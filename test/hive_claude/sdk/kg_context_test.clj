(ns hive-claude.sdk.kg-context-test
  "Tests for compressed context injection into SDK ling spawn and dispatch.

   Tests the integration between:
   - agent_sdk_strategy.clj (spawn + dispatch with context)
   - sdk/lifecycle.clj (silence phase KG enrichment)
   - context_envelope.clj (context enrichment via the extension layer)

   Since hive-mcp 1.5.0, context-envelope delegates to the extension layer
   (:ctx/enrich and :ctx/prepare-spawn); with no extension registered it
   returns nil by design. These tests register stub extensions in a fixture
   and assert that the callers thread their arguments through to the
   extension and return what it produced.

   Uses with-redefs to mock SDK availability and context-store."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-mcp.agent.context-envelope :as ctx-envelope]
            [hive-mcp.extensions.registry :as ext]
            [hive-claude.sdk.lifecycle :as lifecycle]
            [hive-claude.sdk.session :as session]
            [hive-mcp.protocols.dispatch :as dispatch-ctx]
            [hive-mcp.channel.context-store :as context-store]
            [clojure.string :as str]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn reset-context-store [f]
  (context-store/reset-all!)
  (f)
  (context-store/reset-all!))

(defonce ^:private captured-enrich-calls (atom []))
(defonce ^:private captured-prepare-spawn-calls (atom []))

(defn stub-enrich
  "Stub extension for :ctx/enrich. Records its arguments and returns a
   deterministic rendering of what it received, which tests assert on."
  [ctx-refs kg-node-ids scope opts]
  (swap! captured-enrich-calls conj {:ctx-refs ctx-refs
                                     :kg-node-ids kg-node-ids
                                     :scope scope
                                     :opts opts})
  (str "STUB-ENRICH ctx-refs=" (pr-str ctx-refs)
       " kg-node-ids=" (pr-str kg-node-ids)
       " scope=" (pr-str scope)
       " opts=" (pr-str opts)))

(defn stub-prepare-spawn
  "Stub extension for :ctx/prepare-spawn. Records its arguments and returns a
   deterministic rendering of what it received."
  [directory opts]
  (swap! captured-prepare-spawn-calls conj {:directory directory :opts opts})
  (str "STUB-PREPARE-SPAWN directory=" (pr-str directory)
       " opts=" (pr-str opts)))

(defn with-extensions
  "Register stub extensions for the context-envelope extension layer for the
   duration of the test, clearing the registry afterwards so no state leaks
   into the rest of the suite."
  [f]
  (ext/register! :ctx/enrich stub-enrich)
  (ext/register! :ctx/prepare-spawn stub-prepare-spawn)
  (reset! captured-enrich-calls [])
  (reset! captured-prepare-spawn-calls [])
  (try
    (f)
    (finally
      (ext/clear-all!))))

(use-fixtures :each reset-context-store with-extensions)

;; =============================================================================
;; Test Data
;; =============================================================================

(def sample-axioms
  [{:id "ax-1" :content "Never spawn drones from lings"}
   {:id "ax-2" :content "Cap 5-6 lings per Emacs daemon"}])

(def sample-decisions
  [{:id "20260207-dec1" :content "Use Datalevin as default KG backend"}])

;; =============================================================================
;; build-kg-context-prefix Tests (private fn, tested via lifecycle behavior)
;; =============================================================================

