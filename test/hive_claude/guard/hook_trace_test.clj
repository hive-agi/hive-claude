(ns hive-claude.guard.hook-trace-test
  "The generated hive-guard hook carries hive-assay's opt-in session-trace
   append (plan H2-09, dev-loop trigger B). These tests EVALUATE the inlined
   resource text, so what is asserted is what the hook runs."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-claude.guard.projection :as p]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private trace-ns
  (do (binding [*ns* (create-ns 'hive-claude.guard.hook-trace-loader)]
        (refer-clojure)
        (load-string p/hook-trace-source))
      (find-ns 'hive-guard-trace)))

(defn- f [sym] @(ns-resolve trace-ns sym))

(defn- tmp-dir []
  (doto (io/file (System/getProperty "java.io.tmpdir") (str "hook-trace-test-" (System/nanoTime)))
    .mkdirs))

(deftest dispatcher-inlines-the-trace-and-calls-it-before-judging
  (let [s (p/dispatcher-script "test")]
    (is (str/includes? s p/hook-trace-source))
    (is (str/includes? s "(in-ns 'user)"))
    (is (< (str/index-of s "(trace! payload)") (str/index-of s "(judge! payload))")))))

(deftest tracing-is-off-unless-switch-and-existing-dir
  (let [dir (tmp-dir)
        payload {:hook_event_name "Stop" :session_id "s"}]
    (is (nil? ((f 'append!) payload {} 1)))
    (is (nil? ((f 'append!) payload {"HIVE_GUARD_TRACE" "1"} 1)))
    (is (nil? ((f 'append!) payload {"HIVE_GUARD_TRACE" "1"
                                     "HIVE_ASSAY_TRACE_DIR" (str dir "/missing")} 1)))
    (is (not (.exists (io/file dir "missing"))))
    (is (empty? (.list dir)))))

(deftest stop-appends-one-redacted-line
  (let [dir (tmp-dir)
        tr  (io/file dir "tr.jsonl")
        _   (spit tr (str "{\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{\"command\":\"ls token=abc123secretvalue\"}}]}}\n"
                          "{\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"boom Bearer abcdefghijklmnop\",\"is_error\":true}]}}\n"))
        env {"HIVE_GUARD_TRACE" "1" "HIVE_ASSAY_TRACE_DIR" (str dir)}
        line ((f 'append!) {:hook_event_name "Stop" :session_id "s1"
                            :transcript_path (str tr)} env 7)
        read (edn/read-string (slurp (io/file dir "s1.edn")))]
    (testing "written line equals the returned line"
      (is (= line read)))
    (testing "failing last step reads as :fail"
      (is (= :fail (:trace/outcome read))))
    (testing "no secret survives"
      (is (not (str/includes? (pr-str read) "abc123secretvalue")))
      (is (not (str/includes? (pr-str read) "abcdefghijklmnop"))))
    (testing "other events write nothing"
      (is (nil? ((f 'append!) {:hook_event_name "PreToolUse" :session_id "s2"} env 8)))
      (is (not (.exists (io/file dir "s2.edn")))))))
