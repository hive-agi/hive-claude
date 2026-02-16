(ns hive-claude.util
  "Shared utilities for hive-claude. Avoids compile-time hive-mcp coupling.

   Provides a local `rescue` macro (identical to hive-mcp.dns.result/rescue)
   so SDK files can use it without a compile-time dependency on hive-mcp.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defmacro rescue
  "Try body. On exception return fallback.
   Local copy of hive-mcp.dns.result/rescue for zero-compile-time-coupling."
  [fallback & body]
  `(try ~@body
        (catch Exception e#
          (let [fb# ~fallback]
            (if (instance? clojure.lang.IObj fb#)
              (with-meta fb# {::error {:message (.getMessage e#)
                                       :form    ~(str (first body))}})
              fb#)))))

(defn try-resolve
  "Attempt to resolve a fully-qualified symbol. Returns var or nil."
  [sym]
  (try (requiring-resolve sym) (catch Exception _ nil)))
