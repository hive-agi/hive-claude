(ns hive-claude.no-host-coupling-test
  "hive-mcp is the HOST and is not published to maven, so any COMPILE-time
   reference to it makes this jar unloadable wherever the host is absent, which
   is everywhere it is fetched from maven.

   Two shapes do that, and only the first is obvious:

     1. a load-time `(:require [hive-mcp...])` in an ns form;
     2. naming a hive-mcp protocol in `reify`, `defrecord`, `deftype`,
        `extend`, `extend-type` or `extend-protocol`.

   The second is the one that bit this repo. It reads as soft resolution when
   it is guarded, and it is not:

     (when (try-resolve 'hive-mcp.addons.terminal/ITerminalAddon)
       (reify hive-mcp.addons.terminal/ITerminalAddon ...))

   `reify` resolves the protocol to emit a class, so the namespace fails to
   compile regardless of the guard, and it fails as `Syntax error compiling
   reify*`, naming neither hive-mcp nor the axiom. Four namespaces here failed
   that way until the protocols moved to hive-addon and hive-spi.

   Runtime resolution stays legal: `(try-resolve 'hive-mcp.foo/bar)` is a
   quoted symbol, not a compile-time reference, and this test does not flag it.

   TEST scope is deliberately not covered: the suite runs with the host on the
   classpath, and the axiom is about the PUBLISHED artifact.

   Axiom 20260727002436-53e3767e. Fleet detector:
   `carto patterns-run :host-protocol-impl-in-addon`."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private impl-forms
  "Forms that resolve a protocol symbol at compile time."
  '#{reify defrecord deftype extend extend-type extend-protocol})

(defn- clj-sources []
  (->> (file-seq (io/file "src"))
       (filter #(.isFile ^java.io.File %))
       (filter #(re-find #"\.cljc?$" (.getName ^java.io.File %)))))

(defn- forms
  "Every top-level form in `file`, or nil if it cannot be read."
  [^java.io.File file]
  (try
    (with-open [r (java.io.PushbackReader. (io/reader file))]
      (let [eof (Object.)]
        (doall (take-while #(not= eof %)
                           (repeatedly #(read {:read-cond :allow :eof eof} r))))))
    (catch Exception _ nil)))

(defn- host-sym? [x]
  (and (symbol? x) (str/starts-with? (str x) "hive-mcp.")))

(defn- ns-form? [form]
  (and (seq? form) (= 'ns (first form))))

(defn- require-violations
  "hive-mcp namespaces an ns form requires at load time."
  [form]
  (when (ns-form? form)
    (->> (tree-seq coll? seq form) (filter host-sym?) (map str) set)))

(defn- impl-violations
  "hive-mcp protocols named in a compile-time implementation form.

   Only BARE elements count: a call such as (hive-mcp.foo/bar x) is a list, so
   its head is never a bare element of the reify body, and a quoted symbol is
   wrapped in (quote ...). Both are runtime, and neither is flagged."
  [form]
  (->> (tree-seq coll? seq form)
       (filter #(and (seq? %) (impl-forms (first %))))
       (mapcat #(filter host-sym? %))
       (map str)
       set))

(defn- scan [violation-fn]
  (->> (clj-sources)
       (keep (fn [f]
               (when-let [hits (seq (mapcat violation-fn (forms f)))]
                 [(.getPath ^java.io.File f) (vec (sort (set hits)))])))
       (into {})))

(def ^:private require-waivers
  "file -> the host namespaces it may still require, each with the card that
   will remove it. A waiver is a DEBT with a name, not an exemption: the test
   below fails if a waived file stops violating, so a waiver cannot outlive the
   thing it waives."
  {"src/clj/hive_claude/sdk/agentic_loop.clj"
   {:requires #{"hive-mcp.agent.agentic-loop"}
    :card     "[AGENTIC-LOOP-PORT] 20260902101929-736fc486"
    :why      (str "porting it needs a licensing decision (this repo is AGPL on "
                   "Clojars, the host is proprietary on Gitea), which is Pedro's "
                   "call and not a refactor")}})

(deftest sources-were-found-test
  (is (seq (clj-sources))
      "no sources were read, so an empty violation set would mean nothing"))

(deftest no-src-namespace-requires-the-host-test
  (let [offenders (scan #(or (require-violations %) []))
        unwaived  (reduce-kv (fn [acc file hits]
                               (let [allowed (get-in require-waivers [file :requires] #{})
                                     extra   (remove allowed hits)]
                                 (cond-> acc (seq extra) (assoc file (vec extra)))))
                             {} offenders)]
    (is (= {} unwaived)
        (str "these src namespaces require the host at load time: " unwaived))))

(deftest every-require-waiver-is-still-needed-test
  (let [offenders (scan #(or (require-violations %) []))]
    (doseq [[file {:keys [requires card]}] require-waivers]
      (let [actual (set (get offenders file))]
        (is (= requires (set/intersection requires actual))
            (str file " no longer requires " (set/difference requires actual)
                 ", so delete that waiver and close " card))))))

(deftest no-src-namespace-implements-a-host-protocol-test
  (let [offenders (scan impl-violations)]
    (is (= {} offenders)
        (str "these src namespaces name a hive-mcp protocol in a compile-time "
             "implementation form, so they cannot load without the host: "
             offenders))))

;; =============================================================================
;; The guard's own falsification
;; =============================================================================

(deftest impl-violations-actually-detects-the-shape-test
  (let [offending '(when (try-resolve 'hive-mcp.addons.terminal/ITerminalAddon)
                     (reify hive-mcp.addons.terminal/ITerminalAddon
                       (terminal-id [_] :claude)))
        innocent  '(defn f [x]
                     (when-let [g (try-resolve 'hive-mcp.emacs.client/eval-elisp-with-timeout)]
                       (g x)))]
    (is (= #{"hive-mcp.addons.terminal/ITerminalAddon"} (impl-violations offending))
        "the guard must catch a reify on a host protocol, guard clause and all")
    (is (= #{} (impl-violations innocent))
        "and must NOT flag runtime resolution of a host var, which is legal")))
