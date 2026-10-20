(ns hive-claude.guard.hook-ledger-test
  "The generated hive-guard hook fails open, and every fail-open appends a
   `:guard/unjudged` line to the guard ledger so guard downtime can be counted.

   The hook's ledger code lives in `resources/hive_claude/guard/hook_ledger.bb`
   and is inlined into the dispatcher. These tests EVALUATE THAT SAME TEXT, so
   what is asserted is what the hook runs, not a copy of it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-claude.guard.projection :as p]
            [hive-test.trifecta :refer [deftrifecta]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private hook-ns
  "The hook's ledger source, loaded into a namespace of its own with the same
   `io` and `str` aliases the dispatcher requires before inlining it."
  (let [n (create-ns 'hive-claude.guard.hook-ledger-under-test)]
    (binding [*ns* n]
      (refer-clojure)
      (alias 'io (find-ns 'clojure.java.io))
      (alias 'str (find-ns 'clojure.string))
      (load-string p/hook-ledger-source))
    n))

(defn- hook-fn [sym] @(ns-resolve hook-ns sym))

(defn- tmp-path []
  (str (System/getProperty "java.io.tmpdir")
       "/hook-ledger-test-" (System/nanoTime) "/guard-ledger.ednl"))

(defn- read-lines [path]
  (when (.exists (io/file path))
    (mapv edn/read-string (str/split-lines (slurp path)))))

(defn- cleanup! [path]
  (io/delete-file (io/file path) true)
  (io/delete-file (.getParentFile (io/file path)) true))

(defn appended-record
  "Append one fail-open for `{:reason :payload}` to a fresh ledger and answer
   `{:written? :lines}`, the lines read back as EDN. One argument, as a
   trifecta subject takes."
  [{:keys [reason payload]}]
  (let [path (tmp-path)]
    (try
      (let [ok? ((hook-fn 'append-unjudged!) path reason payload 42)]
        {:written? ok? :lines (read-lines path)})
      (finally (cleanup! path)))))

(deftrifecta fail-open-appends-one-unjudged-record
  hive-claude.guard.hook-ledger-test/appended-record
  {:golden-path "test/golden/guard/hook-unjudged-record.edn"
   :cases {:unreachable {:reason "7910: unreachable: Connection refused"
                         :payload {:tool_name "Bash" :hook_event_name "PreToolUse"}}
           :unreadable  {:reason "unreadable payload" :payload nil}
           :seam-gap    {:reason ":remote-threw boom" :payload {:tool_name "Edit"}}}
   :gen (gen/fmap (fn [[r t]] {:reason r :payload {:tool_name t}})
                  (gen/tuple (gen/not-empty gen/string-alphanumeric)
                             (gen/not-empty gen/string-alphanumeric)))
   :pred (fn [{:keys [written? lines]}]
           (and written?
                (= 1 (count lines))
                (= :guard/unjudged (:rule-id (first lines)))
                (= 42 (:at (first lines)))
                (string? (:reason (first lines)))))
   :num-tests 25
   :mutations [["writes-nothing" (fn [_] {:written? true :lines []})]
               ["wrong-rule-id" (fn [_] {:written? true
                                         :lines [{:rule-id :guard/deny :at 42 :reason "x"}]})]]})

(deftest the-record-names-reason-tool-and-time
  (let [r ((hook-fn 'unjudged-record) "nREPL down" {:tool_name "Bash" :hook_event_name "PreToolUse"} 99)]
    (is (= {:rule-id :guard/unjudged :reason "nREPL down" :tool "Bash"
            :event "PreToolUse" :at 99}
           r))))

(deftest an-unreadable-payload-still-records-without-a-tool
  (doseq [payload [nil "garbage" [] {}]]
    (let [r ((hook-fn 'unjudged-record) "unreadable payload" payload 1)]
      (is (= :guard/unjudged (:rule-id r)))
      (is (not (contains? r :tool)) (pr-str payload)))))

(deftest a-huge-reason-is-clipped
  (let [r ((hook-fn 'unjudged-record) (apply str (repeat 100000 "x")) {:tool_name "Bash"} 1)]
    (is (= 512 (count (:reason r))))))

(deftest records-accumulate-one-line-each
  (let [path (tmp-path)
        append! (hook-fn 'append-unjudged!)]
    (try
      (dotimes [i 3] (append! path (str "r" i) {:tool_name "Bash"} i))
      (is (= ["r0" "r1" "r2"] (mapv :reason (read-lines path))))
      (finally (cleanup! path)))))

(deftest an-unwritable-ledger-is-swallowed
  (testing "the path's parent is a regular file, so it can neither be made nor appended"
    (let [blocker (java.io.File/createTempFile "hook-ledger-blocker" ".txt")
          path    (str blocker "/sub/guard-ledger.ednl")]
      (try
        (is (false? ((hook-fn 'append-unjudged!) path "r" {:tool_name "Bash"} 1)))
        (is (false? ((hook-fn 'append-unjudged!) nil "r" {:tool_name "Bash"} 1))
            "even a nil path must not throw")
        (finally (.delete blocker))))))

(deftest the-ledger-path-honours-the-env-then-the-jvm-default
  (let [lp (hook-fn 'ledger-path)]
    (is (= "/x/ledger.ednl" (lp "/x/ledger.ednl" "/home/u")))
    (is (= "/home/u/.config/hive-mcp/guard-ledger.ednl" (lp nil "/home/u")))
    (is (= "/home/u/.config/hive-mcp/guard-ledger.ednl" (lp "  " "/home/u"))
        "a blank env value is no value")))

(deftest the-dispatcher-records-before-it-exits
  (let [script (p/dispatcher-script "one line")]
    (is (str/includes? script (str/trim p/hook-ledger-source))
        "the script inlines the very text these tests evaluate")
    (testing "every fail-open goes through open!, which records, then prints {} and exits 0"
      (let [forms (read-string (str "[" (subs script (str/index-of script "(require")) "]"))
            open  (some (fn [f] (when (and (seq? f) (= 'defn (first f)) (= 'open! (second f))) f)) forms)
            text  (pr-str open)]
        (is (some? open))
        (is (str/includes? text "record-unjudged!"))
        (is (< (str/index-of text "record-unjudged!") (str/index-of text "System/exit"))
            "the record is appended before the process exits")))
    (testing "a crash of the hook's own code fails open too"
      (is (str/includes? script "hook crashed")))))
