(ns satcom.registry
  "Pure-function satellite-capacity-provisioning + service-suspension
  record construction -- an append-only satellite-operator
  book-of-record draft.

  Like every sibling actor's registry, there is no single
  international check-digit standard for a capacity-provisioning or
  service-suspension reference number -- every operator/jurisdiction
  assigns its own reference format. This namespace does NOT invent
  one; it builds a jurisdiction-scoped sequence number and validates
  the record's required fields, the same honest, non-fabricating
  discipline `satcom.facts` uses.

  `satellite-number-invalid-format?` is the THIRD application of this
  fleet's format/syntactic-validity check family, first established by
  `telecom.registry/e164-invalid-format?` (`cloud-itonami-isic-6190`)
  and reused a second time by `wirelesstelecom.registry/msisdn-
  invalid-format?` (`cloud-itonami-isic-6120`). A genuine third
  application, not a new family: a Global Mobile Satellite System
  (GMSS) terminal number (e.g. Inmarsat/Iridium-class satellite phone
  numbers under ITU country code +870/+881, per ITU-T Recommendation
  E.164/E.168) IS itself E.164-formatted, so this reuses the SAME
  structural check shape (leading `+`, no leading zero, 8-15 total
  digits) applied here to a genuinely different real-world identifier
  (a satellite terminal's own assigned GMSS number, distinct from a
  fixed-line/VoIP number or a terrestrial mobile MSISDN). It gates
  only `:actuation/provision-capacity` (the point where a malformed
  satellite number would otherwise get provisioned for real use), the
  same restricted-scope placement `telecom.governor`'s/
  `wirelesstelecom.governor`'s own format checks use.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real satellite ground station, gateway, or NOC. It
  builds the RECORD an operator would keep, not the act of
  provisioning the capacity or suspending the service itself (that is
  `satcom.operation`'s `:actuation/provision-capacity`/`:actuation/
  suspend-service`, always human-gated -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  operator's own act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn satellite-number-invalid-format?
  "Is `terminal`'s own recorded `:satellite-number` NOT a syntactically
  valid E.164-format GMSS number -- a leading `+`, no leading zero
  after it, and 8-15 total digits? A pure ground-truth check against
  the terminal's own permanent field -- no upstream comparison needed.
  The THIRD application of this fleet's format/syntactic-validity
  check family (see ns docstring)."
  [{:keys [satellite-number]}]
  (or (nil? satellite-number)
      (not (re-matches #"\+[1-9]\d{7,14}" satellite-number))))

(defn register-capacity-provisioning
  "Validate + construct the CAPACITY-PROVISIONING registration DRAFT --
  the operator's own act of activating real transponder/beam capacity
  and a real satellite number for a terminal. Pure function -- does
  not touch any real ground station or satellite payload; it builds
  the RECORD an operator would keep. `satcom.governor` independently
  re-verifies the terminal's own satellite-number format validity and
  identity-verification sufficiency, and blocks a double-provisioning
  for the same terminal, before this is ever allowed to commit."
  [terminal-id jurisdiction sequence]
  (when-not (and terminal-id (not= terminal-id ""))
    (throw (ex-info "capacity-provisioning: terminal_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "capacity-provisioning: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "capacity-provisioning: sequence must be >= 0" {})))
  (let [provisioning-number (str (str/upper-case jurisdiction) "-CAP-" (zero-pad sequence 6))
        record {"record_id" provisioning-number
                "kind" "capacity-provisioning-draft"
                "terminal_id" terminal-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "provisioning_number" provisioning-number
     "certificate" (unsigned-certificate "CapacityProvisioning" provisioning-number provisioning-number)}))

(defn register-service-suspension
  "Validate + construct the SERVICE-SUSPENSION registration DRAFT --
  the operator's own act of suspending a real terminal's satellite
  service. Pure function -- does not touch any real ground station or
  satellite payload; it builds the RECORD an operator would keep.
  `satcom.governor` independently re-verifies the terminal's own ITU-
  coordination-dispute resolution status, and blocks a double-
  suspension for the same terminal, before this is ever allowed to
  commit. Like `wirelesstelecom.registry/register-service-suspension`
  (`cloud-itonami-isic-6120`) and `telecom.registry/register-billing-
  suppression` (`cloud-itonami-isic-6190`), this actuation is a
  NEGATIVE act (withholding ongoing connectivity), not a positive one
  -- see README `Actuation` and this actor's own ADR-0001 for the
  honest framing this makes."
  [terminal-id jurisdiction sequence]
  (when-not (and terminal-id (not= terminal-id ""))
    (throw (ex-info "service-suspension: terminal_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "service-suspension: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "service-suspension: sequence must be >= 0" {})))
  (let [suspension-number (str (str/upper-case jurisdiction) "-SUS-" (zero-pad sequence 6))
        record {"record_id" suspension-number
                "kind" "service-suspension-draft"
                "terminal_id" terminal-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "suspension_number" suspension-number
     "certificate" (unsigned-certificate "ServiceSuspension" suspension-number suspension-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
