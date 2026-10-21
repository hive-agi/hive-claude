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

(defn- vessel-symbols
  "Every symbol in the src forms of `file` that names a vessel namespace:
   hive-mcp.emacs.*, hive-emacs.*, hive-vim.*, hive-tmux.*, vterm-mcp.*.
   Quoted symbols count too: a soft resolve of a vessel is still a coupling."
  [^java.io.File file]
  (with-open [r (java.io.PushbackReader. (clojure.java.io/reader file))]
    (let [eof (Object.)]
      (->> (repeatedly #(read {:read-cond :allow :eof eof} r))
           (take-while #(not= eof %))
           (mapcat #(tree-seq coll? seq %))
           (filter symbol?)
           (map str)
           (filter #(re-find #"^(hive-mcp\.emacs|hive-emacs|hive-vim|hive-tmux|vterm-mcp)[./]" %))
           doall))))

(deftest hive-claude-names-no-vessel
  (testing "no src namespace names an editor or terminal vessel, even by soft resolve"
    (let [files (->> (file-seq (clojure.java.io/file "src"))
                     (filter #(re-find #"\.cljc?$" (.getName ^java.io.File %))))
          hits  (into {} (keep (fn [f] (when-let [s (seq (vessel-symbols f))]
                                          [(.getPath ^java.io.File f) (vec (distinct s))])))
                      files)]
      (is (seq files) "no sources were read, so an empty hit set would mean nothing")
      (is (= {} hits)))))

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
