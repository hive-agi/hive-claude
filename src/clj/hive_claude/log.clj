(ns hive-claude.log
  "Logging shim — delegates to timbre on JVM, stderr on babashka.

   Usage is identical to timbre: (log/info ...), (log/warn ...), etc.
   Auto-detection at load time: bb? property -> stderr, else try timbre.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private use-timbre?
  (and (nil? (System/getProperty "babashka.version"))
       (some? (try (requiring-resolve 'taoensso.timbre/info) (catch Exception _ nil)))))

(defn stderr-log [level args]
  (binding [*out* *err*]
    (println (str "[" level "]") (apply str (interpose " " args)))))

(defmacro info  [& args] (if use-timbre? `(taoensso.timbre/info  ~@args) `(stderr-log "INFO"  (list ~@args))))
(defmacro warn  [& args] (if use-timbre? `(taoensso.timbre/warn  ~@args) `(stderr-log "WARN"  (list ~@args))))
(defmacro error [& args] (if use-timbre? `(taoensso.timbre/error ~@args) `(stderr-log "ERROR" (list ~@args))))
(defmacro debug [& args] (if use-timbre? `(taoensso.timbre/debug ~@args) `(stderr-log "DEBUG" (list ~@args))))
