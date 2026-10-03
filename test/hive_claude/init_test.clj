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

(deftest terminal-make-reifies-the-hive-addon-contract
  (testing "make-claude-terminal reifies hive-addon.terminal/ITerminalAddon without hive-mcp"
    ;; Since f0d2057 the terminal implements the contract lib's protocol, which
    ;; is a declared dep, so construction no longer depends on the host.
    (let [make-fn  (requiring-resolve 'hive-claude.terminal/make-claude-terminal)
          iface    @(requiring-resolve 'hive-addon.terminal/ITerminalAddon)
          id-fn    (requiring-resolve 'hive-addon.terminal/terminal-id)
          terminal (make-fn)]
      (is (satisfies? iface terminal))
      (is (= :claude (id-fn terminal))))))
