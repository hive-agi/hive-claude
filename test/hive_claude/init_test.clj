(ns hive-claude.init-test
  "Unit tests for hive-claude addon initialization.

   The suite runs WITH hive-mcp (the HOST) on the test classpath, so the
   graceful-degradation tests below do not rely on the host being absent:
   they SIMULATE host absence by rebinding the per-namespace resolver alias
   vars (hive-claude.init/try-resolve and hive-claude.terminal/try-resolve)
   to (constantly nil), which is the guard both make-addon and
   make-claude-terminal consult. A with-redefs on
   #'hive-claude.util/try-resolve would NOT reach them: both callers alias it
   by VALUE at load time, so the alias vars themselves must be redefined."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-claude.init]
            [hive-claude.terminal]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private host-absent-bindings
  "Var -> replacement map for with-redefs-fn: rebind the resolver alias vars
   so every 'is the host present?' guard sees nil. The vars are private, so
   they are looked up with ns-resolve (which tolerates privacy) instead of a
   var reader, which does not."
  {(ns-resolve 'hive-claude.init 'try-resolve)     (constantly nil)
   (ns-resolve 'hive-claude.terminal 'try-resolve) (constantly nil)})

(defmacro with-host-absent
  "Run `body` with the per-namespace resolver alias vars rebound to nil, so
   the guards in hive-claude.init/make-addon and
   hive-claude.terminal/make-claude-terminal see the host as ABSENT. The vars
   are private and alias hive-claude.util/try-resolve by VALUE at load time,
   so a with-redefs on #'hive-claude.util/try-resolve would not reach them."
  [& body]
  `(with-redefs-fn host-absent-bindings (fn [] ~@body)))

(deftest init-without-hive-mcp-returns-empty
  (testing "init-as-addon! returns empty result when the host appears absent"
    ;; Simulated host absence: the IAddon guard must fail and nothing may be
    ;; registered or stored.
    (with-host-absent
      ;; Bind a fresh instance atom so the assertion does not depend on test
      ;; order: a degraded init must never store an addon instance.
      (with-redefs [hive-claude.init/addon-instance (atom nil)]
        (let [init-fn (requiring-resolve 'hive-claude.init/init-as-addon!)
              result  (init-fn)]
          (is (map? result))
          (is (contains? result :registered))
          (is (contains? result :total))
          ;; Without the host's protocols, should register nothing
          (is (= 0 (:total result)))
          (is (empty? (:registered result)))
          (is (nil? ((requiring-resolve 'hive-claude.init/get-addon-instance)))))))))

(deftest get-addon-instance-nil-without-init
  (testing "get-addon-instance returns nil before initialization"
    (with-redefs [hive-claude.init/addon-instance (atom nil)]
      (is (nil? ((requiring-resolve 'hive-claude.init/get-addon-instance)))))))

(deftest terminal-make-returns-nil-without-protocol
  (testing "make-claude-terminal returns nil when ITerminalAddon is unavailable"
    ;; Simulated host absence: the guard on ITerminalAddon must see nil and
    ;; the constructor must degrade to nil.
    (with-host-absent
      (is (nil? ((requiring-resolve 'hive-claude.terminal/make-claude-terminal)))))))

(deftest make-claude-terminal-with-host-returns-addon
  (testing "make-claude-terminal returns an ITerminalAddon reify with the host present"
    (let [addon ((requiring-resolve 'hive-claude.terminal/make-claude-terminal))]
      (is (some? addon))
      (is (= :claude (.terminal-id addon))))))

(deftest init-with-host-registers-backends
  (testing "init-as-addon! registers the backends with the host present"
    (let [result ((requiring-resolve 'hive-claude.init/init-as-addon!))]
      (is (pos? (:total result)))
      (is (seq (:registered result))))))
