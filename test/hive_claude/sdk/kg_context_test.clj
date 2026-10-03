(ns hive-claude.sdk.kg-context-test
  "Tests for compressed context injection into the SDK silence phase.

   The subject is `hive-claude.sdk.lifecycle/build-kg-context-prefix`. It
   reaches the host only by NAME at call time, through two ports:

   - `hive-mcp.protocols.dispatch/context-type`   (what kind of dispatch context)
   - `hive-mcp.agent.context-envelope/enrich-context` (build the L2 envelope)

   An addon never requires hive-mcp (axiom 20260727002436-53e3767e), so these
   tests do not either: each port is a STUB interned under the name the subject
   resolves (axiom 20260726172005-562432b9), the same seam
   elisp_load_state_test uses for hive-mcp.emacs.client. What the host's own
   envelope builder produces is hive-mcp's to test, not this addon's.

   The sdk/ subtree is slated for removal (SDK-RETIRE-3, 20260615173947-5b3ec79b);
   these tests go with it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-claude.sdk.lifecycle :as lifecycle]
            [hive-claude.sdk.session :as session]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Port stubs
;; =============================================================================

(def ^:private dispatch-port 'hive-mcp.protocols.dispatch)
(def ^:private envelope-port 'hive-mcp.agent.context-envelope)

(def ^:private enrich-calls
  "Arguments each call to the stubbed enrich-context received."
  (atom []))

(defn- stub-context-type
  "A dispatch context here is a plain map tagged with its :type."
  [ctx]
  (:type ctx))

(defn- stub-enrich-context
  [ctx-refs kg-node-ids scope opts]
  (swap! enrich-calls conj [ctx-refs kg-node-ids scope opts])
  (str "--- L2-CONTEXT mode=" (name (:mode opts)) " ---\n"
       "refs=" (pr-str ctx-refs) " scope=" scope))

(defn- install-port! [ns-sym var-sym f]
  (create-ns ns-sym)
  (intern ns-sym var-sym f))

(defn- uninstall-ports! []
  (ns-unmap dispatch-port 'context-type)
  (ns-unmap envelope-port 'enrich-context))

(use-fixtures :each
  (fn [f]
    (reset! enrich-calls [])
    (install-port! dispatch-port 'context-type stub-context-type)
    (install-port! envelope-port 'enrich-context stub-enrich-context)
    (try (f) (finally (uninstall-ports!)))))

(def ^:private build-prefix @#'lifecycle/build-kg-context-prefix)

(defn- ref-context [ctx-refs]
  {:type :ref
   :content "Fix the bug"
   :ctx-refs ctx-refs
   :kg-node-ids ["node-1"]
   :scope "hive-mcp"})

;; =============================================================================
;; build-kg-context-prefix
;; =============================================================================

(deftest build-kg-context-prefix-with-ref-context
  (testing "a RefContext is enriched inline through the envelope port"
    (let [result (build-prefix (ref-context {:axioms "ctx-ax" :decisions "ctx-dec"}))]
      (is (str/includes? result "L2-CONTEXT"))
      (is (= [[{:axioms "ctx-ax" :decisions "ctx-dec"} ["node-1"] "hive-mcp" {:mode :inline}]]
             @enrich-calls)
          "refs, node ids and scope reach the port unchanged, in :inline mode"))))

(deftest build-kg-context-prefix-with-text-context
  (testing "a TextContext has no refs to build from"
    (is (nil? (build-prefix {:type :text :content "Fix the bug"})))
    (is (empty? @enrich-calls) "the envelope port is not asked")))

(deftest build-kg-context-prefix-with-nil
  (is (nil? (build-prefix nil))))

(deftest build-kg-context-prefix-graceful-degradation
  (testing "an envelope port that throws yields nil, not an exception"
    (intern envelope-port 'enrich-context
            (fn [_ _ _ _] (throw (Exception. "envelope failed"))))
    (is (nil? (build-prefix (ref-context {:a "b"}))))))

(deftest build-kg-context-prefix-without-host
  (testing "with no host ports on the classpath the prefix is nil"
    (uninstall-ports!)
    (is (nil? (build-prefix (ref-context {:a "b"}))))))

;; =============================================================================
;; Dispatch context threaded through the session
;; =============================================================================

(deftest dispatch-context-stored-in-session
  (let [ling-id "test-kg-dispatch-1"
        ctx (ref-context {:axioms "ctx-123"})]
    (session/register-session! ling-id {:ling-id ling-id :phase :idle
                                        :observations [] :dispatch-context nil})
    (try
      (session/update-session! ling-id {:dispatch-context ctx})
      (is (= ctx (:dispatch-context (session/get-session ling-id))))
      (finally (session/unregister-session! ling-id)))))

(deftest silence-prompt-enriched-with-kg-context
  (let [ling-id "test-kg-silence-1"]
    (session/register-session! ling-id {:ling-id ling-id :phase :idle :observations []
                                        :dispatch-context (ref-context {:axioms "ctx-ax"})})
    (try
      (let [prefix (build-prefix (:dispatch-context (session/get-session ling-id)))]
        (is (str/includes? prefix "L2-CONTEXT")))
      (finally (session/unregister-session! ling-id)))))

(deftest silence-prompt-no-enrichment-without-context
  (let [ling-id "test-kg-silence-2"]
    (session/register-session! ling-id {:ling-id ling-id :phase :idle :observations []})
    (try
      (is (nil? (build-prefix (:dispatch-context (session/get-session ling-id)))))
      (finally (session/unregister-session! ling-id)))))
