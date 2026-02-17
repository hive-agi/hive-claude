(ns hive-claude.terminal
  "ITerminalAddon implementation for Claude Code terminal backend.

   Bridges hive-mcp's JVM-side ling management to the Emacs-side
   hive-claude elisp package (compiled from ClojureElisp). Implements
   ITerminalAddon by evaluating elisp expressions via the Emacs client.

   Terminal backend options (configured in Emacs):
   - claude-code-ide (default): Full WebSocket MCP integration
   - vterm: Native terminal, reliable input
   - eat: Pure elisp terminal (experimental)

   All hive-mcp dependencies resolved at runtime via requiring-resolve
   (zero compile-time coupling)."
  (:require [hive-claude.elisp :as elisp]
            [hive-claude.log :as log]
            [clojure.string :as str]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Runtime Resolution
;; =============================================================================

(defn- try-resolve
  "Attempt to resolve a fully-qualified symbol. Returns var or nil."
  [sym]
  (try (requiring-resolve sym) (catch Exception _ nil)))

(defn- eval-elisp!
  "Resolve and call hive-mcp.emacs.client/eval-elisp-with-timeout.
   Returns {:success bool :result str :error str}."
  [elisp-code timeout-ms]
  (if-let [eval-fn (try-resolve 'hive-mcp.emacs.client/eval-elisp-with-timeout)]
    (eval-fn elisp-code timeout-ms)
    (do (log/error "hive-mcp.emacs.client/eval-elisp-with-timeout not available")
        {:success false :error "Emacs client not on classpath"})))

(defn- eval-claude-elisp
  "Evaluate a hive-claude elisp expression and parse JSON result.
   Returns {:success true :result parsed} or {:success false :error str}."
  [elisp-str timeout-ms]
  (let [{:keys [success result error timed-out]} (eval-elisp! elisp-str timeout-ms)]
    (cond
      timed-out {:success false :error "timeout" :timed-out true}
      success   (let [parsed (try
                               (when (string? result)
                                 (if-let [parse-fn (try-resolve 'cheshire.core/parse-string)]
                                   (parse-fn result true)
                                   result))
                               (catch Exception _ result))]
                  {:success true :result (or parsed result)})
      :else     {:success false :error (str error)})))

(defn- escape
  "Escape a string for safe embedding in elisp."
  [s]
  (if (string? s) (elisp/escape-for-elisp s) ""))

;; =============================================================================
;; Spawn Context Helper
;; =============================================================================

(def ^:private ^:const max-context-chars
  "Max context size in chars. Claude CLI crashes when system prompt
   (context + presets + identity) exceeds ~120KB. Cap context at 80KB
   to leave room for preset and identity overhead."
  80000)

(defn- spawn-context-file
  "Write catchup context to temp file, returning file path or nil.
   Truncates context exceeding max-context-chars to prevent CLI crash."
  [cwd]
  (try
    (when-let [catchup-fn (try-resolve 'hive-mcp.tools.catchup/spawn-context)]
      (when-let [spawn-ctx (catchup-fn cwd)]
        (let [ctx (if (> (count spawn-ctx) max-context-chars)
                    (do (log/warn "Spawn context truncated"
                                  {:original-size (count spawn-ctx)
                                   :max max-context-chars})
                        (subs spawn-ctx 0 max-context-chars))
                    spawn-ctx)
              tmp (java.io.File/createTempFile "hive-claude-ctx-" ".md")]
          (spit tmp ctx)
          (.getAbsolutePath tmp))))
    (catch Exception _ nil)))

;; =============================================================================
;; ITerminalAddon Implementation
;; =============================================================================

(defn make-claude-terminal
  "Create an ITerminalAddon reify for Claude Code terminal backend.
   Returns nil if ITerminalAddon protocol is not on classpath."
  []
  (when (try-resolve 'hive-mcp.addons.terminal/ITerminalAddon)
    (reify
      hive-mcp.addons.terminal/ITerminalAddon

      (terminal-id [_] :claude)

      (terminal-spawn! [_ ctx opts]
        (let [{:keys [id cwd presets]} ctx
              {:keys [task]} opts
              ctx-file (when task (spawn-context-file cwd))
              elisp (format "(json-encode (hive-claude-bridge-api-spawn \"%s\" \"%s\" %s %s %s %s %s))"
                            (escape id)
                            (escape (or (:name opts) id))
                            (if (seq presets)
                              (format "'(%s)" (str/join " " (map #(format "\"%s\"" (escape %)) presets)))
                              "nil")
                            (if cwd (format "\"%s\"" (escape cwd)) "nil")
                            "1"   ;; depth
                            "nil" ;; parent-id (derived from env in elisp)
                            (if ctx-file (format "\"%s\"" (escape ctx-file)) "nil"))
              {:keys [success error]} (eval-claude-elisp elisp 10000)]
          (if success
            (do
              (log/info "Claude terminal spawned ling" {:id id :cwd cwd})
              id)
            (throw (ex-info "Claude terminal spawn failed"
                            {:id id :error error})))))

      (terminal-dispatch! [_ ctx task-opts]
        (let [{:keys [id]} ctx
              {:keys [task]} task-opts
              elisp (format "(json-encode (hive-claude-bridge-api-dispatch \"%s\" \"%s\"))"
                            (escape id)
                            (escape task))
              {:keys [success error]} (eval-claude-elisp elisp 5000)]
          (if success
            (do (log/info "Claude terminal dispatched task" {:id id})
                true)
            (throw (ex-info "Claude terminal dispatch failed"
                            {:ling-id id :error error})))))

      (terminal-status [_ ctx ds-status]
        (let [{:keys [id]} ctx
              elisp (format "(json-encode (hive-claude-bridge-api-status \"%s\"))"
                            (escape id))
              {:keys [success result]} (eval-claude-elisp elisp 3000)]
          (if success
            (let [elisp-status result]
              (merge (or ds-status {})
                     {:slave/id id
                      :elisp-alive? (get elisp-status :buffer-alive false)
                      :terminal-status (get elisp-status :status)}))
            ds-status)))

      (terminal-kill! [_ ctx]
        (let [{:keys [id]} ctx
              elisp (format "(json-encode (hive-claude-bridge-api-kill \"%s\"))"
                            (escape id))
              {:keys [success result error]} (eval-claude-elisp elisp 5000)]
          (if success
            (if (get result :killed)
              {:killed? true :id id}
              {:killed? false :id id :reason :kill-blocked})
            {:killed? false :id id :reason :elisp-error :error error})))

      (terminal-interrupt! [_ ctx]
        (let [{:keys [id]} ctx
              elisp (format "(json-encode (hive-claude-bridge-api-interrupt \"%s\"))"
                            (escape id))
              {:keys [success error]} (eval-claude-elisp elisp 3000)]
          (if success
            {:success? true :ling-id id}
            {:success? false :ling-id id :errors [(str "interrupt failed: " error)]}))))))
