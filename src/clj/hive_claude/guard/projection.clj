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
   The decode half, exposed for a caller that already holds the parsed payload."
  [raw]
  (when (map? raw)
    (when-let [phase (event->phase (:hook_event_name raw))]
      (when (and (ge/tool-phase? phase) (str/blank? (:tool_name raw)))
        (throw (ex-info "guard: a Claude Code tool hook carries no tool_name"
                        {:error :guard/undecodable-event :raw raw})))
      (try
        (ge/guard-event (merge {:guard/phase phase :guard/harness harness}
                               (optional-entries raw)
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

(defn ->hook-output
  "Claude Code's hook output for `decision`.

   `:deny`  -> permissionDecision \"deny\" + the reason.
   `:warn`  -> additionalContext only; the call proceeds under the user's own
               permission rules, untouched.
   `:allow` -> {} — silence, so nothing the guard says can widen a permission.
   Pure; never throws."
  [decision]
  (if-let [hook-event (hook-event-for decision)]
    (let [base {:hookEventName hook-event}]
      (case (:guard/verdict decision)
        :deny {:hookSpecificOutput
               (assoc base :permissionDecision "deny"
                      :permissionDecisionReason (refusal-text decision))}
        :warn {:hookSpecificOutput
               (assoc base :additionalContext (advisory-text decision))}
        {}))
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

   FAIL-OPEN, and loudly: every way the round-trip can fail — no JVM, no seam, a
   throwing seam, an unreadable reply — prints `{}` and exits 0, because a guard
   that cannot answer must not hold the tool call. It writes the reason to
   stderr so an allow-because-unreachable is visible rather than silent."
  [provenance]
  (str "#!/usr/bin/env bb
;; GENERATED by hive-claude.guard.projection/render-config — DO NOT EDIT.
;; " provenance "
;;
;; Transport only: this script carries NO rules. It forwards the hook payload to
;; the live :guard/decide seam, which evaluates the rule set held in hive memory.
;; Editing the memory entry that states a rule is what changes what fires.

(require '[babashka.deps :as deps])
(deps/add-deps '{:deps {" transport-coord "}})
(require '[bb-mcp.tools.nrepl :as nrepl]
         '[cheshire.core :as json]
         '[clojure.edn :as edn])

(def timeout-ms 5000)

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

(defn open! [reason]
  (binding [*out* *err*] (println \"hive-guard: allowed unjudged —\" reason))
  (println \"{}\")
  (System/exit 0))

(let [payload (try (json/parse-stream *in* true) (catch Exception _ nil))]
  (when-not (map? payload) (open! \"unreadable payload\"))
  (let [res (try (nrepl/eval-code {:port (nrepl/get-nrepl-port)
                                   :code (decide-form payload)
                                   :timeout-ms timeout-ms})
                 (catch Exception e {:error? true :result (ex-message e)}))
        _   (when (:error? res) (open! (str \"guard unreachable: \" (:result res))))
        dec (try (edn/read-string (edn/read-string (:result res)))
                 (catch Exception _ nil))]
    (when-not (map? dec) (open! \"unreadable reply\"))
    (when (:guard/gap dec) (open! (str (:guard/gap dec) \" \" (:guard/gap-detail dec))))
    ;; The seam already encoded this through the :claude-code projection. Print
    ;; it. Re-deriving the hook shape here would be a second encoder, and the
    ;; one nobody regenerates is the one that rots.
    (println (json/generate-string (select-keys dec [:hookSpecificOutput])))))
"))

(defn settings-block
  "The `hooks` map for `~/.claude/settings.json`, as data.

   One matcher per hook event the rule set actually uses. `*` because the guard
   decides which tools it cares about from the rule set — narrowing here would
   put the tool list in a second place."
  [rule-set]
  {:hooks
   (into {}
         (map (fn [event]
                [event [{:matcher "*"
                         :hooks [{:type "command"
                                  :command dispatcher-path
                                  :timeout 10}]}]]))
         (rule-phases rule-set))})

(defrecord ClaudeProjection []
  gp/IGuardProjection

  (harness-id [_] harness)

  (decode-event [_ raw] (->event raw))

  (encode-decision [_ decision] (->hook-output decision))

  (render-config [_ rule-set]
    (let [events (rule-phases rule-set)
          prov   (str "Generated from " (count rule-set) " rule(s) covering "
                      (str/join ", " events) ".")]
      {:files [{:path    dispatcher-path
                :content (dispatcher-script prov)
                :mode    "0755"}]
       :settings-block (settings-block rule-set)
       :notes [(str "Merge :settings-block into ~/.claude/settings.json under \"hooks\". "
                    "It is returned as data rather than written, so the block is "
                    "reviewable before it can deny anything.")
               (str "Hook events registered: " (str/join ", " events)
                    " — derived from the phases the rule set carries, not from a list kept here.")
               "The script holds no rules. Rule edits land in hive memory and take effect without regenerating it."
               "Retires ~/.claude/hooks/auto-mode-guard.sh and carto-first.sh (GUARD-11)."]})))

(defn make-projection
  "Construct the `ClaudeProjection`."
  []
  (->ClaudeProjection))

(defonce ^{:doc "The single `ClaudeProjection` this library publishes to the guard.

   The record is stateless, so one instance is the whole vendor surface. The
   addon publishes THIS VAR under `(gp/projection-ext-key harness)`; the guard
   sweeps that key namespace and adopts whatever it finds, which is why nothing
   here has to know when — or whether — the guard mounted."}
  instance
  (make-projection))
