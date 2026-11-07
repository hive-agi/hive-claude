;; hive-guard-trace: append ONE redacted session-trace line per Stop /
;; SubagentStart hook event, for hive-assay's probe corpus (plan H2-09).
;;
;; OPT-IN, OFF BY DEFAULT. Nothing is written unless BOTH hold:
;;   HIVE_GUARD_TRACE=1             the switch
;;   HIVE_ASSAY_TRACE_DIR=<dir>     an existing directory (never created here)
;; Any failure is swallowed: tracing must never change a hook's answer.
;;
;; Line shape = hive-assay.evo.probe-corpus/TraceLine:
;;   {:trace/id :trace/session :trace/event :trace/at :trace/task
;;    :trace/outcome :trace/reason :trace/steps [{:tool :input :output :is-error}]}
;; written to <dir>/<session>.edn, one EDN map per line.
;;
;; Redaction on WRITE (hive-assay redacts again on READ): secret-shaped text,
;; values under secret-named keys, and every string is capped. Inputs and
;; outputs are heads only (head-chars), never whole tool results.
;;
;; Outcome: a hook cannot see a verdict. :fail only when the session's last
;; recorded step errored (an unresolved tool error); otherwise :unknown, which
;; hive-assay never seeds. HIVE_GUARD_TRACE_OUTCOME=pass|fail overrides it
;; (for a wrapper that knows the verdict).
;;
;; The detectors mirror hive-assay.evo.probe-corpus/secret-detectors; keep the
;; two in step.

