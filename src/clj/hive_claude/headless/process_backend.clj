(ns hive-claude.headless.process-backend
  "ProcessBuilder headless backend implementing IHeadlessBackend.

   Runs `claude` CLI as subprocess. No optional capability protocols —
   only basic streaming and multi-turn.
   headless-interrupt! returns {:success? false :reason :not-supported}."
  (:require [hive-claude.headless.process :as process]
            [hive-claude.util :refer [try-resolve rescue]]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Context Envelope Helpers (resolved at runtime)
;; =============================================================================

(defn- build-spawn-system-prompt
  "Build context envelope string for headless spawn system prompt."
  [cwd opts]
  (rescue nil
          (let [{:keys [ctx-refs kg-node-ids scope]} opts]
            (if (or (seq ctx-refs) (seq kg-node-ids))
              (when-let [enrich-fn (try-resolve 'hive-mcp.agent.context-envelope/enrich-context)]
                (let [envelope (enrich-fn ctx-refs kg-node-ids scope {})]
                  (when envelope
                    (log/info "context spawn system-prompt built from explicit refs"
                              {:categories (count ctx-refs) :kg-nodes (count kg-node-ids)})
                    envelope)))
              (when-let [build-fn (try-resolve 'hive-mcp.agent.context-envelope/build-spawn-envelope)]
                (let [envelope (build-fn cwd {})]
                  (when envelope
                    (log/info "context spawn system-prompt built from context-store lookup" {:cwd cwd})
                    envelope)))))))

(defn- enrich-task-with-context
  "Enrich a task string with context envelope when dispatch-context is available."
  [task dispatch-context]
  (rescue task
          (when-let [envelope-fn (try-resolve 'hive-mcp.agent.context-envelope/envelope-from-dispatch-context)]
            (if-let [envelope (envelope-fn dispatch-context {})]
              (do
                (log/info "context envelope built for headless dispatch"
                          {:envelope-chars (count envelope)
                           :task-chars (count (or task ""))})
                (str envelope "\n\n---\n\n" task))
              task))))

;; =============================================================================
;; IHeadlessBackend Implementation
;; =============================================================================

(defn make-claude-process-backend
  "Create a ProcessBuilder backend implementing IHeadlessBackend.
   Returns nil if the headless process module is not available or
   protocols not on classpath."
  []
  (when (try-resolve 'hive-mcp.addons.headless/IHeadlessBackend)
    (when (process/available?)
      (reify
        hive-mcp.addons.headless/IHeadlessBackend

        (headless-id [_] :claude-process)

        (headless-spawn! [_ ctx opts]
          (let [{:keys [id cwd presets model]} ctx
                {:keys [task buffer-capacity env-extra]} opts
                system-prompt (build-spawn-system-prompt cwd opts)
                result (process/spawn-process! id (cond-> {:cwd cwd
                                                           :task task
                                                           :presets (or (:presets opts) presets)
                                                           :model model
                                                           :buffer-capacity (or buffer-capacity 5000)}
                                                    system-prompt (assoc :system-prompt system-prompt)
                                                    env-extra (assoc :env-extra env-extra)))]
            (log/info "Ling spawned headless (process)" {:id id :pid (:pid result) :cwd cwd
                                                         :model (or model "claude")
                                                         :system-prompt? (some? system-prompt)})
            id))

        (headless-dispatch! [_ ctx task-opts]
          (let [{:keys [id]} ctx
                {:keys [task dispatch-context]} task-opts
                enriched-task (if dispatch-context
                                (enrich-task-with-context task dispatch-context)
                                task)]
            (process/dispatch-via-stdin! id enriched-task)
            (log/info "Task dispatched to headless ling via stdin" {:ling-id id
                                                                    :enriched? (not= task enriched-task)})
            true))

        (headless-status [_ ctx ds-status]
          (let [{:keys [id]} ctx
                headless-info (process/process-status id)]
            (if ds-status
              (cond-> ds-status
                headless-info (assoc :headless-alive? (:alive? headless-info)
                                     :headless-pid (:pid headless-info)
                                     :headless-uptime-ms (:uptime-ms headless-info)
                                     :headless-stdout (:stdout headless-info)
                                     :headless-stderr (:stderr headless-info)))
              (when headless-info
                {:slave/id id
                 :slave/status (if (:alive? headless-info) :idle :dead)
                 :ling/spawn-mode :headless
                 :headless-alive? (:alive? headless-info)
                 :headless-pid (:pid headless-info)}))))

        (headless-kill! [_ ctx]
          (let [{:keys [id]} ctx]
            (try
              (let [result (process/kill-process! id)]
                (log/info "Headless ling killed" {:id id :pid (:pid result)})
                {:killed? true :id id :pid (:pid result)})
              (catch Exception e
                (log/warn "Headless kill exception" {:id id :error (ex-message e)})
                {:killed? true :id id :reason :process-already-dead}))))

        (headless-interrupt! [_ ctx]
          (let [{:keys [id]} ctx]
            {:success? false
             :ling-id id
             :reason :not-supported
             :errors ["Interrupt not supported for headless (ProcessBuilder) spawn mode"]}))

        hive-mcp.addons.headless/IHeadlessCapabilities

        (declared-capabilities [_]
          #{:cap/streaming :cap/multi-turn})))))
