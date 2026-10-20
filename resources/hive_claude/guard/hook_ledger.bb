;; Fail-open ledger append for the generated hive-guard hook.
;;
;; This text is INLINED into the dispatcher by hive-claude.guard.projection;
;; it is not loaded as a namespace. It assumes the script already required
;; clojure.java.io as `io` and clojure.string as `str`.
;;
;; WHY. The hook fails open in several places (unreadable payload, nREPL
;; unreachable, unreadable reply, a :guard/gap from the seam, its own crash).
;; Claude Code does not show a non-blocking hook's stderr to the model, so an
;; allow-because-unreachable used to leave no trace. Each one now appends a
;; :guard/unjudged entry to the same ledger the JVM guard writes, so
;; `guard status` can answer "the guard was down for N calls".
;;
;; TOTAL. Nothing here may throw or hold the tool call: a ledger that cannot be
;; written is a lost record, never a failed hook.

(def ledger-field-cap
  "Longest string kept per field. A reason can carry a remote error message."
  512)

(defn ledger-clip [x]
  (let [s (str x)]
    (if (> (count s) ledger-field-cap) (subs s 0 ledger-field-cap) s)))

(defn ledger-path
  "The ledger file. HIVE_GUARD_LEDGER_PATH wins; otherwise the default the
   JVM guard uses, so both sides append to one file without being told."
  [env-path home]
  (or (some-> env-path str/trim not-empty)
      (str home "/.config/hive-mcp/guard-ledger.ednl")))

(defn unjudged-record
  "The ledger entry for one allow-because-unreachable. :tool and :event come
   from the payload when it was readable, and are absent otherwise."
  [reason payload now-ms]
  (let [tool  (when (map? payload) (:tool_name payload))
        event (when (map? payload) (:hook_event_name payload))]
    (cond-> {:rule-id :guard/unjudged :reason (ledger-clip reason) :at now-ms}
      (string? tool)  (assoc :tool (ledger-clip tool))
      (string? event) (assoc :event (ledger-clip event)))))

(defn append-unjudged!
  "Append one record as a single EDN line to `path`. True when written, false
   when it could not be, and never throws."
  [path reason payload now-ms]
  (try
    (let [f (io/file path)]
      (io/make-parents f)
      (spit f (prn-str (unjudged-record reason payload now-ms)) :append true)
      true)
    (catch Throwable _ false)))

(defn record-unjudged!
  [reason payload]
  (try
    (append-unjudged! (ledger-path (System/getenv "HIVE_GUARD_LEDGER_PATH")
                                   (System/getProperty "user.home"))
                      reason payload (System/currentTimeMillis))
    (catch Throwable _ false)))
