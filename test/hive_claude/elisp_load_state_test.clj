(ns hive-claude.elisp-load-state-test
  "Regression + trifecta for the elisp-loaded? session-fingerprint cache.

   Bug (task 20260401195228-18c8297d): the previous implementation cached
   `(atom {:loaded? true})` via `defonce`. The atom survived Emacs restarts
   because the JVM kept living, so after `M-x kill-emacs && emacs` the
   predicate still returned true and `ensure-elisp-loaded!` skipped reload.

   The fix fingerprints the cache with `(emacs-pid)`+`(emacs-init-time)` so
   a new Emacs process auto-invalidates the cache.

   Test strategy:
     * We never require a live Emacs — instead we `intern` a stub
       `hive-mcp.emacs.client/eval-elisp-with-timeout` that returns canned
       responses based on a mutable session state.
     * Unit tests verify pred is nil pre-load, t post-load, nil across a
       simulated restart.
     * Property test (test.check) forall randomly generated session
       fingerprints: after a real load under fp1, switching to fp2 must
       invalidate the cache.
     * Integration test (^:integration, env-gated on HIVE_CLAUDE_INTEGRATION)
       performs a real load-path inject + require cycle against a live
       Emacs provided by the caller."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

;; ---------------------------------------------------------------------------
;; Stub plumbing
;; ---------------------------------------------------------------------------

(def ^:private emacs-state
  "Simulated Emacs state driven by the tests.
   Keys:
     :fingerprint — string returned by the fingerprint eval (nil = 'Emacs down')
     :features    — set of feature symbols currently `(featurep ...)` true."
  (atom {:fingerprint nil
         :features    #{}}))

(defn- stub-eval
  "Drop-in replacement for hive-mcp.emacs.client/eval-elisp-with-timeout.
   Pattern-matches on the code string to decide what to return."
  [code _timeout]
  (let [{:keys [fingerprint features]} @emacs-state]
    (cond
      (nil? fingerprint)
      {:success false :error "Emacs unreachable"}

      ;; Fingerprint probe
      (re-find #"emacs-pid" code)
      {:success true :result fingerprint}

      ;; featurep probe
      (re-find #"featurep 'hive-claude-bridge" code)
      {:success true
       :result (if (contains? features 'hive-claude-bridge) "t" "nil")}

      ;; load-path injection + require (treat as success, "install" features)
      (re-find #"add-to-list 'load-path" code)
      {:success true :result "t"}

      (re-find #"require '" code)
      (do
        (swap! emacs-state update :features
               (fnil into #{}) '[hive-claude-config
                                 hive-claude-state
                                 hive-claude-bridge
                                 hive-claude-sync])
        {:success true :result "t"})

      (re-find #"hive-claude-sync-register-hooks-bang" code)
      {:success true :result "t"}

      :else
      {:success true :result "nil"})))

(defn- install-stub!
  "Intern the stub into hive-mcp.emacs.client so `requiring-resolve` in
   hive-claude.init picks it up. Idempotent."
  []
  (create-ns 'hive-mcp.emacs.client)
  (intern 'hive-mcp.emacs.client 'eval-elisp-with-timeout stub-eval))

(defn- uninstall-stub! []
  (ns-unmap 'hive-mcp.emacs.client 'eval-elisp-with-timeout))

(defn- reset-everything! []
  (reset! emacs-state {:fingerprint nil :features #{}})
  (let [reset-fn (requiring-resolve 'hive-claude.elisp-load-state/reset-elisp-load-state!)]
    (reset-fn)))

(use-fixtures :each
  (fn [f]
    (install-stub!)
    (reset-everything!)
    (try (f) (finally (reset-everything!) (uninstall-stub!)))))

(defn- sim-start-emacs! [fp]
  (reset! emacs-state {:fingerprint fp :features #{}}))

(defn- sim-kill-emacs! []
  (reset! emacs-state {:fingerprint nil :features #{}}))

;; ---------------------------------------------------------------------------
;; Unit: lifecycle pre-load / post-load / post-restart
;; ---------------------------------------------------------------------------

(deftest elisp-loaded?-false-before-load
  (testing "pred returns false when nothing has been loaded yet"
    (sim-start-emacs! "pid-1|1000.000")
    (let [pred (requiring-resolve 'hive-claude.elisp-load-state/elisp-loaded?)]
      (is (false? (pred))))))

(deftest elisp-loaded?-true-after-load
  (testing "after ensure-elisp-loaded! succeeds, pred returns true"
    (sim-start-emacs! "pid-1|1000.000")
    (let [ensure! (requiring-resolve 'hive-claude.elisp-load-state/ensure-elisp-loaded!)
          pred   (requiring-resolve 'hive-claude.elisp-load-state/elisp-loaded?)]
      (is (true? (ensure!)) "load should succeed under stub")
      (is (true? (pred)) "pred must see the cached fingerprint"))))

(deftest elisp-loaded?-invalidates-on-emacs-restart
  (testing "killing Emacs and starting a new one invalidates the cache"
    (sim-start-emacs! "pid-1|1000.000")
    (let [ensure! (requiring-resolve 'hive-claude.elisp-load-state/ensure-elisp-loaded!)
          pred   (requiring-resolve 'hive-claude.elisp-load-state/elisp-loaded?)]
      (ensure!)
      (is (true? (pred)))
      ;; Simulate M-x kill-emacs && emacs
      (sim-kill-emacs!)
      (sim-start-emacs! "pid-2|2000.000")
      (is (false? (pred))
          "Different fingerprint must invalidate cached :loaded? state"))))

(deftest elisp-loaded?-false-when-features-missing
  (testing "fingerprint matches but featurep returns nil => pred is false"
    (sim-start-emacs! "pid-1|1000.000")
    (let [ensure! (requiring-resolve 'hive-claude.elisp-load-state/ensure-elisp-loaded!)
          pred   (requiring-resolve 'hive-claude.elisp-load-state/elisp-loaded?)]
      (ensure!)
      (swap! emacs-state assoc :features #{}) ;; user did (unload-feature 'hive-claude-bridge)
      (is (false? (pred))))))

(deftest ensure-elisp-loaded!-reloads-after-restart
  (testing "ensure-elisp-loaded! reinstalls features after restart"
    (sim-start-emacs! "pid-1|1000.000")
    (let [ensure! (requiring-resolve 'hive-claude.elisp-load-state/ensure-elisp-loaded!)]
      (is (true? (ensure!)))
      (sim-kill-emacs!)
      (sim-start-emacs! "pid-2|2000.000")
      (is (empty? (:features @emacs-state)) "new Emacs has no features yet")
      (is (true? (ensure!)) "second call under new fp should reload")
      (is (contains? (:features @emacs-state) 'hive-claude-bridge)))))

;; ---------------------------------------------------------------------------
;; Property: forall (fp1, fp2) where fp1 != fp2, restart invalidates
;; ---------------------------------------------------------------------------

(def ^:private fp-gen
  (gen/fmap (fn [[pid t]] (format "pid-%d|%d.000" pid t))
            (gen/tuple gen/small-integer gen/small-integer)))

(defspec restart-invalidates-cache-property 50
  ;; After load under fp1, a fresh Emacs (features wiped, fp becomes fp2) must
  ;; make the predicate return false — whether because the fingerprint differs
  ;; OR because (featurep 'hive-claude-bridge) is nil. Both branches protect
  ;; against the original bug of stale :loaded? true.
  (prop/for-all [fp1 fp-gen
                 fp2 fp-gen]
    (reset-everything!)
    (sim-start-emacs! fp1)
    (let [ensure! (requiring-resolve 'hive-claude.elisp-load-state/ensure-elisp-loaded!)
          pred   (requiring-resolve 'hive-claude.elisp-load-state/elisp-loaded?)]
      (ensure!)
      (let [loaded-under-fp1? (pred)]
        ;; Simulate M-x kill-emacs — features wiped, pid gone
        (sim-kill-emacs!)
        ;; Start fresh Emacs with fp2 (features not yet loaded)
        (swap! emacs-state assoc :fingerprint fp2 :features #{})
        (let [loaded-under-fp2? (pred)]
          (and loaded-under-fp1?
               (not loaded-under-fp2?))))))) ;; restart always invalidates

;; ---------------------------------------------------------------------------
;; Integration: live Emacs load/unload cycle
;; Opt-in via HIVE_CLAUDE_INTEGRATION=1 AND a live Emacs reachable through
;; the real hive-mcp.emacs.client. We only *invoke* ensure-elisp-loaded!
;; once; a real restart can't be simulated in-process, so the integration
;; test focuses on the load half and trusts the unit tests for the restart
;; half.
;; ---------------------------------------------------------------------------

(deftest ^:integration live-load-cycle
  (when (= "1" (System/getenv "HIVE_CLAUDE_INTEGRATION"))
    (testing "against a live Emacs: ensure-elisp-loaded! succeeds"
      ;; Uninstall our stub so the real var is used
      (uninstall-stub!)
      (try
        (let [ensure! (requiring-resolve 'hive-claude.elisp-load-state/ensure-elisp-loaded!)
              pred   (requiring-resolve 'hive-claude.elisp-load-state/elisp-loaded?)]
          (is (true? (ensure!)))
          (is (true? (pred))))
        (finally (install-stub!))))))
