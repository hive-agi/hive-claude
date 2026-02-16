(ns hive-claude.headless.sdk-backend
  "Claude SDK headless backend implementing IHeadlessBackend + optional capability protocols.

   Wraps hive-claude.sdk.lifecycle functions (spawn/dispatch/kill/interrupt/status).
   Declares full capability set including hooks, interrupts, subagents, checkpointing."
  (:require [hive-claude.sdk.facade :as sdk]
            [hive-claude.util :refer [try-resolve rescue]]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; IHeadlessBackend + Capability Protocols Implementation
;; =============================================================================

(defn make-claude-sdk-backend
  "Create a Claude SDK backend implementing IHeadlessBackend + optional caps.
   Returns nil if the SDK is not available or protocols not on classpath."
  []
  (when-let [headless-proto-ns (try-resolve 'hive-mcp.addons.headless/IHeadlessBackend)]
    (when (sdk/available?)
      (let [guards-fn (fn []
                        (when-let [f (try-resolve 'hive-mcp.server.guards/child-ling-env)]
                          (f)))]
        (reify
          hive-mcp.addons.headless/IHeadlessBackend

          (headless-id [_] :claude-sdk)

          (headless-spawn! [_ ctx opts]
            (let [{:keys [id cwd presets model agents]} ctx
                  {:keys [task]} opts
                  effective-agents (or (:agents opts) agents)
                  guard-env (or (guards-fn) {})
                  system-prompt (str "Agent " id " in project. Use hive-mcp tools for coordination.")
                  mcp-servers {"hive" {"type" "stdio"
                                       "command" "bb"
                                       "args" ["-cp" (str cwd "/src") "-m" "hive-mcp.server.core"]
                                       "env" (merge {"BB_MCP_PROJECT_DIR" cwd} guard-env)}}
                  spawn-env (cond-> (merge {"CLAUDE_SWARM_SLAVE_ID" id
                                            "BB_MCP_PROJECT_DIR" cwd}
                                           guard-env)
                              (and model (not= model "claude"))
                              (assoc "OPENROUTER_MODEL" model))
                  result (try
                           (sdk/spawn-headless-sdk! id (cond-> {:cwd cwd
                                                                :system-prompt system-prompt
                                                                :mcp-servers mcp-servers
                                                                :presets presets
                                                                :env spawn-env}
                                                         effective-agents (assoc :agents effective-agents)))
                           (catch Exception e
                             (log/error "Agent SDK spawn failed, cleaning up"
                                        {:ling-id id :error (ex-message e)})
                             (try (sdk/kill-headless-sdk! id) (catch Exception _ nil))
                             (throw e)))]
              (log/info "Ling spawned via Agent SDK" {:id id :cwd cwd :model (or model "claude")
                                                      :backend :claude-sdk :phase (:phase result)
                                                      :agents-count (count effective-agents)})
              (when task
                (sdk/dispatch-headless-sdk! id task))
              id))

          (headless-dispatch! [_ ctx task-opts]
            (let [{:keys [id]} ctx
                  {:keys [task]} task-opts
                  dispatch-opts (cond-> (select-keys task-opts [:skip-silence? :skip-abstract? :phase :raw?])
                                  (:dispatch-context task-opts)
                                  (assoc :dispatch-context (:dispatch-context task-opts)))]
              (when-not (sdk/get-session id)
                (throw (ex-info "Agent SDK session not found for dispatch"
                                {:ling-id id})))
              (let [result-ch (sdk/dispatch-headless-sdk! id task dispatch-opts)]
                (log/info "Task dispatched to Agent SDK ling" {:ling-id id
                                                               :has-result-ch? (some? result-ch)
                                                               :raw? (:raw? dispatch-opts false)})
                result-ch)))

          (headless-status [_ ctx ds-status]
            (let [{:keys [id]} ctx
                  sdk-info (sdk/sdk-status-for id)]
              (if sdk-info
                (cond-> (or ds-status {})
                  true (assoc :slave/id id
                              :ling/spawn-mode :agent-sdk
                              :sdk-alive? true
                              :sdk-phase (:phase sdk-info)
                              :sdk-session-id (:session-id sdk-info)
                              :sdk-observations-count (:observations-count sdk-info)
                              :sdk-started-at (:started-at sdk-info)
                              :sdk-uptime-ms (:uptime-ms sdk-info)
                              :sdk-backend (:backend sdk-info)
                              :sdk-turn-count (or (:turn-count sdk-info) 0)
                              :sdk-has-persistent-client? (boolean (:has-persistent-client? sdk-info))
                              :sdk-interruptable? (boolean (:interruptable? sdk-info)))
                  (nil? ds-status) (assoc :slave/status :idle))
                (if ds-status
                  (assoc ds-status :sdk-alive? false)
                  nil))))

          (headless-kill! [_ ctx]
            (let [{:keys [id]} ctx]
              (try
                (sdk/kill-headless-sdk! id)
                (log/info "Agent SDK ling killed" {:id id})
                {:killed? true :id id :backend :agent-sdk}
                (catch clojure.lang.ExceptionInfo e
                  (log/warn "Agent SDK kill exception" {:id id :error (ex-message e)})
                  {:killed? true :id id :reason :session-not-found}))))

          (headless-interrupt! [_ ctx]
            (let [{:keys [id]} ctx]
              (log/info "Interrupting Agent SDK ling" {:id id})
              (sdk/interrupt-headless-sdk! id)))

          hive-mcp.addons.headless/IHeadlessCapabilities

          (declared-capabilities [_]
            #{:cap/hooks :cap/interrupts :cap/subagents :cap/checkpointing
              :cap/mcp-tools :cap/streaming :cap/multi-turn :cap/budget-guard :cap/saa})

          ;; Optional capability protocols
          hive-mcp.addons.headless-caps/IHookable

          (register-hooks! [_ ling-id hooks-map]
            (log/info "Registering hooks for SDK ling" {:ling-id ling-id
                                                        :hooks (keys hooks-map)})
            ;; Hook registration handled via SDK phase hooks system
            {:registered? true :hook-count (count hooks-map)})

          (active-hooks [_ ling-id]
            (when-let [sess (sdk/get-session ling-id)]
              (:hooks sess)))

          hive-mcp.addons.headless-caps/ISubagentHost

          (register-subagents! [_ ling-id agent-defs]
            (log/info "Registering subagents for SDK ling" {:ling-id ling-id
                                                            :count (count agent-defs)})
            {:registered? true :agent-count (count agent-defs)})

          (list-subagents [_ ling-id]
            (when-let [sess (sdk/get-session ling-id)]
              (:agents sess)))

          hive-mcp.addons.headless-caps/IBudgetGuardable

          (set-budget! [_ ling-id max-usd]
            (rescue {:budget-set? false :max-usd max-usd}
                    (when-let [register-fn (try-resolve 'hive-mcp.agent.hooks.budget/register-budget!)]
                      (register-fn ling-id max-usd {})
                      {:budget-set? true :max-usd max-usd})))

          (budget-status [_ ling-id]
            (rescue nil
                    (when-let [status-fn (try-resolve 'hive-mcp.agent.hooks.budget/budget-status)]
                      (status-fn ling-id)))))))))
