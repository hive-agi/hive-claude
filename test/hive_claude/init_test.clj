(ns hive-claude.init-test
  "Unit tests for hive-claude addon initialization.

   Tests the init-as-addon! lifecycle without requiring hive-mcp on classpath.
   Verifies graceful degradation when protocols are unavailable."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-claude.init :as init]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(deftest init-without-hive-mcp-returns-empty
  (testing "host registration fails gracefully when its registry ports are absent"
    (with-redefs [hive-claude.init/addon-instance (atom nil)]
      (let [registry (ns-resolve 'hive-claude.init 'dep-registry)
            resolver (ns-resolve 'hive-claude.init 'try-resolve)
            original @resolver]
        (with-redefs-fn {resolver (fn [sym]
                                    (when-not (= "hive-mcp" (namespace sym))
                                      (original sym)))}
          (fn []
            (let [result (hive-claude.init/init-as-addon!)]
              (is (= {:registered [] :total 0} result))
              (is (nil? (hive-claude.init/get-addon-instance))))))))))

(deftest get-addon-instance-nil-without-init
  (testing "get-addon-instance returns nil before initialization"
    (with-redefs [hive-claude.init/addon-instance (atom nil)]
      (is (nil? (hive-claude.init/get-addon-instance))))) )

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

(deftest init-with-host-registers-backends
  (testing "init-as-addon! registers against the host when available"
    (with-redefs [hive-claude.init/addon-instance (atom nil)]
      (let [result (hive-claude.init/init-as-addon!)]
        (is (pos? (:total result)))
        (is (seq (:registered result)))))))
