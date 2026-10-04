(ns hive-claude.elisp-load-state
  "Session-fingerprinted cache of 'are hive-claude elisp features loaded in the
   live Emacs process?'.

   Bug history (task 20260401195228-18c8297d):
     The prior implementation kept a naked `(atom {:loaded? false})` behind a
     `defonce`. Because the atom lives in the JVM, it survived an Emacs
     kill/restart — the JVM remembered `:loaded? true` but the fresh Emacs had
     no features. `ensure-elisp-loaded!` consequently skipped the reload,
     leaving the user without bridge features.

   Fix direction:
     Fingerprint the cache with `(emacs-pid)|(emacs-init-time)`. A new Emacs
     process always produces a different fingerprint, so the cache
     self-invalidates without any hook or manual reset. We additionally
     verify `(featurep 'hive-claude-bridge)` on the live Emacs side to catch
     user-driven `unload-feature` between calls.

   Public surface:
     * `elisp-loaded?`         — predicate
     * `ensure-elisp-loaded!`  — idempotent loader
     * `reset-elisp-load-state!` — manual invalidator (for tests / hooks)

   This namespace has no compile-time dependency on hive-mcp — it resolves
   the Emacs client via `requiring-resolve` at call time, the same pattern
   used throughout hive-claude."
  (:require [hive-claude.log :as log]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-claude.util :as util]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private try-resolve
  "Attempt to resolve a fully-qualified symbol. Returns var or nil.
   Alias of hive-claude.util/try-resolve, this repo's single copy."
  util/try-resolve)

;; =============================================================================
;; Elisp Load-Path Injection
;; =============================================================================

(defn- resolve-elisp-dirs
  "Locate elisp directories on classpath. Returns vector of dirs to inject."
  []
  (let [dirs (atom [])]
    (when-let [res-url (io/resource "elisp/hive-claude-bridge.el")]
      (swap! dirs conj (-> (.getPath res-url)
                           (str/replace #"/hive-claude-bridge\.el$" ""))))
    (when-let [rt-url (io/resource "clojure-elisp/clojure-elisp-runtime.el")]
      (swap! dirs conj (-> (.getPath rt-url)
                           (str/replace #"/clojure-elisp-runtime\.el$" ""))))
    @dirs))

;; =============================================================================
;; Session-fingerprinted cache
;; =============================================================================

(defonce ^:private elisp-load-state
  (atom {:session-fp nil}))

(defn- current-session-fingerprint
  "Read Emacs's session fingerprint = (emacs-pid) + (emacs-init-time).
   Returns nil if eval-fn fails or returns unparseable data. A fresh Emacs
   always produces a different fingerprint than the previous session."
  [eval-fn]
  (try
    (let [{:keys [success result]}
          (eval-fn "(format \"%s|%s\" (emacs-pid) (format-time-string \"%s.%N\" (or (emacs-init-time) (current-time))))"
                   2000)]
      (when (and success (string? result) (seq result))
        (str/replace result #"^\"|\"$" "")))
    (catch Exception _ nil)))

(defn- loaded-in-this-session?
  "True iff our cached fingerprint matches the live Emacs session fingerprint."
  [eval-fn]
  (when-let [cached (:session-fp @elisp-load-state)]
    (when-let [live (current-session-fingerprint eval-fn)]
      (= cached live))))

(def ^:private elisp-features
  "Features loaded in dependency order: config → state → bridge → sync."
  ["hive-claude-config" "hive-claude-state" "hive-claude-bridge" "hive-claude-sync"])

(defn- load-elisp-into-emacs!
  "Inject load-path and require hive-claude features into Emacs.
   Returns true on success, false on failure."
  [eval-fn]
  (let [dirs (resolve-elisp-dirs)]
    (if-not (seq dirs)
      (do (log/warn "No hive-claude elisp dirs found on classpath")
          false)
      (do
        (log/debug "Injecting hive-claude elisp load-paths:" dirs)
        (let [lp-elisp (format "(progn %s t)"
                               (str/join " " (map #(format "(add-to-list 'load-path \"%s\")" %) dirs)))
              lp-result (eval-fn lp-elisp 5000)]
          (if-not (:success lp-result)
            (do (log/warn "Failed to inject hive-claude load-path:" (:error lp-result))
                false)
            (let [require-elisp (format "(progn %s t)"
                                        (str/join " "
                                                  (map #(format "(require '%s)" %) elisp-features)))
                  req-result (eval-fn require-elisp 5000)]
              (if (:success req-result)
                (do (log/info "hive-claude elisp loaded into Emacs" {:features elisp-features})
                    (let [hook-result (eval-fn "(hive-claude-sync-register-hooks-bang)" 3000)]
                      (if (:success hook-result)
                        (log/info "hive-claude sync hooks registered")
                        (log/warn "Failed to register sync hooks:" (:error hook-result))))
                    true)
                (do (log/warn "Failed to load hive-claude elisp:" (:error req-result))
                    false)))))))))

(defn- emacs-has-features?
  "Fast path: check if Emacs still has hive-claude-bridge loaded.
   Previously only `:success` was checked, which is truthy even when the
   feature is absent — producing false positives after Emacs restart
   (task 20260401195228-18c8297d)."
  [eval-fn]
  (try
    (let [{:keys [success result]}
          (eval-fn "(if (featurep 'hive-claude-bridge) \"t\" \"nil\")" 2000)]
      (boolean (and success
                    (string? result)
                    (re-find #"\bt\b" result))))
    (catch Exception _ false)))

(defn reset-elisp-load-state!
  "Invalidate the cached session fingerprint. Next `ensure-elisp-loaded!`
   call performs a full reload. Exposed for tests and Emacs restart hooks."
  []
  (reset! elisp-load-state {:session-fp nil}))

(defn elisp-loaded?
  "Predicate: are hive-claude elisp features loaded in the current Emacs
   session? Returns false when Emacs is unreachable, when the cached
   fingerprint is nil, or when the fingerprint no longer matches the
   live Emacs session (i.e. Emacs was restarted)."
  []
  (boolean
   (when-let [eval-fn (try-resolve 'hive-mcp.emacs.client/eval-elisp-with-timeout)]
     (and (loaded-in-this-session? eval-fn)
          (emacs-has-features? eval-fn)))))

(defn ensure-elisp-loaded!
  "Ensure hive-claude elisp features are loaded in Emacs.
   Reconnect-aware: detects Emacs restart via session fingerprint and
   reloads features. Idempotent — fast path skips reload when the
   fingerprint matches AND the feature is actually present in Emacs.
   Public so terminal.clj can call via requiring-resolve before bridge evals."
  []
  (when-let [eval-fn (try-resolve 'hive-mcp.emacs.client/eval-elisp-with-timeout)]
    (if (and (loaded-in-this-session? eval-fn)
             (emacs-has-features? eval-fn))
      true
      (do
        (when (:session-fp @elisp-load-state)
          (log/info "Emacs session changed or features missing — reloading hive-claude"))
        (reset-elisp-load-state!)
        (let [ok? (load-elisp-into-emacs! eval-fn)]
          (when ok?
            (when-let [fp (current-session-fingerprint eval-fn)]
              (reset! elisp-load-state {:session-fp fp})))
          ok?)))))
