(ns satcom.facts
  "Per-jurisdiction satellite-operating-license + ITU-coordination
  regulatory catalog -- the G2-style spec-basis table the Satellite
  Network Governor checks every identity/verify proposal against ('did
  the advisor cite an OFFICIAL public source for this jurisdiction's
  satellite-licensing/space-notification authority, or did it invent
  one?').

  This is the SATELLITE-specific analog of `telecom.facts`
  (`cloud-itonami-isic-6190`, wired/VoIP-reseller telecom) and
  `wirelesstelecom.facts` (`cloud-itonami-isic-6120`, terrestrial
  mobile-network-operator telecom): the SAME per-jurisdiction
  spec-basis discipline, but citing the jurisdiction's SATELLITE-
  operating-license statute AND its role as the ITU 'administration'
  responsible for frequency/orbital-slot coordination filings (ITU
  Radio Regulations Article 9/11) -- a genuinely distinct regulatory
  concern from a terrestrial numbering plan (6190) or a terrestrial
  mobile-spectrum license (6120): satellite frequency assignments are
  coordinated INTERNATIONALLY through the ITU Radiocommunication
  Bureau, not solely by a national regulator acting alone.

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official satellite/
  space-services regulator and its ITU Notifying Administration role
  (see `:provenance`); they are a STARTING catalog, not a from-scratch
  survey of all ~194 jurisdictions. Extending coverage is additive: add
  one map to `catalog`, cite a real source, done -- never invent a
  jurisdiction's requirements to make coverage look bigger.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  subscriber-identity-verification-record/satellite-number-assignment-
  record/ITU-coordination-filing-record/service-suspension-log
  evidence set submitted in some form; `:legal-basis` / `:owner-
  authority` / `:provenance` are the G2 citation the governor requires
  before any `:identity/verify` proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "総務省 (MIC, Ministry of Internal Affairs and Communications) -- ITU Notifying Administration for Japan"
          :legal-basis "電波法 (Radio Act) / 電気通信事業法 -- 衛星通信役務の登録・確認"
          :national-spec "衛星通信事業者の周波数・軌道位置国際調整（ITU無線通信規則第9条/第11条）及び契約者確認に関する規律"
          :provenance "https://www.soumu.go.jp/main_sosiki/joho_tsusin/eisei/"
          :required-evidence ["契約者確認記録 (subscriber-identity-verification-record)"
                              "衛星回線番号割当記録 (satellite-number-assignment-record)"
                              "ITU国際調整申請記録 (itu-coordination-filing-record)"
                              "回線停止台帳 (service-suspension-log)"]}
   "USA" {:name "United States"
          :owner-authority "Federal Communications Commission (FCC) International Bureau -- ITU Notifying Administration for the United States"
          :legal-basis "Communications Act of 1934, Title III (47 U.S.C. §301 et seq.) / 47 CFR Part 25 (Satellite Communications)"
          :national-spec "FCC space-station/earth-station licensing and ITU Radio Regulations Article 9/11 coordination-filing requirements for satellite operators"
          :provenance "https://www.fcc.gov/space"
          :required-evidence ["Subscriber-identity-verification record"
                              "Satellite-number-assignment record"
                              "ITU-coordination-filing record"
                              "Service-suspension log"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Office of Communications (Ofcom) -- UK Administration for ITU satellite-network filings"
          :legal-basis "Wireless Telegraphy Act 2006 / Communications Act 2003"
          :national-spec "UK satellite earth-station licensing and ITU Radio Regulations coordination-filing requirements"
          :provenance "https://www.ofcom.org.uk/spectrum/space-and-satellites"
          :required-evidence ["Subscriber-identity-verification record"
                              "Satellite-number-assignment record"
                              "ITU-coordination-filing record"
                              "Service-suspension log"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundesnetzagentur -- German Administration for ITU satellite-network filings"
          :legal-basis "Telekommunikationsgesetz (TKG) -- Frequenzzuteilung für Satellitenfunk"
          :national-spec "Frequenzzuteilung, Erdfunkstellenzulassung und ITU-Koordinierungsanmeldung für Satellitendienste"
          :provenance "https://www.bundesnetzagentur.de/DE/Fachthemen/Telekommunikation/Frequenzen/OeffentlicherMobilfunk/Satellitenfunk/"
          :required-evidence ["Teilnehmeridentitätsprüfungsnachweis (subscriber-identity-verification-record)"
                              "Satelliten-Rufnummernzuteilungsnachweis (satellite-number-assignment-record)"
                              "ITU-Koordinierungsanmeldung (itu-coordination-filing-record)"
                              "Sperrprotokoll (service-suspension-log)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to provision
  satellite capacity or suspend service on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-6130 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `satcom.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