(ns hive-guard-trace
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(def head-chars 300)
(def max-steps 40)
(def secret-marker "<redacted:secret>")
(def ^:private not-marker "(?!<redacted)")

(def secret-detectors
  [;; pattern-exempt: PEM private-key block
   [#"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----" secret-marker]
   ;; pattern-exempt: user:password@ in a URL
   [#"(?i)\b([a-z][a-z0-9+.-]*://)[^/\s:@<]+:[^/\s@]+@" (str "$1" secret-marker "@")]
   ;; pattern-exempt: Authorization header value
   [(re-pattern (str "(?i)\\b((?:proxy-)?authorization)([\"']?\\s*[:=]\\s*[\"']?)" not-marker
                     "(?:[A-Za-z-]+\\s+)?[^\\s\"',}]+"))
    (str "$1$2" secret-marker)]
   ;; pattern-exempt: bearer token
   [#"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}" secret-marker]
   ;; pattern-exempt: HTTP basic credentials
   [#"\bBasic\s+[A-Za-z0-9+/]{12,}={0,2}" secret-marker]
   ;; pattern-exempt: vendor key prefixes
   [#"\b(?:sk|pk|rk)-[A-Za-z0-9_-]{16,}|\b(?:ghp|gho|ghu|ghs|ghr|github_pat)_[A-Za-z0-9_]{16,}|\bglpat-[A-Za-z0-9_-]{16,}|\bxox[abposr]-[A-Za-z0-9-]{10,}|\bAKIA[0-9A-Z]{16}\b|\bAIza[0-9A-Za-z_-]{30,}"
    secret-marker]
   ;; pattern-exempt: JWT
   [#"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}" secret-marker]
   ;; pattern-exempt: key=value where the key names a secret
   [(re-pattern (str "(?i)\\b([A-Za-z0-9_-]*(?:api[_-]?key|secret|token|passw(?:or)?d|pwd|private[_-]?key"
                     "|access[_-]?key|credential|cookie|session[_-]?id)s?)([\"']?\\s*[:=]\\s*[\"']?)"
                     not-marker "[^\\s\"',}]+"))
    (str "$1$2" secret-marker)]
   ;; pattern-exempt: email
   [#"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}" secret-marker]
   ;; pattern-exempt: long hex run
   [#"\b[0-9a-fA-F]{32,}\b" secret-marker]])

;; pattern-exempt: map keys naming a secret
(def ^:private secret-key-re
  #"(?i)(api[_-]?key|secret|token|passw(or)?d|pwd|private[_-]?key|access[_-]?key|credential|cookie|authorization|session[_-]?id)s?$")

(defn redact-secrets [s]
  (reduce (fn [acc [re rep]] (str/replace acc re rep)) (str s) secret-detectors))

(defn- head [s] (let [s (str s)] (if (> (count s) head-chars) (subs s 0 head-chars) s)))

(defn redact [x]
  (walk/postwalk
   (fn [v]
     (cond
       (map-entry? v) (let [[k val] v]
                        (if (and (or (string? k) (keyword? k))
                                 (re-find secret-key-re (name k))
                                 (some? val) (not (coll? val)))
                          (clojure.lang.MapEntry. k secret-marker)
                          v))
       (string? v)    (head (redact-secrets v))
       :else          v))
   x))

(defn enabled? [env]
  (and (= "1" (get env "HIVE_GUARD_TRACE"))
       (let [d (get env "HIVE_ASSAY_TRACE_DIR")]
         (and (not (str/blank? d)) (.isDirectory (io/file d))))))

(defn- transcript-steps
  "Tool calls of a Claude Code transcript JSONL: tool_use blocks joined to
   their tool_result by id. Last max-steps only."
  [path]
  (when (and path (.isFile (io/file path)))
    (let [rows    (keep #(try (json/parse-string % true) (catch Exception _ nil))
                        (str/split-lines (slurp path)))
          blocks  (for [r rows
                        :let [c (get-in r [:message :content])]
                        :when (sequential? c)
                        b c]
                    b)
          results (into {} (for [b blocks :when (= "tool_result" (:type b))]
                             [(:tool_use_id b) b]))]
      (->> (for [b blocks :when (= "tool_use" (:type b))
                 :let [res (results (:id b))
                       out (:content res)]]
             {:tool     (str (:name b))
              :input    (pr-str (:input b))
              :output   (if (string? out) out (pr-str out))
              :is-error (boolean (:is_error res))})
           (take-last max-steps)
           vec))))

(defn trace-line
  "The redacted TraceLine for one hook payload (string or keyword keys), or
   nil for any event other than Stop / SubagentStart."
  [payload env now]
  (let [payload (update-keys payload keyword)
        event   (case (:hook_event_name payload)
                  "Stop" :stop
                  "SubagentStart" :subagent-start
                  nil)]
    (when event
      (let [steps   (or (transcript-steps (:transcript_path payload)) [])
            forced  (get env "HIVE_GUARD_TRACE_OUTCOME")
            outcome (cond
                      (#{"pass" "fail"} forced)         (keyword forced)
                      (:is-error (peek steps))          :fail
                      :else                             :unknown)
            session (str (or (:session_id payload) "unknown"))
            task    (or (get env "HIVE_GUARD_TRACE_TASK")
                        (some-> (:cwd payload) io/file .getName)
                        "session")]
        (redact
         {:trace/id      (str session "-" (name event) "-" now)
          :trace/session session
          :trace/event   event
          :trace/at      (str (java.time.Instant/ofEpochMilli now))
          :trace/task    task
          :trace/outcome outcome
          :trace/steps   steps})))))

(defn- safe-file-name [s]
  ;; pattern-exempt: keep a session id usable as a file name
  (str/replace (str s) #"[^A-Za-z0-9._-]" "_"))

(defn append!
  "Append the trace line for `payload` when tracing is enabled. Never throws;
   answers the written line or nil."
  ([payload] (append! payload (into {} (System/getenv)) (System/currentTimeMillis)))
  ([payload env now]
   (try
     (when (enabled? env)
       (when-let [line (trace-line payload env now)]
         (let [f (io/file (get env "HIVE_ASSAY_TRACE_DIR")
                          (str (safe-file-name (:trace/session line)) ".edn"))]
           (spit f (str (pr-str line) "\n") :append true)
           line)))
     (catch Exception _ nil))))