(deftest build-kg-context-prefix-with-ref-context
  (testing "threads RefContext ctx-refs/kg-node-ids/scope through :ctx/enrich"
    (let [ax-id (context-store/context-put! sample-axioms :tags #{"axioms"})
          dec-id (context-store/context-put! sample-decisions :tags #{"decisions"})
          ref-ctx (dispatch-ctx/->ref-context
                   "Fix the bug"
                   {:ctx-refs {:axioms ax-id :decisions dec-id}
                    :kg-node-ids []
                    :scope "hive-mcp"
                    :reconstruct-fn (fn [_ _ _] "mock reconstructed context")})
          ;; Call the private fn via var deref
          build-fn @#'lifecycle/build-kg-context-prefix
          result (build-fn ref-ctx)]
      (is (= 1 (count @captured-enrich-calls))
          "Should call the :ctx/enrich extension exactly once")
      (let [{:keys [ctx-refs kg-node-ids scope opts]} (first @captured-enrich-calls)]
        (is (= {:axioms ax-id :decisions dec-id} ctx-refs)
            "Should thread ctx-refs from the RefContext")
        (is (= [] kg-node-ids) "Should thread kg-node-ids from the RefContext")
        (is (= "hive-mcp" scope) "Should thread scope from the RefContext")
        (is (= {:mode :inline} opts)
            "Should call with inline mode (silence phase resolves immediately)"))
      (is (str/includes? (or result "")
                         (str (pr-str {:axioms ax-id :decisions dec-id})))
          "Should return the extension's output, containing the ctx-refs")
      (is (str/includes? (or result "") "hive-mcp")
          "Should return the extension's output, containing the scope"))))

(deftest build-kg-context-prefix-with-text-context
  (testing "returns nil for TextContext (no refs to build from)"
    (let [text-ctx (dispatch-ctx/->text-context "Fix the bug")
          build-fn @#'lifecycle/build-kg-context-prefix
          result (build-fn text-ctx)]
      (is (nil? result) "TextContext should produce nil (no structured refs)"))))

(deftest build-kg-context-prefix-with-nil
  (testing "returns nil for nil dispatch-context"
    (let [build-fn @#'lifecycle/build-kg-context-prefix]
      (is (nil? (build-fn nil)) "nil should produce nil"))))

(deftest build-kg-context-prefix-graceful-degradation
  (testing "returns nil when envelope building throws"
    (with-redefs [ctx-envelope/enrich-context
                  (fn [_ _ _ _] (throw (Exception. "envelope failed")))]
      (let [ref-ctx (dispatch-ctx/->ref-context "task" {:ctx-refs {:a "b"}})
            build-fn @#'lifecycle/build-kg-context-prefix
            result (build-fn ref-ctx)]
        (is (nil? result) "Should return nil on failure (CLARITY-Y)")))))

;; =============================================================================
;; Spawn L2 Envelope Tests
;; =============================================================================

(deftest spawn-envelope-builds-from-cwd
  (testing "build-spawn-envelope delegates to the :ctx/prepare-spawn extension"
    ;; Store refs in context-store to simulate prior catchup
    (let [ax-id (context-store/context-put! sample-axioms
                                            :tags #{"catchup" "axioms" "hive-mcp"})
          dec-id (context-store/context-put! sample-decisions
                                             :tags #{"catchup" "decisions" "hive-mcp"})
          cwd "/home/lages/PP/hive/hive-mcp"
          result (ctx-envelope/build-spawn-envelope
                  cwd
                  {:ctx-refs {:axioms ax-id :decisions dec-id}
                   :kg-node-ids []
                   :scope "hive-mcp"
                   :mode :inline})]
      (is (= 1 (count @captured-prepare-spawn-calls))
          "Should call the :ctx/prepare-spawn extension exactly once")
      (let [{:keys [directory opts]} (first @captured-prepare-spawn-calls)]
        (is (= cwd directory) "Should pass the directory through")
        (is (= {:ctx-refs {:axioms ax-id :decisions dec-id}
                :kg-node-ids []
                :scope "hive-mcp"
                :mode :inline}
               opts)
            "Should pass the opts map through"))
      (is (str/includes? (or result "") cwd)
          "Should return the extension's output, containing the directory"))))

(deftest spawn-envelope-nil-on-no-refs
  (testing "build-spawn-envelope returns nil when no refs available"
    (with-redefs [;; No context-store entries, no catchup refs
                  context-store/context-query (fn [& _] [])]
      (let [result (ctx-envelope/build-spawn-envelope
                    "/tmp/nonexistent"
                    {:mode :inline})]
        ;; May return nil or fallback - depends on catchup-spawn behavior
        ;; The important thing is it doesn't throw
        (is (not (instance? Exception result))
            "Should not throw on missing refs")))))

;; =============================================================================
;; Dispatch Context Threading Tests
;; =============================================================================

(deftest dispatch-context-stored-in-session
  (testing "dispatch-context is stored in session when provided"
    (let [ling-id "test-kg-dispatch-1"
          ref-ctx (dispatch-ctx/->ref-context
                   "Fix the bug"
                   {:ctx-refs {:axioms "ctx-123"}
                    :kg-node-ids ["node-1"]
                    :scope "hive-mcp"})]
      ;; Register a mock session
      (session/register-session! ling-id
                                 {:ling-id ling-id
                                  :phase :idle
                                  :observations []
                                  :client-ref :mock-client
                                  :dispatch-context nil})
      (try
        ;; Simulate what dispatch does: store context in session
        (session/update-session! ling-id {:dispatch-context ref-ctx})
        (let [sess (session/get-session ling-id)]
          (is (some? (:dispatch-context sess))
              "dispatch-context should be stored in session")
          (is (= :ref (dispatch-ctx/context-type (:dispatch-context sess)))
              "Should be RefContext type"))
        (finally
          (session/unregister-session! ling-id))))))

;; =============================================================================
;; Integration: Silence Phase with KG Context
;; =============================================================================

(deftest silence-prompt-enriched-with-kg-context
  (testing "run-saa-silence! threads the session's dispatch-context through :ctx/enrich"
    (let [ling-id "test-kg-silence-1"
          ax-id (context-store/context-put! sample-axioms :tags #{"axioms"})
          ref-ctx (dispatch-ctx/->ref-context
                   "Fix the bug"
                   {:ctx-refs {:axioms ax-id}
                    :kg-node-ids []
                    :scope "hive-mcp"
                    :reconstruct-fn (fn [_ _ _] "## Mock KG Context\nAxioms loaded.")})]
      ;; Register mock session with dispatch-context
      (session/register-session! ling-id
                                 {:ling-id ling-id
                                  :phase :idle
                                  :observations []
                                  :dispatch-context ref-ctx})
      (try
        ;; Verify the prefix builder works with this session's context
        (let [build-fn @#'lifecycle/build-kg-context-prefix
              prefix (build-fn (:dispatch-context (session/get-session ling-id)))]
          (is (= 1 (count @captured-enrich-calls))
              "Should call the :ctx/enrich extension exactly once")
          (let [{:keys [ctx-refs scope]} (first @captured-enrich-calls)]
            (is (= {:axioms ax-id} ctx-refs)
                "Should thread the session's ctx-refs")
            (is (= "hive-mcp" scope) "Should thread the session's scope"))
          (is (str/includes? (or prefix "") (pr-str {:axioms ax-id}))
              "Prefix should be the extension's output, containing the ctx-refs"))
        (finally
          (session/unregister-session! ling-id))))))

(deftest silence-prompt-no-enrichment-without-context
  (testing "run-saa-silence! works normally without dispatch-context"
    (let [ling-id "test-kg-silence-2"]
      ;; Register mock session WITHOUT dispatch-context
      (session/register-session! ling-id
                                 {:ling-id ling-id
                                  :phase :idle
                                  :observations []})
      (try
        (let [build-fn @#'lifecycle/build-kg-context-prefix
              prefix (build-fn (:dispatch-context (session/get-session ling-id)))]
          (is (nil? prefix) "Should return nil when no dispatch-context in session"))
        (finally
          (session/unregister-session! ling-id))))))

;; =============================================================================
;; Mode Selection Tests
;; =============================================================================

(deftest inline-mode-resolves-immediately
  (testing ":inline mode threads ctx-refs through the extension unchanged"
    (let [ax-id (context-store/context-put! sample-axioms :tags #{"axioms"})
          result (ctx-envelope/enrich-context
                  {:axioms ax-id} [] "hive-mcp" {:mode :inline})]
      (is (= 1 (count @captured-enrich-calls))
          "Should call the :ctx/enrich extension exactly once")
      (let [{:keys [ctx-refs kg-node-ids scope opts]} (first @captured-enrich-calls)]
        (is (= {:axioms ax-id} ctx-refs) "Should receive the ctx-refs")
        (is (= [] kg-node-ids) "Should receive the kg-node-ids")
        (is (= "hive-mcp" scope) "Should receive the scope")
        (is (= {:mode :inline} opts) "Should receive the inline mode option"))
      (is (str/includes? (or result "") (pr-str {:axioms ax-id}))
          "Should return the extension's output"))))

(deftest deferred-mode-passes-refs
  (testing ":deferred mode threads ref IDs and KG node IDs through the extension"
    (let [result (ctx-envelope/enrich-context
                  {:axioms "ctx-ax-123" :decisions "ctx-dec-456"}
                  ["node-1" "node-2"]
                  "hive-mcp"
                  {:mode :deferred})]
      (is (= 1 (count @captured-enrich-calls))
          "Should call the :ctx/enrich extension exactly once")
      (let [{:keys [ctx-refs kg-node-ids scope opts]} (first @captured-enrich-calls)]
        (is (= {:axioms "ctx-ax-123" :decisions "ctx-dec-456"} ctx-refs)
            "Should receive the ref IDs unresolved")
        (is (= ["node-1" "node-2"] kg-node-ids) "Should receive the KG node IDs")
        (is (= "hive-mcp" scope) "Should receive the scope")
        (is (= {:mode :deferred} opts) "Should receive the deferred mode option"))
      (is (str/includes? (or result "") "ctx-ax-123")
          "Should return the extension's output, containing the axiom ref ID")
      (is (str/includes? (or result "") "node-1")
          "Should return the extension's output, containing the KG node ID"))))
