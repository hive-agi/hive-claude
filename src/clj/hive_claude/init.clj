(ns hive-claude.init
  "IAddon implementation for hive-claude — Claude Code terminal + headless backends.

   Follows the vterm-mcp exemplar: reify + nil-railway pipeline.
   Zero compile-time hive-mcp dependencies — all resolved via requiring-resolve.

   Registers:
   1. :claude terminal backend (existing, via terminal-registry)
   2. :claude-process headless backend (via headless-registry, ProcessBuilder)
   NOTE: :claude-sdk headless backend is owned by hive-agent-bridge (pure Clojure).

   Elisp loading:
   On initialize!, injects resources/elisp/ into Emacs load-path and requires
   hive-claude-config, hive-claude-state, hive-claude-bridge (in dependency order).
   Follows the lsp-mcp exemplar for load-path injection. The
   session-fingerprinted reload cache lives in
   `hive-claude.elisp-load-state` — split out so the cache can be exercised
   without pulling hive-mcp.addons.* onto the test classpath.

   Usage:
     ;; Via addon system (auto-discovered from META-INF manifest):
     (init-as-addon!)"
  (:require [hive-claude.terminal :as terminal]
            [hive-claude.log :as log]
            [hive-claude.elisp-load-state :as els]
            [hive-dsl.result :as r]
            [clojure.set :as set]
            [hive-claude.guard.projection :as guard-projection]
            [hive-spi.guard.ports :as gp]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Resolution Helpers
;; =============================================================================

(defn- try-resolve
  "Attempt to resolve a fully-qualified symbol. Returns var or nil."
  [sym]
  (try (requiring-resolve sym) (catch Exception _ nil)))

;; =============================================================================
;; Elisp Load State — thin re-exports for backwards compat.
;; The real implementation lives in hive-claude.elisp-load-state.
;; =============================================================================

(def reset-elisp-load-state! els/reset-elisp-load-state!)
(def elisp-loaded?          els/elisp-loaded?)
(def ensure-elisp-loaded!   els/ensure-elisp-loaded!)

;; =============================================================================
;; IAddon Implementation
;; =============================================================================

(defonce ^:private addon-instance (atom nil))

(defn- make-addon
  "Create an IAddon reify for hive-claude.
   Returns nil if protocol is not on classpath."
  []
  (when (try-resolve 'hive-mcp.addons.protocol/IAddon)
    (let [state (atom {:initialized? false})]
      (reify
        hive-mcp.addons.protocol/IAddon

        (addon-id [_] "hive.claude")

        (addon-type [_] :native)

        (capabilities [_] #{:terminal :headless :health-reporting})

        (initialize! [_ _config]
          (if (:initialized? @state)
            {:success? true :already-initialized? true}
            (let [;; Load elisp into Emacs first (idempotent, best-effort)
                  _elisp-ok? (ensure-elisp-loaded!)
                  claude-addon (terminal/make-claude-terminal)
                  registered-ids (atom [])]
              ;; 1. Terminal backend (existing)
              (when claude-addon
                (when-let [register-fn (try-resolve 'hive-mcp.agent.ling.terminal-registry/register-terminal!)]
                  (let [result (register-fn :claude claude-addon)]
                    (when (:registered? result)
                      (swap! registered-ids conj :claude)
                      (log/info "hive-claude: :claude terminal registered")))))

              ;; 2. Headless Process backend (ProcessBuilder)
              ;; NOTE: :claude-sdk is now owned by hive-agent-bridge (pure Clojure,
              ;; no libpython-clj). hive-claude only owns :claude-process.
              (when-let [reg-fn (try-resolve 'hive-mcp.agent.ling.headless-registry/register-headless!)]
                (try
                  (require 'hive-claude.headless.process-backend)
                  (when-let [make-fn (try-resolve 'hive-claude.headless.process-backend/make-claude-process-backend)]
                    (when-let [proc-backend (make-fn)]
                      (let [result (reg-fn :claude-process proc-backend
                                           {:provides #{:claude}
                                            :priority 20})]
                        (when (:registered? result)
                          (swap! registered-ids conj :claude-process)
                          (log/info "hive-claude: :claude-process headless registered")))))
                  (catch Exception e
                    (log/debug "Process backend not available" {:error (ex-message e)}))))

              ;; 3. Reconcile: deregister stale headless entries from previous sessions
              ;; Only :claude-process — :claude-sdk is owned by hive-agent-bridge
              (let [owned-ids #{:claude-process}
                    live-ids  (set (filter owned-ids @registered-ids))
                    stale-ids (set/difference owned-ids live-ids)]
                (when (seq stale-ids)
                  (when-let [dereg-fn (try-resolve 'hive-mcp.agent.ling.headless-registry/deregister-headless!)]
                    (doseq [sid stale-ids]
                      (dereg-fn sid)
                      (log/info "hive-claude: cleaned stale headless entry" {:headless-id sid})))))

              (let [ids @registered-ids]
                (if (seq ids)
                  (do
                    (reset! state {:initialized? true
                                   :terminal-addon claude-addon
                                   :registered-ids ids})
                    (log/info "hive-claude addon initialized" {:registered ids})
                    {:success? true
                     :errors []
                     :metadata {:registered-ids ids}})
                  {:success? false
                   :errors ["No backends could be registered"]})))))

        (shutdown! [_]
          (when (:initialized? @state)
            ;; Unregister sync hooks
            (r/guard Exception nil
                     (when-let [eval-fn (try-resolve 'hive-mcp.emacs.client/eval-elisp-with-timeout)]
                       (eval-fn "(hive-claude-sync-unregister-hooks-bang)" 3000)))
            ;; Deregister terminal
            (when-let [dereg-fn (try-resolve 'hive-mcp.agent.ling.terminal-registry/deregister-terminal!)]
              (dereg-fn :claude))
            ;; Deregister headless backends (only those we own)
            (when-let [dereg-fn (try-resolve 'hive-mcp.agent.ling.headless-registry/deregister-headless!)]
              (doseq [id (filter #{:claude-process} (:registered-ids @state))]
                (dereg-fn id)))
            (reset! state {:initialized? false})
            (log/info "hive-claude addon shut down" {:deregistered (:registered-ids @state)}))
          nil)

        (tools [_] [])

        (schema-extensions [_] {})

        (hooks [_]
          ;; The guard projection for :claude-code, published as inert data. The
          ;; host files it in its extension registry like any hook and the guard
          ;; sweeps that key namespace, so this addon and hive.guard can mount in
          ;; either order. The VAR, not its value — see the var's own docstring.
          {(gp/projection-ext-key guard-projection/harness)
           #'guard-projection/instance})

        (health [_]
          (if (:initialized? @state)
            (let [emacs-ok? (try
                              (when-let [eval-fn (try-resolve 'hive-mcp.emacs.client/eval-elisp-with-timeout)]
                                (let [{:keys [success]} (eval-fn "(featurep 'hive-claude-bridge)" 2000)]
                                  success))
                              (catch Exception _ false))]
              {:status (if emacs-ok? :ok :degraded)
               :details {:terminal-id :claude
                         :emacs-has-hive-claude emacs-ok?}})
            {:status :down
             :details {:reason "not initialized"}}))))))

;; =============================================================================
;; Dep Registry + Nil-Railway Pipeline
;; =============================================================================

(defonce ^:private dep-registry
  (atom {:register! 'hive-mcp.addons.core/register-addon!
         :init!     'hive-mcp.addons.core/init-addon!
         :addon-id  'hive-mcp.addons.protocol/addon-id}))

(defn- resolve-deps
  "Resolve all symbols in registry. Returns ctx map or nil."
  [registry]
  (reduce-kv
   (fn [ctx k sym]
     (if-let [resolved (try-resolve sym)]
       (assoc ctx k resolved)
       (do (log/debug "Dep resolution failed:" k "->" sym)
           (reduced nil))))
   {}
   registry))

(defn- step-resolve-deps [ctx]
  (when-let [deps (resolve-deps @dep-registry)]
    (merge ctx deps)))

(defn- step-register [{:keys [addon register!] :as ctx}]
  (let [result (register! addon)]
    (when (:success? result)
      (assoc ctx :reg-result result))))

(defn- step-init [{:keys [addon addon-id init!] :as ctx}]
  (let [result (init! (addon-id addon))]
    (when (:success? result)
      (assoc ctx :init-result result))))

(defn- step-store-instance [{:keys [addon] :as ctx}]
  (reset! addon-instance addon)
  ctx)

(defn- run-addon-pipeline!
  "Nil-railway: resolve-deps -> register -> init -> store"
  [initial-ctx]
  (some-> initial-ctx
          step-resolve-deps
          step-register
          step-init
          step-store-instance))

;; =============================================================================
;; Public API
;; =============================================================================

(defn init-as-addon!
  "Register hive-claude as an IAddon. Returns registration result."
  []
  (if-let [_result (some-> (make-addon)
                           (as-> addon (run-addon-pipeline! {:addon addon})))]
    (do
      (log/info "hive-claude registered as IAddon")
      {:registered ["claude"] :total 1})
    (do
      (log/debug "IAddon unavailable — hive-claude addon registration failed")
      {:registered [] :total 0})))

(defn addon-ctor
  "Pure constructor for the `hive.claude` IAddon — (config -> IAddon | nil).
   Resolved by the hive-addon.mount composer via :addon/init-fn; the host then
   drives register!/initialize!. Returns the same reify the legacy init-as-addon!
   path constructs (via make-addon), with NO registration/elisp/backend side
   effects — those run in initialize!. Returns nil when the IAddon protocol is
   absent from the classpath (graceful). Tolerates (ignores) mounter-injected
   config keys such as :mount/dependencies. Additive: init-as-addon! remains for
   the current hive-mcp loader."
  [_config]
  (make-addon))

(defn get-addon-instance
  "Return the current IAddon instance, or nil."
  []
  @addon-instance)