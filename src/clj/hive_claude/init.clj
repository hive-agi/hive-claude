(ns hive-claude.init
  "IAddon implementation for hive-claude — Claude Code terminal + headless backends.

   Follows the vterm-mcp exemplar: reify + nil-railway pipeline.
   Zero compile-time hive-mcp dependencies — all resolved via requiring-resolve.

   Registers BOTH:
   1. :claude terminal backend (existing, via terminal-registry)
   2. :claude-sdk headless backend (NEW, via headless-registry, when SDK available)
   3. :claude-process headless backend (NEW, via headless-registry, ProcessBuilder)

   Elisp loading:
   On initialize!, injects resources/elisp/ into Emacs load-path and requires
   hive-claude-config, hive-claude-state, hive-claude-bridge (in dependency order).
   Follows the lsp-mcp exemplar for load-path injection.

   Usage:
     ;; Via addon system (auto-discovered from META-INF manifest):
     (init-as-addon!)"
  (:require [hive-claude.terminal :as terminal]
            [hive-claude.log :as log]
            [clojure.java.io :as io]
            [clojure.string :as str]))

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
;; Elisp Load-Path Injection (lsp-mcp exemplar)
;; =============================================================================
;; TODO: Extract this pattern into clojure-elisp runtime as a reusable macro
;; e.g. (cljel/ensure-elisp-loaded! {:features [...] :marker-resource "..."})

(defn- resolve-elisp-dirs
  "Locate elisp directories on classpath. Returns vector of dirs to inject.
   Finds hive-claude's own elisp/ and the clojure-elisp runtime."
  []
  (let [dirs (atom [])]
    ;; hive-claude compiled elisp
    (when-let [res-url (io/resource "elisp/hive-claude-bridge.el")]
      (swap! dirs conj (-> (.getPath res-url)
                           (str/replace #"/hive-claude-bridge\.el$" ""))))
    ;; clojure-elisp runtime (required by compiled .el files)
    (when-let [rt-url (io/resource "clojure-elisp/clojure-elisp-runtime.el")]
      (swap! dirs conj (-> (.getPath rt-url)
                           (str/replace #"/clojure-elisp-runtime\.el$" ""))))
    @dirs))

(defonce ^:private elisp-loaded?
  (delay
    (when-let [eval-fn (try-resolve 'hive-mcp.emacs.client/eval-elisp-with-timeout)]
      (let [dirs (resolve-elisp-dirs)]
        (when (seq dirs)
          (log/debug "Injecting hive-claude elisp load-paths:" dirs)
          ;; Add all dirs to load-path
          (let [lp-elisp (format "(progn %s t)"
                                 (str/join " " (map #(format "(add-to-list 'load-path \"%s\")" %) dirs)))
                lp-result (eval-fn lp-elisp 5000)]
            (if-not (:success lp-result)
              (do (log/warn "Failed to inject hive-claude load-path:" (:error lp-result))
                  false)
              ;; Require features in dependency order
              (let [features ["hive-claude-config" "hive-claude-state" "hive-claude-bridge"]
                    require-elisp (format "(progn %s t)"
                                          (str/join " "
                                                    (map #(format "(require '%s)" %) features)))
                    req-result (eval-fn require-elisp 5000)]
                (if (:success req-result)
                  (do (log/info "hive-claude elisp loaded into Emacs" {:features features})
                      true)
                  (do (log/warn "Failed to load hive-claude elisp:" (:error req-result))
                      false))))))))))

(defn- ensure-elisp-loaded!
  "Ensure hive-claude elisp features are loaded in Emacs. Idempotent."
  []
  @elisp-loaded?)

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

              ;; 2. Headless SDK backend (when Python SDK available)
              (when-let [reg-fn (try-resolve 'hive-mcp.agent.ling.headless-registry/register-headless!)]
                (try
                  (require 'hive-claude.headless.sdk-backend)
                  (when-let [make-fn (try-resolve 'hive-claude.headless.sdk-backend/make-claude-sdk-backend)]
                    (when-let [sdk-backend (make-fn)]
                      (let [result (reg-fn :claude-sdk sdk-backend)]
                        (when (:registered? result)
                          (swap! registered-ids conj :claude-sdk)
                          (log/info "hive-claude: :claude-sdk headless registered")))))
                  (catch Exception e
                    (log/debug "SDK backend not available" {:error (ex-message e)})))

                ;; 3. Headless Process backend (ProcessBuilder)
                (try
                  (require 'hive-claude.headless.process-backend)
                  (when-let [make-fn (try-resolve 'hive-claude.headless.process-backend/make-claude-process-backend)]
                    (when-let [proc-backend (make-fn)]
                      (let [result (reg-fn :claude-process proc-backend)]
                        (when (:registered? result)
                          (swap! registered-ids conj :claude-process)
                          (log/info "hive-claude: :claude-process headless registered")))))
                  (catch Exception e
                    (log/debug "Process backend not available" {:error (ex-message e)}))))

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
            ;; Deregister terminal
            (when-let [dereg-fn (try-resolve 'hive-mcp.agent.ling.terminal-registry/deregister-terminal!)]
              (dereg-fn :claude))
            ;; Deregister headless backends
            (when-let [dereg-fn (try-resolve 'hive-mcp.agent.ling.headless-registry/deregister-headless!)]
              (doseq [id (filter #{:claude-sdk :claude-process} (:registered-ids @state))]
                (dereg-fn id)))
            (reset! state {:initialized? false})
            (log/info "hive-claude addon shut down" {:deregistered (:registered-ids @state)}))
          nil)

        (tools [_] [])

        (schema-extensions [_] {})

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

(defn get-addon-instance
  "Return the current IAddon instance, or nil."
  []
  @addon-instance)
