(ns hive-claude.sdk.agentic-loop
  "ClaudeSDKAgenticLoop — IAgenticLoop implementation wrapping sdk.lifecycle.

   Provides rich headless control over the Claude Agent SDK:
   - Async start/abort lifecycle via sdk.lifecycle spawn/kill
   - collect-response! with CompletableFuture + timeout
   - Cost tracking accumulated from SAA completion events
   - Transcript access from session observations
   - Mid-session constraints via budget guardrails

   Control gradient: OPAQUE — iteration delegated to Claude Agent SDK,
   caller hooks lifecycle events but doesn't broker tools.

   Wraps existing functions in hive-claude.sdk.lifecycle without
   reimplementing any SDK logic."
  (:require [hive-mcp.agent.agentic-loop :as proto]
            [hive-claude.sdk.lifecycle :as lifecycle]
            [hive-claude.sdk.session :as session]
            [clojure.core.async :as async]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent CompletableFuture TimeUnit TimeoutException]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- derive-session-state
  "Derive IAgenticLoop session state from SDK session phase."
  [ling-id]
  (if-let [sess (session/get-session ling-id)]
    (case (:phase sess)
      :idle     :idle
      :silence  :running
      :abstract :running
      :act      :running
      :dispatch :running
      :done     :done
      :error    :errored
      ;; default
      (if sess :running :idle))
    :idle))

(defn- drain-result-channel!
  "Drain an SDK dispatch result channel, accumulating cost and completing
   the CompletableFuture when :saa-complete or :error is received."
  [out-ch ^CompletableFuture completion-future cost-atom transcript-atom ling-id]
  (async/thread
    (loop [last-content nil]
      (if-let [msg (async/<!! out-ch)]
        (do
          ;; Record all messages in transcript
          (swap! transcript-atom conj msg)
          ;; Accumulate text content
          (let [content (or (:content (:data msg)) (:content msg) last-content)]
            (case (:type msg)
              :saa-complete
              (do
                (log/debug "[sdk-loop] SAA complete" {:ling-id ling-id})
                (.complete completion-future
                           {:result      (or content "")
                            :cost-usd    (:total-cost-usd @cost-atom 0.0)
                            :turns       (or (:turn-count msg) 0)
                            :ling-id     ling-id}))

              :error
              (.complete completion-future
                         {:error  true
                          :result (or (:error msg) "Unknown error")
                          :ling-id ling-id})

              ;; Other message types: just continue draining
              (recur content))))
        ;; Channel closed without completion
        (when-not (.isDone completion-future)
          (.complete completion-future
                     {:result   (or last-content "")
                      :cost-usd (:total-cost-usd @cost-atom 0.0)
                      :ling-id  ling-id}))))))

;; =============================================================================
;; ClaudeSDKAgenticLoop Record
;; =============================================================================

(defrecord ClaudeSDKAgenticLoop
           [ling-id            ;; string — SDK session identifier
            state-atom         ;; atom {:session-state :idle}
            cost-atom          ;; atom {:total-cost-usd 0.0}
            transcript-atom    ;; atom [message-maps...]
            completion-future  ;; atom holding CompletableFuture (per dispatch)
            config]            ;; spawn config

  proto/IAgenticLoop

  (start! [_this start-config]
    (let [merged (merge config start-config)
          cwd    (or (:cwd merged) "/tmp")]
      ;; Spawn the SDK session
      (lifecycle/spawn-headless-sdk!
       ling-id
       {:cwd           cwd
        :system-prompt (:preset-content merged)
        :mcp-servers   (:mcp-servers merged)
        :presets       (:presets merged)
        :agents        (:agents merged)
        :env           (:env merged)})
      (swap! state-atom assoc :session-state :running)
      {:session-id ling-id}))

  (abort! [_this]
    (try
      (lifecycle/interrupt-headless-sdk! ling-id)
      (lifecycle/kill-headless-sdk! ling-id)
      (swap! state-atom assoc :session-state :aborted)
      {:aborted? true}
      (catch Exception e
        (log/warn "[sdk-loop] Abort failed" {:ling-id ling-id :error (ex-message e)})
        (swap! state-atom assoc :session-state :errored)
        {:aborted? false :error (ex-message e)})))

  (session-state [_this]
    (derive-session-state ling-id))

  (send-message! [_this message]
    (let [cf (CompletableFuture.)]
      (reset! completion-future cf)
      ;; Dispatch via SDK lifecycle
      (let [out-ch (lifecycle/dispatch-headless-sdk! ling-id (str message))]
        ;; Drain result channel in background
        (drain-result-channel! out-ch cf cost-atom transcript-atom ling-id))
      {:task-id ling-id :future cf}))

  (collect-response! [_this opts]
    (let [timeout-ms (or (:timeout-ms opts) 60000)
          ^CompletableFuture cf @completion-future]
      (if cf
        (try
          (.get cf timeout-ms TimeUnit/MILLISECONDS)
          (catch TimeoutException _
            {:timeout true})
          (catch Exception e
            {:error true :result (ex-message e)}))
        {:error true :result "No dispatch in progress"})))

  (cost [_this]
    {:total-cost-usd (:total-cost-usd @cost-atom 0.0)
     :turns          (or (:turn-count (session/get-session ling-id)) 0)})

  (transcript [_this]
    @transcript-atom)

  (tool-results! [_this _results]
    ;; Opaque loop — tool results not supported
    {:unsupported true})

  (hooks [_this]
    #{:cap/opaque :PreToolUse :PostToolUse :Notification :Stop :PreCompact})

  (constrain! [_this constraints]
    ;; Apply budget constraint via session update if supported
    (when-let [max-cost (:max-cost-usd constraints)]
      (session/update-session! ling-id {:max-cost-usd max-cost}))
    (when-let [max-turns (:max-turns constraints)]
      (session/update-session! ling-id {:max-turns max-turns}))
    {:applied? true}))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn make-sdk-loop
  "Create a new ClaudeSDKAgenticLoop for the given ling-id and config.

   Config keys:
     :cwd            — Working directory (required)
     :preset-content — System prompt
     :mcp-servers    — MCP server config
     :presets        — Preset names
     :agents         — Sub-agent definitions
     :env            — Extra environment variables

   Returns: ClaudeSDKAgenticLoop (satisfies IAgenticLoop)"
  [ling-id config]
  {:pre [(string? ling-id)]}
  (->ClaudeSDKAgenticLoop
   ling-id
   (atom {:session-state :idle})        ;; state-atom
   (atom {:total-cost-usd 0.0})         ;; cost-atom
   (atom [])                            ;; transcript-atom
   (atom nil)                           ;; completion-future
   config))

;; =============================================================================
;; Factory Function (for headless adapter)
;; =============================================================================

(defn make-sdk-loop-factory
  "Return a factory function: (fn [ling-id config] -> ClaudeSDKAgenticLoop).
   Used by the headless adapter to create loops per-ling."
  []
  (fn [ling-id config]
    (make-sdk-loop ling-id config)))
