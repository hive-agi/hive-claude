(ns hive-claude.init-test
  "Unit tests for hive-claude addon initialization.

   Tests the init-as-addon! lifecycle without requiring hive-mcp on classpath.
   Verifies graceful degradation when protocols are unavailable."
  (:require [clojure.test :refer [deftest is testing]]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(deftest init-without-hive-mcp-returns-empty
  (testing "init-as-addon! returns empty result when hive-mcp not on classpath"
    ;; When running standalone (no hive-mcp), init should degrade gracefully
    (let [init-fn (requiring-resolve 'hive-claude.init/init-as-addon!)
          result (init-fn)]
      (is (map? result))
      (is (contains? result :registered))
      (is (contains? result :total))
      ;; Without hive-mcp protocols, should register nothing
      (is (= 0 (:total result)))
      (is (empty? (:registered result))))))

(deftest get-addon-instance-nil-without-init
  (testing "get-addon-instance returns nil before initialization"
    (let [get-fn (requiring-resolve 'hive-claude.init/get-addon-instance)]
      (is (nil? (get-fn))))))

(deftest terminal-make-returns-nil-without-protocol
  (testing "make-claude-terminal returns nil when ITerminalAddon not on classpath"
    (let [make-fn (requiring-resolve 'hive-claude.terminal/make-claude-terminal)]
      ;; Without hive-mcp on classpath, should return nil gracefully
      (is (nil? (make-fn))))))
