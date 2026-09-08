(ns satcom.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-6130`: this
  repo previously had NO demo page and no generator at all (there was
  not even a `docs/samples/` directory), so there was also no
  hand-written page to evict.

  This namespace drives the REAL actor stack -- `satcom.operation`
  (the langgraph-clj StateGraph) -> `satcom.governor` (Satellite
  Network Governor) -> `satcom.store` (MemStore SSoT) -- and renders
  the resulting store. Nothing on the page is typed by hand:

    - every terminal row comes from `satcom.store/demo-data` via
      `store/all-terminals`;
    - every HARD-hold row comes from a `:governor-hold` fact the real
      governor actually wrote to the real ledger, including the
      governor's own `:detail` string;
    - every jurisdiction row comes from `satcom.facts/catalog`, and the
      coverage line from `satcom.facts/coverage` -- so a jurisdiction
      with no spec-basis is reported as missing, never papered over;
    - every draft registry record comes from
      `store/provisioning-history` / `store/suspension-history`, i.e.
      from `satcom.registry`;
    - the rollout-phase table comes from one further REAL graph run per
      (write-op x phase) cell -- every op in `satcom.phase/write-ops`,
      derived rather than listed by hand -- not from reading
      `satcom.phase`'s tables, and the paragraph above that table
      states what those runs measured rather than asserting a rule;
    - the approver-attribution table is DERIVED by probing the store
      for a retained approver key (see `approver-in`), so it reports
      what this store actually does today rather than a hardcoded
      claim about it.

  There are no timestamps in the page, so two runs against the same
  seed are byte-identical (verified by diffing two runs).

  `-main` THROWS if the real governor produced zero HARD
  `:governor-hold` facts, if any rule that did fire is missing from the
  rendered document, or if any op declared in `satcom.phase/write-ops`
  was never measured by the phase probe. A demo whose compliance layer
  silently stopped firing -- or whose action gate quietly stopped
  covering an op -- must not be publishable.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [kotoba.lang.text :as str]
            [jp-go-dds.skin :as skin]
            [langgraph.graph :as g]
            [satcom.facts :as facts]
            [satcom.operation :as op]
            [satcom.phase :as phase]
            [satcom.store :as store]))

(def ^:private operator
  "The human satellite operator this demo runs as. `:phase` defaults to
  the repo's own `satcom.phase/default-phase` rather than a literal."
  {:actor-id "op-1" :actor-role :satellite-operator :phase phase/default-phase})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- resume!
  "Resume a run paused at `:request-approval` with a human decision."
  [actor tid status]
  (g/run* actor {:approval {:status status :by (:actor-id operator)}}
          {:thread-id tid :resume? true}))

;; ----------------------------- the real run -----------------------------

(defn run-demo!
  "Runs a fresh seeded store (`satcom.store/demo-data`: term-1..term-4)
  through a scenario that reaches every disposition this actor can
  produce and fires EVERY ONE of the Satellite Network Governor's six
  HARD rules.

  term-1 (Sakura Remote Clinic VSAT, JPN, valid GMSS number, no
  dispute) walks the full lifecycle: intake (auto-commits at phase 3 --
  the only auto-eligible op), identity verification (escalates, human
  approves), ITU-coordination screening (escalates, approves),
  capacity provisioning and service suspension (both ALWAYS escalate --
  `:actuation/*` is absent from every phase's `:auto` set, and the
  governor's `high-stakes` set says the same thing independently).
  Re-running each actuation then HARD-holds on `:already-provisioned`
  / `:already-suspended`.

  term-2 (Atlantis Research Station) is seeded with jurisdiction
  \"ATL\", which is genuinely absent from `satcom.facts/catalog` -- so
  its identity verification HARD-holds on `:no-spec-basis` from the
  seed data alone, with no test flag needed. Provisioning capacity for
  it then HARD-holds on `:evidence-incomplete`, because the held
  verification never put a checklist on file.

  term-3 (鈴木海洋観測ブイ) is seeded with satellite-number
  \"0312345678\", which is not E.164/GMSS-shaped; its verification
  commits, and provisioning then HARD-holds on
  `:satellite-number-format-invalid` -- recomputed by the governor from
  the terminal's own field, never read back from a stored verdict.

  term-4 (田中離島診療所) is seeded with an unresolved ITU
  coordination dispute; the screening op HARD-holds on its own finding
  (`:coordination-dispute-unresolved`) and never reaches a human. A
  subsequent suspension proposal DOES reach a human -- because the
  held screening put nothing on file for the governor to read -- and
  the human rejects it. That is the honest shape of this actor: the
  HARD hold stops the machine, and the human is the backstop for what
  the machine cannot see.

  Returns {:db store :runs [..]} -- `:runs` carries each graph run's
  `:audit` channel, which is where `:approval-granted` facts live (the
  store ledger only receives commits and holds)."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        runs (atom [])
        record! (fn [tid request r]
                  (swap! runs conj {:thread tid
                                    :request request
                                    :disposition (get-in r [:state :disposition])
                                    :audit (vec (get-in r [:state :audit]))})
                  r)
        step! (fn step!
                ([tid request] (step! tid request nil))
                ([tid request approval]
                 (let [r0 (exec! actor tid request operator)
                       r (if approval (resume! actor tid approval) r0)]
                   (record! tid request r))))
        ;; the intake patch is taken from the seeded record itself, so
        ;; even the demo's own input traces back to `store/demo-data`.
        seeded (fn [id ks] (select-keys (store/terminal db id) ks))]

    ;; --- term-1: the clean full lifecycle -------------------------------
    (step! "t1-intake"    {:op :terminal/intake :subject "term-1"
                           :patch (seeded "term-1" [:id :holder-name])})
    (step! "t1-verify"    {:op :identity/verify :subject "term-1"} :approved)
    (step! "t1-screen"    {:op :coordination/screen :subject "term-1"} :approved)
    (step! "t1-provision" {:op :actuation/provision-capacity :subject "term-1"} :approved)
    (step! "t1-suspend"   {:op :actuation/suspend-service :subject "term-1"} :approved)

    ;; --- term-1 again: the two double-actuation guards ------------------
    (step! "t1-reprovision" {:op :actuation/provision-capacity :subject "term-1"})
    (step! "t1-resuspend"   {:op :actuation/suspend-service :subject "term-1"})

    ;; --- term-2: unregistered jurisdiction, then missing evidence -------
    (step! "t2-verify"    {:op :identity/verify :subject "term-2"})
    (step! "t2-provision" {:op :actuation/provision-capacity :subject "term-2"})

    ;; --- term-3: malformed satellite number -----------------------------
    (step! "t3-verify"    {:op :identity/verify :subject "term-3"} :approved)
    (step! "t3-provision" {:op :actuation/provision-capacity :subject "term-3"})

    ;; --- term-4: unresolved ITU dispute, then a human rejection ---------
    (step! "t4-verify"    {:op :identity/verify :subject "term-4"} :approved)
    (step! "t4-screen"    {:op :coordination/screen :subject "term-4"})
    (step! "t4-suspend"   {:op :actuation/suspend-service :subject "term-4"} :rejected)

    {:db db :runs @runs}))

(defn phase-probe!
  "One REAL graph run per (write-op x rollout-phase) cell: EVERY op in
  `satcom.phase/write-ops` against every phase declared in
  `satcom.phase/phases`. Each cell gets its OWN freshly seeded store,
  set up at the default phase (intake + an approved identity
  verification, so the evidence gate is satisfied and the phase gate is
  what the probe is actually measuring), and then runs ONE op under a
  context pinned to that phase.

  The op list is DERIVED from `phase/write-ops` rather than written out
  here. A literal list silently omits any op added to the actor later:
  this probe used to name four ops by hand and so never measured
  `:actuation/suspend-service` at all -- one of the two real-world
  actuations was missing from the published action gate while the page
  still looked complete.

  This is measured, not read off `satcom.phase`'s tables -- which is
  the point: it is what makes the `:actuation/*` rows at the most
  permissive phase evidence rather than a restatement of the comment
  above the table."
  []
  (vec
   (for [ph (sort (keys phase/phases))
         probe-op (sort phase/write-ops)]
     (let [db (store/seed-db)
           actor (op/build db)
           patch (select-keys (store/terminal db "term-1") [:id :holder-name])]
       ;; setup, at the default phase
       (exec! actor "setup-intake" {:op :terminal/intake :subject "term-1" :patch patch} operator)
       (exec! actor "setup-verify" {:op :identity/verify :subject "term-1"} operator)
       (resume! actor "setup-verify" :approved)
       (let [request (cond-> {:op probe-op :subject "term-1"}
                       (= :terminal/intake probe-op) (assoc :patch patch))
             state (:state (exec! actor "probe" request (assoc operator :phase ph)))
             audit (vec (:audit state))
             hold (last (filter #(= :governor-hold (:t %)) audit))
             ask (last (filter #(= :approval-requested (:t %)) audit))]
         {:phase ph
          :label (get-in phase/phases [ph :label])
          :op probe-op
          :disposition (:disposition state)
          :reason (or (:phase-reason hold)
                      (when (seq (:basis hold))
                        (str/join ", " (map name (:basis hold))))
                      (:reason ask))})))))

;; ----------------------------- derived facts -----------------------------

(defn- approver-in
  "Scan a store-side record for a RETAINED approver identity, whatever
  key it might have landed on (keyword or string, `-` or `_`). Returns
  the value, or nil when the store kept none.

  Derived on purpose: the disclosure this drives must not be a
  hardcoded claim that the store drops the approver, because that
  claim becomes a lie the moment someone fixes the store."
  [m]
  (when (map? m)
    (some (fn [[k v]]
            (let [n (-> (if (keyword? k) (name k) (str k))
                        (str/lower)
                        (str/replace "_" "-"))]
              (when (contains? #{"approved-by" "approver" "approved-by-id"} n) v)))
          m)))

(defn- store-record-for
  "The record the store actually wrote for `effect` on `subject` --
  i.e. what a later reader of the SSoT would get back."
  [db effect subject]
  (case effect
    :verification/set (store/identity-verification-of db subject)
    :coordination-screen/set (store/coordination-screen-of db subject)
    :terminal/upsert (store/terminal db subject)
    :terminal/mark-provisioned
    (last (filter #(= subject (get % "terminal_id")) (store/provisioning-history db)))
    :terminal/mark-suspended
    (last (filter #(= subject (get % "terminal_id")) (store/suspension-history db)))
    nil))

(def ^:private effect-of
  "op -> the SSoT effect its proposal carries (`satcom.satcomadvisor`)."
  {:terminal/intake :terminal/upsert
   :identity/verify :verification/set
   :coordination/screen :coordination-screen/set
   :actuation/provision-capacity :terminal/mark-provisioned
   :actuation/suspend-service :terminal/mark-suspended})

(defn approval-attribution
  "For every run a human actually decided, join the approver identity
  from the graph's `:audit` channel against what the STORE kept, and
  report both. A reader must be able to tell 'nobody approved' apart
  from 'the store dropped it'."
  [{:keys [db runs]}]
  (vec
   (for [{:keys [request audit]} runs
         :let [granted (last (filter #(= :approval-granted (:t %)) audit))
               rejected (last (filter #(= :approval-rejected (:t %)) audit))]
         :when (or granted rejected)]
     (let [subject (:subject request)
           effect (effect-of (:op request))
           rec (store-record-for db effect subject)]
       {:op (:op request)
        :subject subject
        :effect effect
        :decision (if granted :approved :rejected)
        :audit-approver (:by granted)
        :store-approver (approver-in rec)
        :store-reachable? (some? rec)}))))

(defn hard-holds
  "Every HARD `:governor-hold` fact the real governor wrote, flattened
  one row per violation. `:detail` is the governor's own string."
  [db]
  (vec
   (for [f (store/ledger db)
         :when (and (= :governor-hold (:t f)) (seq (:violations f)))
         v (:violations f)]
     {:rule (:rule v) :detail (:detail v)
      :op (:op f) :subject (:subject f) :confidence (:confidence f)
      :holder (:holder-name (store/terminal db (:subject f)))})))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (str "<code>" (esc (if (keyword? v) (str v) v)) "</code>"))

(defn- yn [b klass-true klass-false t f]
  (format "<span class=\"%s\">%s</span>" (if b klass-true klass-false) (if b t f)))

(defn- last-fact-for [ledger id]
  (last (filter #(= (:subject %) id) ledger)))

(defn- status-cell [ledger id]
  (let [f (last-fact-for ledger id)]
    (case (:t f)
      nil "<span class=\"muted\">no activity</span>"
      :committed "<span class=\"ok\">committed</span>"
      :approval-rejected "<span class=\"warn\">human rejected</span>"
      :governor-hold
      (str "<span class=\"critical\">HARD hold · "
           (esc (str/join ", " (map name (:basis f)))) "</span>")
      "<span class=\"muted\">in progress</span>")))

(defn- lifecycle-cell [{:keys [capacity-provisioned? service-suspended?]}]
  (cond
    (and capacity-provisioned? service-suspended?)
    "<span class=\"warn\">provisioned, then suspended</span>"
    capacity-provisioned? "<span class=\"ok\">capacity provisioned</span>"
    service-suspended? "<span class=\"warn\">service suspended</span>"
    :else "<span class=\"muted\">not yet actuated</span>"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- rows [xs] (str/join "\n" xs))

(defn- table [headers body-rows]
  (str "    <table>\n"
       "      <thead><tr>"
       (str/join (map #(str "<th>" % "</th>") headers))
       "</tr></thead>\n"
       "      <tbody>\n" (rows body-rows) "\n      </tbody>\n"
       "    </table>\n"))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       body
       "  </section>\n"))

;; --- individual sections ---------------------------------------------

(defn- terminal-section [db]
  (let [ledger (vec (store/ledger db))]
    (section
     "Terminal directory"
     (str "Every row is a record from <code>satcom.store/demo-data</code> read back through "
          "<code>store/all-terminals</code> after the run — ids, holder names, GMSS numbers "
          "and jurisdictions are seed data, not written by the renderer.")
     (table ["Terminal" "Holder" "Satellite number" "Jurisdiction"
             "Spec-basis on file?" "ITU dispute unresolved?" "Actuation state" "Last ledger fact"]
            (for [{:keys [id holder-name satellite-number jurisdiction
                          coordination-dispute-unresolved?] :as t}
                  (store/all-terminals db)]
              (row (kw id)
                   (esc holder-name)
                   (str "<span class=\"num\">" (esc satellite-number) "</span>")
                   (esc jurisdiction)
                   (yn (some? (facts/spec-basis jurisdiction))
                       "ok" "critical" "yes" "NO — not in satcom.facts")
                   (yn coordination-dispute-unresolved?
                       "critical" "muted" "yes" "no")
                   (lifecycle-cell t)
                   (status-cell ledger id)))))))

(defn- jurisdiction-section [db]
  (let [seed-js (distinct (map :jurisdiction (store/all-terminals db)))
        cov (facts/coverage seed-js)]
    (section
     "Jurisdiction spec-basis catalog"
     (str "The G2 citation table the governor checks every identity proposal against "
          "(<code>satcom.facts/catalog</code>). Coverage over the jurisdictions actually "
          "present in the seeded directory, reported by <code>satcom.facts/coverage</code>: "
          "<strong>" (:covered cov) " of " (:requested cov) "</strong> covered — missing "
          "<code>" (esc (str/join ", " (:missing-jurisdictions cov))) "</code>. "
          "A jurisdiction absent from this table has NO spec-basis; the advisor may not "
          "invent one and the governor holds if it tries.")
     (table ["ISO3" "Jurisdiction" "Notifying authority / regulator" "Legal basis"
             "Required evidence" "In this directory?"]
            (for [iso3 (sort (keys facts/catalog))
                  :let [{:keys [name owner-authority legal-basis required-evidence]}
                        (facts/spec-basis iso3)]]
              (row (esc iso3)
                   (esc name)
                   (esc owner-authority)
                   (esc legal-basis)
                   (str "<span class=\"num\">" (count required-evidence) "</span> — "
                        (esc (str/join " / " required-evidence)))
                   (yn (boolean (some #{iso3} seed-js)) "ok" "muted" "yes" "—")))))))

(defn- hard-hold-section [holds]
  (section
   (str "HARD governor holds — " (count holds) " fired, "
        (count (distinct (map :rule holds))) " distinct rules")
   (str "Every row is a <code>:governor-hold</code> fact the Satellite Network Governor "
        "actually wrote to the real ledger during this run, one row per violation. "
        "The <em>Detail</em> column is the governor's own string, not a paraphrase. "
        "HARD holds cannot be overridden by an approver and never reach a human at all.")
   (table ["Rule" "Op" "Terminal" "Holder" "Advisor confidence" "Governor detail"]
          (for [{:keys [rule op subject holder confidence detail]} holds]
            (row (str "<span class=\"critical\">" (esc (clojure.core/name rule)) "</span>")
                 (kw op)
                 (kw subject)
                 (esc (or holder "—"))
                 (str "<span class=\"num\">" (esc confidence) "</span>")
                 (esc detail))))))

(defn- approval-section [attrib]
  (let [any-dropped? (some #(and (= :approved (:decision %)) (nil? (:store-approver %))) attrib)]
    (section
     "Human approvals — and where the approver identity survives"
     (str "Escalated operations paused at <code>:request-approval</code> and were decided by a "
          "human satellite operator. The <em>Approver (audit)</em> column is read from the "
          "graph's <code>:audit</code> channel; the <em>Approver (store)</em> column is derived "
          "at render time by probing the record the store actually kept for that effect "
          "(<code>satcom.render-html/approver-in</code>), so it reports this store's real "
          "behaviour rather than a hardcoded claim about it."
          (if any-dropped?
            (str " <strong>Observed in this run:</strong> the approver survives into the SSoT "
                 "for register-style effects (which commit <code>:payload</code>) but NOT for "
                 "the two actuations, whose records are drafted by <code>satcom.registry</code> "
                 "and carry no approver field. Those rows are marked "
                 "<em>audit only — not retained in record</em>: the operation WAS approved, and "
                 "the approval is in the audit trail, but a later reader of the store alone "
                 "cannot name the approver.")
            " <strong>Observed in this run:</strong> every approver was retained in the store."))
     (table ["Op" "Terminal" "SSoT effect" "Decision" "Approver (audit)" "Approver (store)"]
            (for [{:keys [op subject effect decision audit-approver store-approver
                          store-reachable?]} attrib]
              (row (kw op)
                   (kw subject)
                   (kw effect)
                   (if (= :approved decision)
                     "<span class=\"ok\">approved</span>"
                     "<span class=\"warn\">rejected</span>")
                   (if audit-approver
                     (str "<span class=\"ok\">" (esc audit-approver) "</span>")
                     "<span class=\"muted\">—</span>")
                   (cond
                     store-approver (str "<span class=\"ok\">" (esc store-approver) "</span>")
                     (= :rejected decision)
                     "<span class=\"muted\">n/a — nothing was committed</span>"
                     (not store-reachable?)
                     "<span class=\"muted\">n/a — no record written</span>"
                     :else
                     "<span class=\"warn\">audit only — not retained in record</span>"))))
     )))

(defn- ledger-section [db]
  (let [ledger (vec (store/ledger db))]
    (section
     (str "Audit ledger — " (count ledger) " facts")
     (str "The append-only decision log, in commit order, exactly as "
          "<code>store/ledger</code> returns it. Commits and holds only: "
          "<code>:approval-granted</code> lives in the graph's audit channel, which is why "
          "the approvals table above has to join two sources.")
     (table ["#" "Fact" "Op" "Terminal" "Basis" "Summary / detail"]
            (map-indexed
             (fn [i {:keys [t op subject basis summary violations]}]
               (row (str "<span class=\"num\">" (inc i) "</span>")
                    (case t
                      :committed "<span class=\"ok\">committed</span>"
                      :governor-hold "<span class=\"critical\">governor-hold</span>"
                      :approval-rejected "<span class=\"warn\">approval-rejected</span>"
                      (esc (clojure.core/name t)))
                    (kw op)
                    (kw subject)
                    (esc (str/join ", " (map #(if (keyword? %) (clojure.core/name %) (str %))
                                             basis)))
                    (esc (or summary
                             (str/join " / " (map :detail violations))
                             ""))))
             ledger)))))

(defn- registry-section [db]
  (let [provs (store/provisioning-history db)
        susps (store/suspension-history db)]
    (section
     "Draft registry records"
     (str "The book-of-record drafts <code>satcom.registry</code> constructed for the two "
          "actuations that committed. Reference numbers are jurisdiction-scoped sequence "
          "numbers assigned by this actor — this repo does not invent a check-digit standard "
          "that does not exist internationally. Every certificate is unsigned: signing is the "
          "operator's own act, not this actor's.")
     (table ["Kind" "Record id" "Terminal" "Jurisdiction" "Immutable"]
            (for [r (concat provs susps)]
              (row (esc (get r "kind"))
                   (str "<span class=\"num\">" (esc (get r "record_id")) "</span>")
                   (kw (get r "terminal_id"))
                   (esc (get r "jurisdiction"))
                   (yn (get r "immutable") "ok" "muted" "true" "false")))))))

(defn- op-list
  "`:a/b, :c/d` as escaped inline code, in a stable order."
  [ops]
  (str/join ", " (map #(str "<code>" (esc %) "</code>") (sort ops))))

(defn- phase-findings
  "What the probe ACTUALLY measured about auto-commit, as a sentence.

  Derived on purpose. The hardcoded predecessor of this paragraph
  asserted that `:actuation/provision-capacity` escalates even at the
  most permissive phase and that no phase ever auto-commits a
  real-world act. Both happen to be true today — but they are claims
  about `satcom.phase`'s tables, and the moment someone adds an
  actuation to a phase's `:auto` set the table below would say
  `auto-commit` while the prose above it still denied it. This reads
  the answer off the same runs the table is rendered from, so the two
  cannot disagree."
  [probe]
  (let [max-phase (apply max (map :phase probe))
        top (filter #(= max-phase (:phase %)) probe)
        top-label (:label (first top))
        auto-at-top (map :op (filter #(= :commit (:disposition %)) top))
        ever-auto (set (map :op (filter #(= :commit (:disposition %)) probe)))
        never-auto (remove ever-auto (distinct (map :op probe)))]
    (str "<strong>Measured across these runs:</strong> at the most permissive phase this "
         "actor has (<span class=\"num\">" (esc max-phase) "</span>, " (esc top-label) ") "
         (if (seq auto-at-top)
           (str "only " (op-list auto-at-top) " auto-commits")
           "no op auto-commits at all")
         ". "
         (if (seq never-auto)
           (str (op-list never-auto) " reached no auto-commit at ANY phase measured — "
                "each either escalated to a human or was held.")
           "Every op measured auto-committed at some phase."))))

(defn- phase-section [probe]
  (section
   (str "Rollout phase gate — " (count probe) " measured runs")
   (str "Every op in <code>satcom.phase/write-ops</code> against every declared phase: one "
        "freshly seeded store per cell, set up with an approved identity verification so "
        "the evidence gate is satisfied, then a single op run under a context pinned to that "
        "phase. These dispositions are MEASURED, not read off <code>satcom.phase</code>'s "
        "tables. "
        (phase-findings probe))
   (table ["Phase" "Label" "Op" "Disposition" "Reason"]
          (for [{:keys [phase label op disposition reason]} probe]
            (row (str "<span class=\"num\">" phase "</span>")
                 (esc label)
                 (kw op)
                 (case disposition
                   :commit "<span class=\"ok\">auto-commit</span>"
                   :escalate "<span class=\"warn\">escalate to human</span>"
                   :hold "<span class=\"critical\">hold</span>"
                   (esc (str disposition)))
                 (if reason
                   (str "<code>" (esc (if (keyword? reason) (clojure.core/name reason) reason))
                        "</code>")
                   "<span class=\"muted\">—</span>"))))))

(defn render
  "Renders the whole document from a completed run."
  [{:keys [db] :as result} probe]
  (let [holds (hard-holds db)
        attrib (approval-attribution result)]
    (str
     "<!doctype html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-6130 · Community Satellite Telecommunications Operations — Operator Console</title>\n"
     "<style>\n" (skin/dds+skin) "\n</style></head>\n<body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Satellite telecommunications activities (ISIC 6130) — Operator Console</h1>\n"
     "</header>\n"
     "<p><span class=\"badge\">read-only sample</span> "
     "<span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">capacity provisioning &amp; service suspension always human-approved</span></p>\n"
     "<p class=\"subtitle\">Generated at build time by <code>satcom.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) from a real run of this repo's own actor: "
     "<code>satcom.operation</code> (langgraph-clj StateGraph) → <code>satcom.governor</code> "
     "(Satellite Network Governor) → <code>satcom.store</code> (SSoT + append-only ledger). "
     "The generator throws rather than write this page if the governor produced no HARD hold, "
     "so a green page cannot mean an unexercised compliance layer. No timestamps: two runs "
     "against the same seed are byte-identical.</p>\n"
     "<main>\n"
     (terminal-section db)
     (jurisdiction-section db)
     (hard-hold-section holds)
     (approval-section attrib)
     (phase-section probe)
     (ledger-section db)
     (registry-section db)
     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-6130 · AGPL-3.0-or-later · "
     "This actor drafts records; it does not command any real ground station, gateway or "
     "satellite payload. Ground-station dispatch, transponder telemetry and lawful-intercept "
     "are out of scope by construction — there is no op for them in "
     "<code>satcom.satcomadvisor</code>, <code>satcom.governor</code> or "
     "<code>satcom.phase</code>.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ----------------------------- entry point -----------------------------

(defn- assert-hard-holds!
  "Build-time invariant, not a comment: refuse to publish a console
  whose compliance layer did not actually fire."
  [holds html]
  (when (empty? holds)
    (throw (ex-info
            (str "REFUSING to write operator-console.html: the real governor produced ZERO "
                 "HARD :governor-hold facts. A demo of a compliance layer that never held "
                 "anything is theatre; fix the scenario or the governor, not this check.")
            {:hard-holds 0})))
  (let [missing (remove #(str/includes? html (name %)) (distinct (map :rule holds)))]
    (when (seq missing)
      (throw (ex-info
              (str "REFUSING to write operator-console.html: rules fired in the real run but "
                   "are absent from the rendered document: " (pr-str (vec missing))
                   ". The page and the run must agree.")
              {:missing-rules (vec missing)})))))

(defn- assert-action-gate-complete!
  "Build-time invariant: the published action gate must cover EVERY op
  the actor can write with, at every declared phase.

  This exists because the probe previously named its ops in a literal
  vector and quietly omitted `:actuation/suspend-service` -- half of
  this actor's real-world actuation surface was missing from the page
  while the page still read as a complete gate. An op that is never
  measured must not be able to disappear silently again."
  [probe]
  (let [measured (set (map :op probe))
        missing (remove measured phase/write-ops)
        expected (* (count phase/write-ops) (count phase/phases))]
    (when (seq missing)
      (throw (ex-info
              (str "REFUSING to write operator-console.html: these ops are declared in "
                   "satcom.phase/write-ops but were never measured by the phase probe: "
                   (pr-str (vec (sort missing)))
                   ". An unmeasured op must not be published as a gated one.")
              {:unmeasured-ops (vec (sort missing))})))
    (when (not= expected (count probe))
      (throw (ex-info
              (str "REFUSING to write operator-console.html: expected " expected
                   " probe cells (" (count phase/write-ops) " write-ops x "
                   (count phase/phases) " phases) but got " (count probe) ".")
              {:expected expected :actual (count probe)})))))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        probe (phase-probe!)
        holds (hard-holds (:db result))
        html (render result probe)]
    (assert-hard-holds! holds html)
    (assert-action-gate-complete! probe)
    (.mkdirs (.getParentFile (java.io.File. ^String out)))
    (spit out html)
    (println "wrote" out
             (str "(" (count (store/ledger (:db result))) " ledger facts, "
                  (count holds) " HARD holds across "
                  (count (distinct (map :rule holds))) " distinct rules, "
                  (count (approval-attribution result)) " human decisions, "
                  (count probe) " phase-gate runs, "
                  (count html) " chars)"))))
