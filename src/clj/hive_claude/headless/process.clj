(ns hive-claude.headless.process
  "Process management adapter for Claude Code ProcessBuilder-based headless lings.

   Thin adapter over hive-mcp.agent.headless — resolves all hive-mcp
   functions at runtime via requiring-resolve for zero compile-time coupling.
   Falls back gracefully when hive-mcp is not on classpath."
  (:require [hive-claude.util :refer [try-resolve rescue]]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Dynamic Resolution
;; =============================================================================

(defn- headless-fn
  "Resolve a function from hive-mcp.agent.headless at runtime."
  [fn-name]
  (try-resolve (symbol "hive-mcp.agent.headless" fn-name)))

;; =============================================================================
;; Public API (delegating to hive-mcp.agent.headless)
;; =============================================================================

(defn spawn-process!
  "Spawn a headless ling subprocess via ProcessBuilder.
   Delegates to hive-mcp.agent.headless/spawn-headless!."
  [ling-id opts]
  (if-let [spawn-fn (headless-fn "spawn-headless!")]
    (spawn-fn ling-id opts)
    (throw (ex-info "hive-mcp.agent.headless not available"
                    {:ling-id ling-id}))))

(defn dispatch-via-stdin!
  "Send a task to a headless ling via its stdin pipe.
   Delegates to hive-mcp.agent.headless/dispatch-via-stdin!."
  [ling-id message]
  (if-let [dispatch-fn (headless-fn "dispatch-via-stdin!")]
    (dispatch-fn ling-id message)
    (throw (ex-info "hive-mcp.agent.headless not available"
                    {:ling-id ling-id}))))

(defn kill-process!
  "Terminate a headless ling process.
   Delegates to hive-mcp.agent.headless/kill-headless!."
  ([ling-id] (kill-process! ling-id {}))
  ([ling-id opts]
   (if-let [kill-fn (headless-fn "kill-headless!")]
     (kill-fn ling-id opts)
     (throw (ex-info "hive-mcp.agent.headless not available"
                     {:ling-id ling-id})))))

(defn process-status
  "Get the status of a headless ling.
   Delegates to hive-mcp.agent.headless/headless-status."
  [ling-id]
  (when-let [status-fn (headless-fn "headless-status")]
    (status-fn ling-id)))

(defn get-stdout
  "Get stdout contents of a headless ling."
  ([ling-id] (get-stdout ling-id {}))
  ([ling-id opts]
   (when-let [f (headless-fn "get-stdout")]
     (f ling-id opts))))

(defn get-stderr
  "Get stderr contents of a headless ling."
  ([ling-id] (get-stderr ling-id {}))
  ([ling-id opts]
   (when-let [f (headless-fn "get-stderr")]
     (f ling-id opts))))

(defn get-stdout-since
  "Get stdout lines appended after a given timestamp."
  [ling-id since]
  (when-let [f (headless-fn "get-stdout-since")]
    (f ling-id since)))

(defn process?
  "Check if a ling-id corresponds to a headless process."
  [ling-id]
  (boolean (when-let [f (headless-fn "headless?")]
             (f ling-id))))

(defn available?
  "Check if the headless process module is available."
  []
  (boolean (headless-fn "spawn-headless!")))
