(ns hive-claude.guard.projection
  "ClaudeProjection — the `IGuardProjection` for Claude Code's hook system.

   `harness-id` is :claude-code.

   RAW SHAPE. A parsed Claude Code hook payload, keywordized:

     {:hook_event_name \"PreToolUse\"   the moment; maps onto :guard/phase
      :tool_name       string          present at the tool phases
      :tool_input      map             the tool's arguments
      :tool_response   any             :post-tool only
      :prompt          string          :prompt-submit only
      :source          string          :session-start trigger
      :agent_type      string          :subagent-start only
      :session_id      string
      :cwd             string}

   decode-event
     Returns a `:guard/harness :claude-code` GuardEvent, or nil for a hook event
     this projection does not map. Throws ex-info {:error
     :guard/undecodable-event} when the payload IS a mapped moment but cannot be
     read.

   encode-decision
     Claude Code's `hookSpecificOutput` map.

     A deny is the only verdict that spends the `permissionDecision` channel.
     An allow encodes to `{}` and a warn to `additionalContext` ALONE, with no
     `permissionDecision` — writing \"allow\" there would auto-approve a call the
     user's own permission rules were going to be asked about, so the guard
     would be GRANTING permission while reporting that it merely allowed. The
     guard's job is to refuse, never to permit.

   render-config
     The dispatcher script and the settings.json hook block, as content. Writes
     nothing.

     The generated hook carries NO RULES. It is transport: it forwards the raw
     payload to the live `:guard/decide` seam and writes back what that answers.
     The rule set shapes only WHICH hook events are registered — a rule set with
     no :stop rule registers no Stop hook — and the provenance header. That is
     the whole point: one evaluator, so an edit to the memory entry that states
     a rule changes what fires without anything being regenerated."
  (:require [clojure.string :as str]
            [hive-spi.guard.event :as ge]
            [hive-spi.guard.ports :as gp]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def harness
  "The `:guard/harness` this projection stamps, and the id it registers under."
  :claude-code)

(def event->phase
  "Claude Code hook event names, mapped onto guard phases.

   An event absent from this map is one the guard does not judge; `decode-event`
   answers nil for it rather than guessing a phase."
  {"PreToolUse"       :pre-tool
   "PostToolUse"      :post-tool
   "SessionStart"     :session-start
   "SubagentStart"    :subagent-start
   "UserPromptSubmit" :prompt-submit
   "Stop"             :stop})

(def phase->event
  "The inverse of `event->phase`, for rendering the hook registrations."
  (into {} (map (fn [[e p]] [p e])) event->phase))

(def ^:private trigger-keys
  "SessionStart `source` values, as phase triggers."
  {"startup" :startup "resume" :resume "clear" :clear "compact" :compact})

;;; ===========================================================================
;;; Decode — Claude Code payload -> GuardEvent
;;; ===========================================================================

(defn- optional-entries
  "The common GuardEvent entries `raw` actually carries."
  [{:keys [session_id cwd]}]
  (cond-> {}
    (not (str/blank? session_id)) (assoc :session/id session_id)
    (not (str/blank? cwd))        (assoc :cwd cwd)))

(defn- ->depth
  "A ling depth from the hook's string (or number), or nil."
  [v]
  (cond
    (integer? v) (long v)
    (string? v)  (try (Long/parseLong (str/trim v)) (catch Exception _ nil))
    :else        nil))

(defn- ling-entries
  "The ling identity the dispatcher stamped under `:hive_ling`, read from the
   hook process's environment. Claude Code's own payload carries none, and a
   headless ling IS a `claude` process behind this same hook, so without these
   a ling's call is indistinguishable from the operator's.

     :slave_id   CLAUDE_SWARM_SLAVE_ID  -> :agent/id
     :depth      HIVE_LING_DEPTH        -> :ling/depth (long)
     :credential HIVE_AGENT_CREDENTIAL present -> :ling/credential? true

   Only the credential's PRESENCE crosses: its value never leaves the hook.
   Anything malformed is dropped, never guessed. Pure."
  [{:keys [hive_ling]}]
  (if-not (map? hive_ling)
    {}
    (let [{:keys [slave_id depth credential]} hive_ling
          d (->depth depth)]
      (cond-> {}
        (and (string? slave_id) (not (str/blank? slave_id))) (assoc :agent/id slave_id)
        d                   (assoc :ling/depth d)
        (true? credential)  (assoc :ling/credential? true)))))

(defn- phase-entries
  "The phase-specific GuardEvent entries for `phase` out of `raw`."
  [phase {:keys [tool_name tool_input tool_response prompt source agent_type]}]
  (case phase
    :pre-tool       (cond-> {:tool/name tool_name}
                      (map? tool_input) (assoc :tool/input tool_input))
    :post-tool      (cond-> {:tool/name tool_name}
                      (map? tool_input)     (assoc :tool/input tool_input)
                      (some? tool_response) (assoc :tool/result tool_response))
    :session-start  (cond-> {}
                      (trigger-keys source) (assoc :session/trigger (trigger-keys source)))
    :subagent-start (cond-> {}
                      (string? agent_type) (assoc :agent/type agent_type))
    :prompt-submit  {:prompt (or prompt "")}
    :stop           {}))

(defn ->event
  "Assemble the GuardEvent `raw` describes, or nil when it describes none.
   The decode half, exposed for a caller that already holds the parsed payload.
   A `:hive_ling` map the dispatcher stamped becomes the ling identity
   (`ling-entries`)."
  [raw]
  (when (map? raw)
    (when-let [phase (event->phase (:hook_event_name raw))]
      (when (and (ge/tool-phase? phase) (str/blank? (:tool_name raw)))
        (throw (ex-info "guard: a Claude Code tool hook carries no tool_name"
                        {:error :guard/undecodable-event :raw raw})))
      (try
        (ge/guard-event (merge {:guard/phase phase :guard/harness harness}
                               (optional-entries raw)
                               (ling-entries raw)
                               (phase-entries phase raw)))
        (catch Exception e
          (throw (ex-info "guard: could not decode a Claude Code hook payload"
                          {:error :guard/undecodable-event :raw raw} e)))))))

;;; ===========================================================================
;;; Encode — GuardDecision -> hookSpecificOutput
;;; ===========================================================================

(defn- refusal-text
  "The body Claude Code shows for a refused call."
  [{:guard/keys [reason rule-id citations]}]
  (str "REFUSED by the hive guard.\n\n" reason
       (when (seq citations)
         (str "\n\nStated by: " (str/join ", " citations)))
       (when rule-id (str "\nRule: " rule-id))))

(defn- advisory-text
  "The body Claude Code shows as additional context for an advisory."
  [{:guard/keys [reason rule-id]}]
  (str "GUARD — " reason (when rule-id (str " [" rule-id "]"))))

(defn- hook-event-for
  "The Claude Code hook event a decision applies to.

   Normally the phase the guard stamped onto the decision. A `:deny` with no
   phase still resolves, because `:pre-tool` is the only DENIABLE phase — that
   is read off the event vocabulary, not guessed. A `:warn` with no phase
   resolves to nil and encodes to silence: a lost advisory costs a line of
   context, where a lost refusal would cost the refusal."
  [decision]
  (or (phase->event (:guard/phase decision))
      (when (= :deny (:guard/verdict decision)) "PreToolUse")))

(defn- block-text
  "The reason Claude Code hands the model when a Stop is blocked."
  [{:guard/keys [reason rule-id citations]}]
  (str "NOT DONE — the hive guard blocked ending this turn.\n\n" reason
       (when (seq citations)
         (str "\n\nStated by: " (str/join ", " citations)))
       (when rule-id (str "\nRule: " rule-id))))

(defn ->hook-output
  "Claude Code's hook output for `decision`.

   `:deny`  -> permissionDecision \"deny\" + the reason.
   `:warn`  -> additionalContext only; the call proceeds under the user's own
               permission rules, untouched.
   `:allow` -> {} — silence, so nothing the guard says can widen a permission.

   Stop is the exception to the hookSpecificOutput channel: Claude Code reads a
   Stop hook's TOP-LEVEL `decision`/`reason`. A `:deny` at :stop encodes to
   `{:decision \"block\"}` — the turn does not end, and the model is handed the
   reason to act on. A `:warn` at :stop encodes to `systemMessage`, shown to
   the user, since a Stop has no model-context channel.
   Pure; never throws."
  [decision]
  (if-let [hook-event (hook-event-for decision)]
    (if (= "Stop" hook-event)
      (case (:guard/verdict decision)
        :deny {:decision "block" :reason (block-text decision)}
        :warn {:systemMessage (advisory-text decision)}
        {})
      (let [base {:hookEventName hook-event}]
        (case (:guard/verdict decision)
          :deny {:hookSpecificOutput
                 (assoc base :permissionDecision "deny"
                        :permissionDecisionReason (refusal-text decision))}
          :warn {:hookSpecificOutput
                 (assoc base :additionalContext (advisory-text decision))}
          {})))
    {}))

;;; ===========================================================================
;;; Render — the dispatcher and the settings block
;;; ===========================================================================

(def ^:private transport-coord
  "The pinned coordinate the generated hook resolves for its nREPL client.

   PINNED, never RELEASE: a hook that resolves the newest artifact on every
   invocation can change what gates a session without anyone editing anything."
  "io.github.hive-agi/bb-mcp {:mvn/version \"1.2.14\"}")

(def ^:private dispatcher-path
  "Where the generated hook lives. One script for every hook event; the payload
   names its own moment, so a second script would be a second copy of the same
   transport."
  "~/.claude/hooks/hive-guard.bb")

(defn- rule-phases
  "The phases `rule-set` actually carries rules for, in a stable order."
  [rule-set]
  (->> rule-set
       (mapcat :rule/phases)
       set
       (keep phase->event)
       sort
       vec))

(defn dispatcher-script
  "The bb dispatcher, as text.

   Reads one hook payload on stdin, hands it to the live `:guard/decide` seam
   over the hive-mcp nREPL socket, prints the hook output on stdout.

   `provenance` is a ONE-LINE summary string, rendered into a `;;` comment.
   FAIL-LOUD on anything else, because both failure modes are silent:
   a newline breaks out of the comment and the rest becomes code the generated
   script would try to evaluate; and passing the RULE SET — the obvious-looking
   argument — inlines every rule into the header of a file whose next line says
   it carries none, which is precisely the copy that rots. Callers should go
   through `render-config`, which builds the summary.

   WHICH socket: the coordinator's, and only it. The port comes from
   HIVE_GUARD_NREPL_PORT, then BB_MCP_NREPL_PORT, then 7910 — never from a
   `.nrepl-port` in the session's working directory. bb-mcp's own resolver reads
   that file first, and a session that spawned a project REPL leaves one behind:
   measured 2026-09-29, every hook of such a session went to a REPL with no
   `:guard/decide` seam, answered `:seam-absent`, and was allowed unjudged, so
   every deny rule was off for the rest of the session without a trace in the
   ledger. A port whose reply has no seam is therefore SKIPPED, not trusted.

   FAIL-OPEN, and loudly: when no candidate port holds the seam, or the seam
   throws, or the reply is unreadable, it prints `{}` and exits 0, because a
   guard that cannot answer must not hold the tool call. It writes the reason to
   stderr so an allow-because-unreachable is visible rather than silent."
  [provenance]
  (when-not (string? provenance)
    (throw (ex-info "guard: dispatcher provenance must be a one-line string"
                    {:error :guard/invalid-provenance
                     :type  (some-> provenance class .getName)})))
  (when (str/includes? provenance "\n")
    (throw (ex-info "guard: dispatcher provenance must be ONE line — a newline would end the comment and leave code behind it"
                    {:error :guard/invalid-provenance
                     :provenance provenance})))
  (str "#!/usr/bin/env -S bb --config /dev/null
;; GENERATED by hive-claude.guard.projection/render-config — DO NOT EDIT.
;; " provenance "
;;
;; --config /dev/null: bb otherwise loads the bb.edn of the session's working
;; directory. A project bb.edn that brings its own :deps makes the add-deps
;; below fail with Coord of unknown type (measured 2026-10-06 from
;; clones-ref/datahike), and then every hook of that session errors.
;;
;; Transport only: this script carries NO rules. It forwards the hook payload to
;; the live :guard/decide seam, which evaluates the rule set held in hive memory.
;; Editing the memory entry that states a rule is what changes what fires.

(require '[babashka.deps :as deps])
(deps/add-deps '{:deps {" transport-coord "}})
(require '[bb-mcp.tools.nrepl :as nrepl]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def timeout-ms 5000)

(defn candidate-ports
  \"The coordinator's port, never a working directory's .nrepl-port: a session
   that spawned a project REPL leaves one behind, and that REPL has no seam.\"
  []
  (->> [(System/getenv \"HIVE_GUARD_NREPL_PORT\")
        (System/getenv \"BB_MCP_NREPL_PORT\")
        \"7910\"]
       (keep #(some-> % str/trim not-empty parse-long))
       distinct))

(defn decide-form [payload]
  (pr-str
   `(pr-str
     (try
       (if-let [d# ((requiring-resolve 'hive-mcp.extensions.registry/get-extension)
                    :guard/decide)]
         (into {} (d# :claude-code '~payload))
         {:guard/gap :seam-absent})
       (catch Throwable e#
         {:guard/gap :remote-threw :guard/gap-detail (ex-message e#)})))))

(defn ask
  \"{:decision d} from the seam at `port`, or {:miss reason} when that port is
   unreachable or holds no seam, which sends the caller on to the next port.\"
  [port payload]
  (let [res (try (nrepl/eval-code {:port port
                                   :code (decide-form payload)
                                   :timeout-ms timeout-ms})
                 (catch Exception e {:error? true :result (ex-message e)}))
        dec (when-not (:error? res)
              (try (edn/read-string (edn/read-string (:result res)))
                   (catch Exception _ nil)))]
    (cond
      (:error? res)                    {:miss (str port \": unreachable: \" (:result res))}
      (= :seam-absent (:guard/gap dec)) {:miss (str port \": no guard seam\")}
      :else                            {:decision dec})))

(defn ling-identity
  \"The ling identity of THIS hook process, from its environment. Headless
   lings are claude processes behind this same hook, and Claude Code's payload
   names no agent, so without it a ling looks like the operator. The
   credential crosses as PRESENCE only: its value never leaves this process.\"
  []
  (let [slave (System/getenv \"CLAUDE_SWARM_SLAVE_ID\")
        depth (System/getenv \"HIVE_LING_DEPTH\")
        cred? (some? (System/getenv \"HIVE_AGENT_CREDENTIAL\"))]
    (cond-> {}
      (not (str/blank? slave)) (assoc :slave_id slave)
      (not (str/blank? depth)) (assoc :depth depth)
      cred?                    (assoc :credential true))))

(defn open! [reason]
  (binding [*out* *err*] (println \"hive-guard: allowed unjudged —\" reason))
  (println \"{}\")
  (System/exit 0))

(let [payload (try (json/parse-stream *in* true) (catch Exception _ nil))
      ling    (ling-identity)
      payload (cond-> payload (and (map? payload) (seq ling)) (assoc :hive_ling ling))]
  (when-not (map? payload) (open! \"unreadable payload\"))
  (let [tries (reduce (fn [acc port]
                        (let [r (ask port payload)]
                          (if (:decision r) (reduced r) (conj acc (:miss r)))))
                      []
                      (candidate-ports))
        dec   (:decision tries)]
    (when-not dec (open! (str/join \"; \" tries)))
    (when-not (map? dec) (open! \"unreadable reply\"))
    (when (:guard/gap dec) (open! (str (:guard/gap dec) \" \" (:guard/gap-detail dec))))
    ;; The seam already encoded this through the :claude-code projection. Print
    ;; it. Re-deriving the hook shape here would be a second encoder, and the
    ;; one nobody regenerates is the one that rots. The top-level keys are the
    ;; Stop hook's: a Stop block is `decision`/`reason`, not hookSpecificOutput.
    (println (json/generate-string
              (select-keys dec [:hookSpecificOutput :decision :reason :systemMessage])))))
"))

(defn settings-block
  "The `hooks` map for `~/.claude/settings.json`, as data.

   One matcher per hook event the rule set actually uses. `*` because the guard
   decides which tools it cares about from the rule set — narrowing here would
   put the tool list in a second place. `path` is the dispatcher every hook
   runs; it defaults to the home hook directory."
  ([rule-set] (settings-block rule-set dispatcher-path))
  ([rule-set path]
   {:hooks
    (into {}
          (map (fn [event]
                 [event [{:matcher "*"
                          :hooks [{:type "command"
                                   :command path
                                   :timeout 10}]}]]))
          (rule-phases rule-set))}))

(defrecord ClaudeProjection [path]
  gp/IGuardProjection

  (harness-id [_] harness)

  (decode-event [_ raw] (->event raw))

  (encode-decision [_ decision] (->hook-output decision))

  (render-config [_ rule-set]
    (let [events (rule-phases rule-set)
          prov   (str "Generated from " (count rule-set) " rule(s) covering "
                      (str/join ", " events) ".")]
      {:files [{:path    path
                :content (dispatcher-script prov)
                :mode    "0755"}]
       :settings-block (settings-block rule-set path)
       :notes [(str "Merge :settings-block into ~/.claude/settings.json under \"hooks\". "
                    "It is returned as data rather than written, so the block is "
                    "reviewable before it can deny anything.")
               (str "Hook events registered: " (str/join ", " events)
                    " — derived from the phases the rule set carries, not from a list kept here.")
               "The script holds no rules. Rule edits land in hive memory and take effect without regenerating it."
               "Retires ~/.claude/hooks/auto-mode-guard.sh and carto-first.sh (GUARD-11)."]})))

(defn make-projection
  "Construct the `ClaudeProjection`.

   `:dispatcher-path` names where the rendered hook script is meant to live
   (default `~/.claude/hooks/hive-guard.bb`). Injecting it is what lets a caller,
   a test included, render for a home other than the real one."
  ([] (make-projection {}))
  ([opts]
   (->ClaudeProjection (get opts :dispatcher-path dispatcher-path))))

(defonce ^{:doc "The single `ClaudeProjection` this library publishes to the guard.

   The record is stateless, so one instance is the whole vendor surface. The
   addon publishes THIS VAR under `(gp/projection-ext-key harness)`; the guard
   sweeps that key namespace and adopts whatever it finds, which is why nothing
   here has to know when — or whether — the guard mounted."}
  instance
  (make-projection))
