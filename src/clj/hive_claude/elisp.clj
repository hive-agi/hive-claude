(ns hive-claude.elisp
  "Elisp formatting helpers for Claude terminal backend.
   Inlined from hive-mcp tools/swarm/core to avoid compile-time coupling."
  (:require [clojure.string :as str]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(defn format-elisp-list
  "Format a Clojure sequence as elisp list string.
   Returns nil if items is empty/nil."
  [items format-item-fn]
  (when (seq items)
    (format "'(%s)" (str/join " " (map format-item-fn items)))))

(defn escape-for-elisp
  "Escape a string for safe embedding in an elisp string literal."
  [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")))
