(ns hive-claude.init-test
  "Unit tests for hive-claude addon initialization.

   Tests the init-as-addon! lifecycle without requiring hive-mcp on classpath.
   Verifies graceful degradation when protocols are unavailable."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-claude.init :as init]
            [hive-addon.protocol :as addon]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(deftest init-without-hive-mcp-returns-empty
  (testing "host registration fails gracefully when its registry ports are absent"
    ;; The registry port names host symbols that do not resolve, which is
    ;; what a classpath without hive-mcp looks like to the pipeline. Nothing
    ;; is redefined and the host's real addon registry is never touched.
    (let [store  (atom nil)
          result (init/init-as-addon!
                  {:registry       {:register! 'hive-claude.absent-host.core/register-addon!
                                    :init!     'hive-claude.absent-host.core/init-addon!
                                    :addon-id  'hive-claude.absent-host.protocol/addon-id}
                   :instance-store store})]
      (is (= {:registered [] :total 0} result))
      (is (nil? @store)))))

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

(def ^:private stub-calls
  "Calls the stubbed host registry ports received, in order."
  (atom []))

(defn stub-register!
  "Host registry port stub: records the addon id it was asked to register."
  [addon]
  (swap! stub-calls conj [:register (addon/addon-id addon)])
  {:success? true})

(defn stub-init!
  "Host init port stub: records the id it was asked to initialize."
  [id]
  (swap! stub-calls conj [:init id])
  {:success? true})

(deftest init-with-host-registers-backends
  (testing "init-as-addon! registers against the host when available"
    ;; The host's registry is a port: stub fns stand in for
    ;; hive-mcp.addons.core, so the result does not depend on what earlier
    ;; tests left in the real addon registry.
    (reset! stub-calls [])
    (let [store  (atom nil)
          result (init/init-as-addon!
                  {:registry       {:register! 'hive-claude.init-test/stub-register!
                                    :init!     'hive-claude.init-test/stub-init!
                                    :addon-id  'hive-addon.protocol/addon-id}
                   :instance-store store})]
      (is (pos? (:total result)))
      (is (seq (:registered result)))
      (is (= [[:register "hive.claude"] [:init "hive.claude"]] @stub-calls))
      (is (= "hive.claude" (addon/addon-id @store))))))
