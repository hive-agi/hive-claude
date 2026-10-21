(ns hive-claude.init
  "IAddon implementation for hive-claude, the Claude Code headless backend.

   Follows the vterm-mcp exemplar: reify + nil-railway pipeline.
   Zero compile-time hive-mcp dependencies: all resolved via requiring-resolve.

   Registers:
   1. :claude-process headless backend (via headless-registry, ProcessBuilder)
   NOTE: :claude-sdk headless backend is owned by hive-agent-bridge (pure Clojure).

   Vessel-neutral: hive-claude names no editor. The interactive :claude
   terminal (a Claude Code session inside an editor buffer) is contributed by
   the vessel that hosts it (hive-emacs registers it from
   `hive-emacs.claude-terminal`), so this addon loads, runs and reports healthy
   with no editor running.

   Usage:
     ;; Via addon system (auto-discovered from META-INF manifest):
     (init-as-addon!)"
  (:require [hive-addon.protocol :as addon]
            [hive-claude.log :as log]
            [clojure.set :as set]
            [hive-claude.guard.projection :as guard-projection]
            [hive-spi.guard.ports :as gp]
            [hive-claude.util :as util]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Resolution Helpers
;; =============================================================================

(def ^:private try-resolve util/try-resolve)


;; =============================================================================
;; IAddon Implementation
;; =============================================================================

(defonce ^:private addon-instance (atom nil))

(defn- make-addon
  "Create an IAddon reify for hive-claude."
  []
  (let [state (atom {:initialized? false})]
    (reify
      addon/IAddon

        (addon-id [_] "hive.claude")

        (addon-type [_] :native)

        (capabilities [_] #{:headless :health-reporting})

        (initialize! [_ _config]
          (if (:initialized? @state)
            {:success? true :already-initialized? true}
            (let [registered-ids (atom [])]
              ;; 1. Headless Process backend (ProcessBuilder)
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

              ;; 2. Reconcile: deregister stale headless entries from previous sessions
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
                                   :registered-ids ids})
                    (log/info "hive-claude addon initialized" {:registered ids})
                    {:success? true
                     :errors []
                     :metadata {:registered-ids ids}})
                  {:success? false
                   :errors ["No backends could be registered"]})))))

        (shutdown! [_]
          (when (:initialized? @state)
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
            {:status  :ok
             :details {:registered-ids (:registered-ids @state)}}
            {:status :down
             :details {:reason "not initialized"}})))))

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

(defn- step-resolve-deps
  "Resolve the host symbols for ctx. A caller may supply its own symbol map
   under :registry (a port: any map of key -> qualified symbol); the default
   is the addon's own `dep-registry`."
  [{:keys [registry] :as ctx}]
  (when-let [deps (resolve-deps (or registry @dep-registry))]
    (merge ctx deps)))

(defn- step-register [{:keys [addon register!] :as ctx}]
  (let [result (register! addon)]
    (when (:success? result)
      (assoc ctx :reg-result result))))

(defn- step-init [{:keys [addon addon-id init!] :as ctx}]
  (let [result (init! (addon-id addon))]
    (when (:success? result)
      (assoc ctx :init-result result))))

(defn- step-store-instance
  "Remember the registered addon in ctx's :instance-store (any atom) when a
   caller supplies one, else in the addon's own `addon-instance`."
  [{:keys [addon instance-store] :as ctx}]
  (reset! (or instance-store addon-instance) addon)
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
  "Register hive-claude as an IAddon. Returns registration result.

   The one-arity form takes the seams the pipeline reads, so a caller (a test,
   an embedding host) can substitute them without redefining a var:
     :registry       map of :register!/:init!/:addon-id -> qualified symbol
                     naming the host fns (default: `dep-registry`)
     :instance-store atom receiving the registered addon
                     (default: the one behind `get-addon-instance`)"
  ([] (init-as-addon! {}))
  ([{:keys [registry instance-store]}]
   (if-let [_result (some-> (make-addon)
                            (as-> addon (run-addon-pipeline! (cond-> {:addon addon}
                                                               registry       (assoc :registry registry)
                                                               instance-store (assoc :instance-store instance-store)))))]
     (do
       (log/info "hive-claude registered as IAddon")
       {:registered ["claude"] :total 1})
     (do
       (log/debug "IAddon unavailable — hive-claude addon registration failed")
       {:registered [] :total 0}))))

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