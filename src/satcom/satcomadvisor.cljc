(ns satcom.satcomadvisor
  "Satellite Operations Advisor client -- the *contained intelligence
  node* for the satellite-operator actor.

  It normalizes terminal-intake, drafts a per-jurisdiction subscriber-
  identity + satellite-licensing evidence checklist, screens terminals
  for an unresolved ITU frequency-coordination/orbital-slot dispute,
  drafts the capacity-provisioning action, and drafts the
  service-suspension action. CRITICAL: it is a smart-but-untrusted
  advisor. It returns a *proposal* (with a rationale + the fields it
  cited), never a committed record or a real capacity provisioning/
  service suspension. Every output is censored downstream by `satcom.
  governor` before anything touches the SSoT, and `:actuation/
  provision-capacity`/`:actuation/suspend-service` proposals NEVER
  auto-commit at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/provision-capacity | :actuation/suspend-service | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [satcom.facts :as facts]
            [satcom.registry :as registry]
            [satcom.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the terminal, satellite-number figures or
  jurisdiction. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "回線記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :terminal/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- verify-identity
  "Per-jurisdiction subscriber-identity + satellite-licensing evidence
  checklist draft. `:no-spec?` injects the failure mode we must defend
  against: proposing a checklist for a jurisdiction with NO official
  spec-basis in `satcom.facts` -- the Satellite Network Governor must
  reject this (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [tm (store/terminal db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction tm))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "satcom.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :verification/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :verification/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-coordination-dispute
  "ITU frequency-coordination/orbital-slot dispute screening draft.
  `:coordination-dispute-unresolved?` on the terminal record injects
  the failure mode: the Satellite Network Governor must HOLD,
  un-overridably, on any unresolved dispute."
  [db {:keys [subject]}]
  (let [tm (store/terminal db subject)]
    (cond
      (nil? tm)
      {:summary "対象回線記録が見つかりません" :rationale "no terminal record"
       :cites [] :effect :coordination-screen/set :value {:terminal-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (true? (:coordination-dispute-unresolved? tm))
      {:summary    (str (:holder-name tm) ": 未解決のITU国際調整・軌道位置紛争を検出")
       :rationale  "スクリーニングが未解決のITU国際調整・軌道位置紛争を検出。人手確認とホールドが必須。"
       :cites      [:coordination-check]
       :effect     :coordination-screen/set
       :value      {:terminal-id subject :verdict :unresolved}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:holder-name tm) ": 未解決のITU国際調整・軌道位置紛争なし")
       :rationale  "ITU国際調整・軌道位置紛争スクリーニング完了。"
       :cites      [:coordination-check]
       :effect     :coordination-screen/set
       :value      {:terminal-id subject :verdict :resolved}
       :stake      nil
       :confidence 0.9})))

(defn- propose-capacity-provisioning
  "Draft the actual CAPACITY-PROVISIONING action -- provisioning real
  transponder/beam capacity and a real satellite number for a
  terminal. ALWAYS `:stake :actuation/provision-capacity` -- this is a
  REAL-WORLD act, never a draft the actor may auto-run. See README
  `Actuation`: no phase ever adds this op to a phase's `:auto` set
  (`satcom.phase`); the governor also always escalates on `:actuation/
  provision-capacity`. Two independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [tm (store/terminal db subject)]
    {:summary    (str subject " 向け衛星回線容量発行提案"
                      (when tm (str " (terminal=" (:holder-name tm) ")")))
     :rationale  (if tm
                   (str "satellite-number=" (:satellite-number tm))
                   "回線記録が見つかりません")
     :cites      (if tm [subject] [])
     :effect     :terminal/mark-provisioned
     :value      {:terminal-id subject}
     :stake      :actuation/provision-capacity
     :confidence (if (and tm (not (registry/satellite-number-invalid-format? tm))) 0.9 0.3)}))

(defn- propose-service-suspension
  "Draft the actual SERVICE-SUSPENSION action -- suspending a real
  terminal's satellite service. ALWAYS `:stake :actuation/suspend-
  service` -- this is a REAL-WORLD act (and, like `wirelesstelecom.
  opsadvisor`'s (`cloud-itonami-isic-6120`) and `telecom.
  telecomadvisor`'s (`cloud-itonami-isic-6190`) own negative
  actuations, a NEGATIVE one -- withholding ongoing connectivity, not
  issuing a new record), never a draft the actor may auto-run. See
  README `Actuation`: no phase ever adds this op to a phase's `:auto`
  set (`satcom.phase`); the governor also always escalates on
  `:actuation/suspend-service`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [tm (store/terminal db subject)]
    {:summary    (str subject " 向け回線停止提案"
                      (when tm (str " (terminal=" (:holder-name tm) ")")))
     :rationale  (if tm
                   "jurisdiction-evidence-checklist referenced"
                   "回線記録が見つかりません")
     :cites      (if tm [subject] [])
     :effect     :terminal/mark-suspended
     :value      {:terminal-id subject}
     :stake      :actuation/suspend-service
     :confidence (if tm 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :terminal/intake                 (normalize-intake db request)
    :identity/verify                 (verify-identity db request)
    :coordination/screen             (screen-coordination-dispute db request)
    :actuation/provision-capacity    (propose-capacity-provisioning db request)
    :actuation/suspend-service       (propose-service-suspension db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは衛星通信事業者の回線容量発行・回線停止エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:terminal/upsert|:verification/set|:coordination-screen/set|"
       ":terminal/mark-provisioned|:terminal/mark-suspended) "
       ":stake(:actuation/provision-capacity か :actuation/suspend-service か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :identity/verify                {:terminal (store/terminal st subject)}
    :coordination/screen            {:terminal (store/terminal st subject)}
    :actuation/provision-capacity   {:terminal (store/terminal st subject)}
    :actuation/suspend-service      {:terminal (store/terminal st subject)}
    {:terminal (store/terminal st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Satellite Network Governor
  escalates/holds -- an LLM hiccup can never auto-provision capacity
  or auto-suspend service."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :satcomadvisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
