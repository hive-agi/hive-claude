(ns hive-claude.guard.projection-test
  "Contract tests for the :claude-code IGuardProjection.

   Three properties carry this namespace:

   1. Every hook event the projection CLAIMS to map decodes into a conformant
      GuardEvent, and an event it does not map answers nil rather than guessing.
   2. The guard can REFUSE but never PERMIT. `permissionDecision` appears on a
      deny and on nothing else, so no verdict this projection encodes can widen
      a permission the user's own rules were going to be asked about.
   3. `render-config` is pure and rule-derived: it writes nothing, its hook
      registrations follow the phases the rule set carries, and the script it
      emits contains no rule."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-claude.guard.projection :as p]
            [hive-spi.guard.event :as ge]
            [hive-spi.guard.ports :as gp]
            [hive-spi.guard.rule :as gr]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

(def ^:private proj (p/make-projection))

(def ^:private rule-set
  [(gr/guard-rule {:rule/id      :t/deny-sed
                   :rule/phases  #{:pre-tool}
                   :rule/match   {:match/tool ["Bash"]}
                   :rule/verdict :deny
                   :rule/reason  "structural edits go through carto"})
   (gr/guard-rule {:rule/id      :t/session-reminder
                   :rule/phases  #{:session-start :subagent-start}
                   :rule/match   {}
                   :rule/verdict :warn
                   :rule/reason  "carto is the interface"})])

;;; ===========================================================================
;;; Identity
;;; ===========================================================================

(deftest the-projection-names-its-harness
  (is (= :claude-code (gp/harness-id proj)))
  (is (= :claude-code p/harness)
      "the id it registers under and the harness it stamps are one value"))

;;; ===========================================================================
;;; Decode
;;; ===========================================================================

(deftest every-mapped-hook-event-decodes-to-a-conformant-event
  (doseq [[raw expected-phase]
          [[{:hook_event_name "PreToolUse" :tool_name "Bash"
             :tool_input {:command "git add -A"} :cwd "/x" :session_id "s1"} :pre-tool]
           [{:hook_event_name "PostToolUse" :tool_name "Bash"
             :tool_input {:command "ls"} :tool_response "ok"} :post-tool]
           [{:hook_event_name "SessionStart" :source "startup"} :session-start]
           [{:hook_event_name "SubagentStart" :agent_type "Explore"} :subagent-start]
           [{:hook_event_name "UserPromptSubmit" :prompt "hi"} :prompt-submit]
           [{:hook_event_name "Stop"} :stop]]]
    (let [e (gp/decode-event proj raw)]
      (is (ge/valid? e) (str "not a conformant GuardEvent: " (pr-str raw)))
      (is (= expected-phase (:guard/phase e)))
      (is (= :claude-code (:guard/harness e))))))

(deftest the-decoded-event-carries-what-the-payload-carried
  (let [e (gp/decode-event proj {:hook_event_name "PreToolUse"
                                 :tool_name "Bash"
                                 :tool_input {:command "git add -A"}
                                 :cwd "/repo" :session_id "s1"})]
    (is (= "Bash" (:tool/name e)))
    (is (= {:command "git add -A"} (:tool/input e)))
    (is (= "/repo" (:cwd e)))
    (is (= "s1" (:session/id e)))))

(deftest an-unmapped-hook-event-is-nil-not-a-guess
  (doseq [raw [{:hook_event_name "PreCompact"}
               {:hook_event_name "Notification"}
               {:hook_event_name "SubagentStop"}
               {:no-event-name true}]]
    (is (nil? (gp/decode-event proj raw))
        (str "a moment this projection does not map must decode to nil: " (pr-str raw))))
  (is (nil? (gp/decode-event proj "not-a-map"))))

(deftest a-tool-hook-without-a-tool-name-is-LOUD
  (doseq [raw [{:hook_event_name "PreToolUse" :tool_input {:command "x"}}
               {:hook_event_name "PostToolUse" :tool_name "  "}]]
    (let [ex (try (gp/decode-event proj raw) (catch Exception e e))]
      (is (instance? clojure.lang.ExceptionInfo ex)
          (str "expected a throw for " (pr-str raw)))
      (is (= :guard/undecodable-event (:error (ex-data ex)))
          "decoding half an event and judging on the missing half is the failure to avoid"))))

;;; ===========================================================================
;;; Encode — the guard refuses; it never permits
;;; ===========================================================================

(deftest a-deny-spends-the-permission-channel-and-says-why
  (let [out (gp/encode-decision proj {:guard/phase :pre-tool
                                      :guard/verdict :deny
                                      :guard/reason "stage explicit paths"
                                      :guard/rule-id :guard/no-git-add-all
                                      :guard/citations ["20260711024017-553c8d7b"]})
        h   (:hookSpecificOutput out)]
    (is (= "PreToolUse" (:hookEventName h)))
    (is (= "deny" (:permissionDecision h)))
    (is (str/includes? (:permissionDecisionReason h) "stage explicit paths"))
    (is (str/includes? (:permissionDecisionReason h) "20260711024017-553c8d7b")
        "a refusal names the entry that justifies it, or it cannot be argued with")
    (is (str/includes? (:permissionDecisionReason h) "no-git-add-all"))))

(deftest a-warn-advises-and-does-NOT-touch-the-permission-channel
  (let [h (:hookSpecificOutput
           (gp/encode-decision proj {:guard/phase :pre-tool
                                     :guard/verdict :warn
                                     :guard/reason "prefer carto search"
                                     :guard/rule-id :guard/carto-first-shell-search}))]
    (is (str/includes? (:additionalContext h) "prefer carto search"))
    (is (not (contains? h :permissionDecision))
        "writing \"allow\" here would AUTO-APPROVE a call the user's own rules were going to gate")))

(deftest an-allow-is-silence
  (is (= {} (gp/encode-decision proj {:guard/phase :pre-tool :guard/verdict :allow}))))

(deftest no-verdict-can-widen-a-permission
  (testing "across every verdict, the only permissionDecision this projection emits is deny"
    (doseq [verdict [:allow :warn :deny]]
      (let [h (:hookSpecificOutput
               (gp/encode-decision proj {:guard/phase :pre-tool
                                         :guard/verdict verdict
                                         :guard/reason "r"}))]
        (is (contains? #{nil "deny"} (:permissionDecision h))
            (str "verdict " verdict " emitted permissionDecision "
                 (pr-str (:permissionDecision h))))))))

;;; ===========================================================================
;;; Render
;;; ===========================================================================

(deftest render-config-writes-nothing-and-returns-content
  (let [{:keys [files settings-block notes]} (gp/render-config proj rule-set)
        script (:content (first files))]
    (is (= 1 (count files)) "one dispatcher, because the payload names its own moment")
    (is (str/ends-with? (:path (first files)) "hive-guard.bb"))
    (is (= "0755" (:mode (first files))))
    (is (string? script))
    (is (seq notes))
    (is (map? settings-block))
    (doseq [f files]
      (is (not (.exists (java.io.File. (str/replace (:path f) #"^~" (System/getProperty "user.home")))))
          "render-config must not have installed anything"))))

(deftest the-hook-registrations-follow-the-RULE-SET
  (let [events (-> (gp/render-config proj rule-set) :settings-block :hooks keys set)]
    (is (= #{"PreToolUse" "SessionStart" "SubagentStart"} events)
        "exactly the phases the rules carry — no more, no less")
    (is (not (contains? events "Stop"))
        "a rule set with no :stop rule registers no Stop hook"))
  (testing "an empty rule set registers no hooks at all"
    (is (empty? (-> (gp/render-config proj []) :settings-block :hooks)))))

(deftest the-generated-script-carries-no-rules
  (let [script (-> (gp/render-config proj rule-set) :files first :content)]
    (is (not (str/includes? script "carto is the interface"))
        "a reason baked into the script would be the second copy this epic exists to delete")
    (is (not (str/includes? script ":t/deny-sed")))
    (is (str/includes? script ":guard/decide")
        "it forwards to the one evaluator instead")
    (is (str/includes? script "GENERATED"))
    (is (str/includes? script "System/exit 0")
        "a guard that cannot answer must fail open rather than hold the tool call")))

(deftest the-script-holds-no-second-encoder
  (let [script (-> (gp/render-config proj rule-set) :files first :content)]
    (is (not (str/includes? script "permissionDecision"))
        "the seam already encoded the decision through this projection; a second
         encoder in the script is the copy that rots")
    (is (str/includes? script "hookSpecificOutput")
        "it forwards what the seam encoded")))

(deftest a-decision-carrying-no-phase-still-refuses
  (testing ":pre-tool is the only DENIABLE phase, so a deny resolves its hook event
            from the vocabulary rather than needing to be told"
    (let [h (:hookSpecificOutput (gp/encode-decision proj {:guard/verdict :deny
                                                           :guard/reason "no"}))]
      (is (= "PreToolUse" (:hookEventName h)))
      (is (= "deny" (:permissionDecision h)))))
  (testing "a warn with no phase is silence, not a malformed hook output"
    (is (= {} (gp/encode-decision proj {:guard/verdict :warn :guard/reason "hm"})))))

(deftest no-encoded-output-ever-carries-a-nil-hook-event
  (doseq [d [{:guard/verdict :deny :guard/reason "r"}
             {:guard/verdict :warn :guard/reason "r"}
             {:guard/verdict :allow}
             {:guard/phase :pre-tool :guard/verdict :deny :guard/reason "r"}
             {:guard/phase :session-start :guard/verdict :warn :guard/reason "r"}
             {:guard/phase :stop :guard/verdict :warn :guard/reason "r"}]]
    (let [out (gp/encode-decision proj d)]
      (when-let [h (:hookSpecificOutput out)]
        (is (string? (:hookEventName h))
            (str "a hook output naming no event is malformed: " (pr-str d)))))))

(deftest the-provenance-header-says-what-it-was-generated-from
  (let [script (-> (gp/render-config proj rule-set) :files first :content)]
    (is (str/includes? script "2 rule(s)"))
    (is (str/includes? script "PreToolUse"))))

(deftest a-provenance-that-would-corrupt-the-script-is-refused
  (testing "a newline ends the ;; comment and leaves the rest as code"
    (let [ex (try (p/dispatcher-script "line one\n(System/exit 1)")
                  (catch Exception e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= :guard/invalid-provenance (:error (ex-data ex))))))
  (testing "and the rule SET is not a provenance, however plausible it looks"
    ;; Passing rule-set here would inline every rule into the header of a file
    ;; whose next line says it carries none — the copy that rots.
    (let [ex (try (p/dispatcher-script rule-set) (catch Exception e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= :guard/invalid-provenance (:error (ex-data ex))))))
  (testing "a one-line summary is accepted and lands in the header"
    (let [script (p/dispatcher-script "Generated from 2 rule(s).")]
      (is (str/includes? script ";; Generated from 2 rule(s)."))
      (is (str/includes? script "carries NO rules")))))

(deftest every-line-of-the-header-is-a-comment
  (testing "nothing above the first form can be read as code"
    (let [script (-> (gp/render-config proj rule-set) :files first :content)
          header (take-while #(not (str/starts-with? % "(")) (str/split-lines script))]
      (doseq [line header]
        (is (or (str/blank? line)
                (str/starts-with? line ";;")
                (str/starts-with? line "#!"))
            (str "header line is not a comment: " (pr-str line)))))))
