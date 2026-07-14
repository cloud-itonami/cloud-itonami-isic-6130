(ns satcom.governor
  "Satellite Network Governor -- the independent compliance layer that
  earns the Satellite Operations Advisor the right to commit (named
  `:satellite-network-governor` in this repo's own `blueprint.edn`).
  The LLM has no notion of which jurisdiction's satellite-licensing/
  ITU-coordination law is official, whether a terminal's own recorded
  satellite number is even syntactically valid, whether a terminal's
  own subscriber-identity verification has actually been completed
  with full evidence, whether an unresolved ITU frequency-coordination
  or orbital-slot dispute against the terminal has actually stayed
  unresolved, or when an act stops being a draft and becomes a
  real-world satellite-capacity provisioning or service suspension, so
  this MUST be a separate system able to *reject* a proposal and fall
  back to HOLD -- the satellite-operator analog of `cloud-itonami-
  isic-6190`'s `telecom.governor` (Telecom Access Governor) and
  `cloud-itonami-isic-6120`'s `wirelesstelecom.governor` (Mobile
  Network Governor).

  Five checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them (you don't get to approve your way past a
  fabricated satellite-licensing spec-basis, incomplete evidence, a
  malformed satellite number, an unresolved ITU-coordination dispute,
  or a double provisioning/suspension). The confidence/actuation gate
  is SOFT: it asks a human to look (low confidence / actuation), and
  the human may approve -- but see `satcom.phase`: for `:stake
  :actuation/provision-capacity`/`:actuation/suspend-service` (a
  real-world act) NO phase ever allows auto-commit either. Two
  independent layers agree that actuation is always a human call.

    1. Spec-basis                  -- did the identity proposal cite
                                       an OFFICIAL satellite-licensing/
                                       ITU-coordination source
                                       (`satcom.facts`), or invent one?
    2. Evidence incomplete         -- for `:actuation/provision-
                                       capacity`/`:actuation/suspend-
                                       service`, has the terminal
                                       actually been identity-verified
                                       with a full evidence checklist
                                       on file?
    3. Satellite-number format
       invalid                      -- for `:actuation/provision-
                                       capacity`, INDEPENDENTLY
                                       recompute whether the
                                       terminal's own recorded
                                       satellite number is
                                       syntactically valid
                                       (`satcom.registry/satellite-
                                       number-invalid-format?`) -- needs
                                       no proposal inspection or
                                       stored-verdict lookup at all.
                                       The THIRD application of this
                                       fleet's format/syntactic-
                                       validity check family (after
                                       `telecom.governor`'s `e164-
                                       format-invalid`, `6190`, and
                                       `wirelesstelecom.governor`'s
                                       `msisdn-format-invalid`, `6120`).
    4. ITU-coordination dispute
       unresolved                    -- reported by THIS proposal
                                       itself (a `:coordination/
                                       screen` that just found an
                                       unresolved dispute), or already
                                       on file for the terminal
                                       (`:coordination/screen`/
                                       `:actuation/suspend-service`).
                                       Evaluated UNCONDITIONALLY (not
                                       scoped to a specific op), the
                                       SAME discipline `telecom.
                                       governor/billing-dispute-
                                       unresolved-violations`
                                       (`6190`)/`wirelesstelecom.
                                       governor/license-dispute-
                                       unresolved-violations` (`6120`)
                                       establish -- applied here to an
                                       unresolved ITU frequency-
                                       coordination or orbital-slot
                                       interference dispute on the
                                       terminal's own site. Like its
                                       siblings, exercised in
                                       tests/demo via `:coordination/
                                       screen` DIRECTLY, not via an
                                       actuation op against an
                                       unscreened terminal -- see this
                                       ns's own test suite.
    5. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:actuation/
                                       provision-capacity`/`:actuation/
                                       suspend-service` (REAL acts) ->
                                       escalate.

  One more guard pair, double-provisioning/double-suspension
  prevention, is enforced but NOT listed as a numbered HARD check above
  because it needs no upstream comparison at all --
  `already-provisioned-violations`/`already-suspended-violations`
  refuse to provision capacity/suspend service for the SAME terminal
  twice, off dedicated `:capacity-provisioned?`/`:service-suspended?`
  facts (never a `:status` value) -- the SAME 'check a dedicated
  boolean, not status' discipline every prior sibling governor's
  guards establish, informed by `cloud-itonami-isic-6492`'s
  status-lifecycle bug (ADR-2607071320).

  Out of scope, by construction, on both independent layers (see
  README `Scope`): real ground-station/gateway dispatch, real
  transponder-payload telemetry, lawful-intercept and any law-
  enforcement-ordered suspension distinct from ordinary `:actuation/
  suspend-service` are NEVER actor operations at all -- there is no op
  in this namespace's `high-stakes` set, `satcom.phase`'s tables, or
  `satcom.satcomadvisor`'s `infer` dispatch for any of them, mirroring
  `wirelesstelecom.governor`'s own sibling posture (`cloud-itonami-
  isic-6120`)."
  (:require [satcom.facts :as facts]
            [satcom.registry :as registry]
            [satcom.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Provisioning real satellite capacity and suspending a real
  terminal's service are the two real-world actuation events this
  actor performs -- a two-member set, matching every prior
  dual-actuation sibling's shape (including `cloud-itonami-isic-6190`'s
  own `telecom.governor/high-stakes` and `cloud-itonami-isic-6120`'s
  own `wirelesstelecom.governor/high-stakes`)."
  #{:actuation/provision-capacity :actuation/suspend-service})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:identity/verify` (or actuation) proposal with no spec-basis
  citation is a HARD violation -- never invent a jurisdiction's
  satellite-licensing/ITU-coordination requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:identity/verify :actuation/provision-capacity :actuation/suspend-service} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は衛星免許・ITU国際調整要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:actuation/provision-capacity`/`:actuation/suspend-service`,
  the jurisdiction's required subscriber-identity-verification-record/
  satellite-number-assignment-record/ITU-coordination-filing-record/
  service-suspension-log evidence must actually be satisfied -- do not
  trust the advisor's self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (contains? #{:actuation/provision-capacity :actuation/suspend-service} op)
    (let [tm (store/terminal st subject)
          verification (store/identity-verification-of st subject)]
      (when-not (and verification
                     (facts/required-evidence-satisfied?
                      (:jurisdiction tm) (:checklist verification)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(契約者確認記録/衛星回線番号割当記録/ITU国際調整申請記録/回線停止台帳等)が充足していない状態での提案"}]))))

(defn- satellite-number-format-invalid-violations
  "For `:actuation/provision-capacity`, INDEPENDENTLY recompute whether
  the terminal's own recorded satellite number is syntactically valid
  via `satcom.registry/satellite-number-invalid-format?` -- needs no
  proposal inspection or stored-verdict lookup at all, since its
  inputs are a permanent ground-truth field already on the terminal."
  [{:keys [op subject]} st]
  (when (= op :actuation/provision-capacity)
    (let [tm (store/terminal st subject)]
      (when (registry/satellite-number-invalid-format? tm)
        [{:rule :satellite-number-format-invalid
          :detail (str subject " の記録番号(" (:satellite-number tm) ")は衛星回線番号(E.164)形式として不正")}]))))

(defn- coordination-dispute-unresolved-violations
  "An unresolved ITU frequency-coordination or orbital-slot
  interference dispute on the terminal's site -- reported by THIS
  proposal (e.g. a `:coordination/screen` that itself just found one),
  or already on file in the store for the terminal (`:coordination/
  screen`/`:actuation/suspend-service`) -- is a HARD, un-overridable
  hold. Evaluated UNCONDITIONALLY (not scoped to a specific op) so the
  screening op itself can HARD-hold on its own finding. Mirrors
  `telecom.governor/billing-dispute-unresolved-violations` (`6190`)/
  `wirelesstelecom.governor/license-dispute-unresolved-violations`
  (`6120`)."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :unresolved (get-in proposal [:value :verdict]))
        terminal-id (when (contains? #{:coordination/screen :actuation/suspend-service} op) subject)
        hit-on-file? (and terminal-id (= :unresolved (:verdict (store/coordination-screen-of st terminal-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :coordination-dispute-unresolved
        :detail "未解決のITU国際調整・軌道位置紛争がある状態での回線停止提案は進められない"}])))

(defn- already-provisioned-violations
  "For `:actuation/provision-capacity`, refuses to provision capacity
  for the SAME terminal twice, off a dedicated `:capacity-
  provisioned?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :actuation/provision-capacity)
    (when (store/terminal-already-provisioned? st subject)
      [{:rule :already-provisioned
        :detail (str subject " は既に衛星回線容量発行済み")}])))

(defn- already-suspended-violations
  "For `:actuation/suspend-service`, refuses to suspend service for the
  SAME terminal twice, off a dedicated `:service-suspended?` fact
  (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :actuation/suspend-service)
    (when (store/terminal-already-suspended? st subject)
      [{:rule :already-suspended
        :detail (str subject " は既に回線停止済み")}])))

(defn check
  "Censors a Satellite Operations Advisor proposal against the
  governor rules. Returns {:ok? bool :violations [..] :confidence c
  :escalate? bool :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (satellite-number-format-invalid-violations request st)
                           (coordination-dispute-unresolved-violations request proposal st)
                           (already-provisioned-violations request st)
                           (already-suspended-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
