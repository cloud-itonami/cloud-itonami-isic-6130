(ns satcom.store
  "SSoT for the satellite-operator actor, behind a `Store` protocol so
  the backend is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/satcom/store_contract_test.clj), which is the whole point: the
  actor, the Satellite Network Governor and the audit ledger never
  know which SSoT they run on.

  Like `telecom.store`'s (`cloud-itonami-isic-6190`) and
  `wirelesstelecom.store`'s (`cloud-itonami-isic-6120`) dual
  provisioning/suspension history, this actor has TWO actuation events
  (provisioning satellite capacity, suspending service) acting on the
  SAME entity (a terminal), each with its OWN history collection,
  sequence counter and dedicated double-actuation-guard boolean
  (`:capacity-provisioned?`/`:service-suspended?`, never a `:status`
  value) -- the same discipline every prior sibling governor's guards
  establish, informed by `cloud-itonami-isic-6492`'s status-lifecycle
  bug (ADR-2607071320).

  The ledger stays append-only on every backend: 'which terminal was
  screened for an unresolved ITU-coordination dispute, which satellite
  number was provisioned, which service was suspended, on what
  jurisdictional basis, approved by whom' is always a query over an
  immutable log -- the audit trail a community trusting a satellite
  operator needs, and the evidence an operator needs if a provisioning
  or suspension decision is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [satcom.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (terminal [s id])
  (all-terminals [s])
  (coordination-screen-of [s terminal-id] "committed ITU-coordination-dispute screening verdict for a terminal, or nil")
  (identity-verification-of [s terminal-id] "committed identity verification, or nil")
  (ledger [s])
  (provisioning-history [s] "the append-only capacity-provisioning history (satcom.registry drafts)")
  (suspension-history [s] "the append-only service-suspension history (satcom.registry drafts)")
  (next-provisioning-sequence [s jurisdiction] "next provisioning-number sequence for a jurisdiction")
  (next-suspension-sequence [s jurisdiction] "next suspension-number sequence for a jurisdiction")
  (terminal-already-provisioned? [s terminal-id] "has this terminal's capacity already been provisioned?")
  (terminal-already-suspended? [s terminal-id] "has this terminal's service already been suspended?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-terminals [s terminals] "replace/seed the terminal directory (map id->terminal)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained terminal set covering both actuation
  lifecycles (provisioning satellite capacity, suspending service) so
  the actor + tests run offline."
  []
  {:terminals
   {"term-1" {:id "term-1" :holder-name "Sakura Remote Clinic VSAT"
              :satellite-number "+870123456789"
              :coordination-dispute-unresolved? false
              :capacity-provisioned? false :service-suspended? false
              :jurisdiction "JPN" :status :intake}
    "term-2" {:id "term-2" :holder-name "Atlantis Research Station"
              :satellite-number "+870123456790"
              :coordination-dispute-unresolved? false
              :capacity-provisioned? false :service-suspended? false
              :jurisdiction "ATL" :status :intake}
    "term-3" {:id "term-3" :holder-name "鈴木海洋観測ブイ"
              :satellite-number "0312345678"
              :coordination-dispute-unresolved? false
              :capacity-provisioned? false :service-suspended? false
              :jurisdiction "JPN" :status :intake}
    "term-4" {:id "term-4" :holder-name "田中離島診療所"
              :satellite-number "+870123456791"
              :coordination-dispute-unresolved? true
              :capacity-provisioned? false :service-suspended? false
              :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- provision-capacity!
  "Backend-agnostic `:terminal/mark-provisioned` -- looks up the
  terminal via the protocol and drafts the capacity-provisioning
  record, and returns {:result .. :terminal-patch ..} for the caller
  to persist."
  [s terminal-id]
  (let [tm (terminal s terminal-id)
        seq-n (next-provisioning-sequence s (:jurisdiction tm))
        result (registry/register-capacity-provisioning terminal-id (:jurisdiction tm) seq-n)]
    {:result result
     :terminal-patch {:capacity-provisioned? true
                      :provisioning-number (get result "provisioning_number")}}))

(defn- suspend-service!
  "Backend-agnostic `:terminal/mark-suspended` -- looks up the terminal
  via the protocol and drafts the service-suspension record, and
  returns {:result .. :terminal-patch ..} for the caller to persist."
  [s terminal-id]
  (let [tm (terminal s terminal-id)
        seq-n (next-suspension-sequence s (:jurisdiction tm))
        result (registry/register-service-suspension terminal-id (:jurisdiction tm) seq-n)]
    {:result result
     :terminal-patch {:service-suspended? true
                      :suspension-number (get result "suspension_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (terminal [_ id] (get-in @a [:terminals id]))
  (all-terminals [_] (sort-by :id (vals (:terminals @a))))
  (coordination-screen-of [_ id] (get-in @a [:coordination-screens id]))
  (identity-verification-of [_ terminal-id] (get-in @a [:verifications terminal-id]))
  (ledger [_] (:ledger @a))
  (provisioning-history [_] (:provisionings @a))
  (suspension-history [_] (:suspensions @a))
  (next-provisioning-sequence [_ jurisdiction] (get-in @a [:provisioning-sequences jurisdiction] 0))
  (next-suspension-sequence [_ jurisdiction] (get-in @a [:suspension-sequences jurisdiction] 0))
  (terminal-already-provisioned? [_ terminal-id] (boolean (get-in @a [:terminals terminal-id :capacity-provisioned?])))
  (terminal-already-suspended? [_ terminal-id] (boolean (get-in @a [:terminals terminal-id :service-suspended?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :terminal/upsert
      (swap! a update-in [:terminals (:id value)] merge value)

      :verification/set
      (swap! a assoc-in [:verifications (first path)] payload)

      :coordination-screen/set
      (swap! a assoc-in [:coordination-screens (first path)] payload)

      :terminal/mark-provisioned
      (let [terminal-id (first path)
            {:keys [result terminal-patch]} (provision-capacity! s terminal-id)
            jurisdiction (:jurisdiction (terminal s terminal-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:provisioning-sequences jurisdiction] (fnil inc 0))
                       (update-in [:terminals terminal-id] merge terminal-patch)
                       (update :provisionings registry/append result))))
        result)

      :terminal/mark-suspended
      (let [terminal-id (first path)
            {:keys [result terminal-patch]} (suspend-service! s terminal-id)
            jurisdiction (:jurisdiction (terminal s terminal-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:suspension-sequences jurisdiction] (fnil inc 0))
                       (update-in [:terminals terminal-id] merge terminal-patch)
                       (update :suspensions registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-terminals [s terminals] (when (seq terminals) (swap! a assoc :terminals terminals)) s))

(defn seed-db
  "A MemStore seeded with the demo terminal set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :verifications {} :coordination-screens {} :ledger [] :provisioning-sequences {}
                           :provisionings [] :suspension-sequences {} :suspensions []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (verification/coordination-screen payloads,
  ledger facts, provisioning/suspension records) are stored as EDN
  strings so `langchain.db` doesn't expand them into sub-entities --
  the same convention every sibling actor's store uses."
  {:terminal/id                          {:db/unique :db.unique/identity}
   :verification/terminal-id             {:db/unique :db.unique/identity}
   :coordination-screen/terminal-id      {:db/unique :db.unique/identity}
   :ledger/seq                           {:db/unique :db.unique/identity}
   :provisioning/seq                     {:db/unique :db.unique/identity}
   :suspension/seq                       {:db/unique :db.unique/identity}
   :provisioning-sequence/jurisdiction   {:db/unique :db.unique/identity}
   :suspension-sequence/jurisdiction     {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- terminal->tx [{:keys [id holder-name satellite-number
                             coordination-dispute-unresolved?
                             capacity-provisioned? service-suspended?
                             jurisdiction status provisioning-number suspension-number]}]
  (cond-> {:terminal/id id}
    holder-name                            (assoc :terminal/holder-name holder-name)
    satellite-number                       (assoc :terminal/satellite-number satellite-number)
    (some? coordination-dispute-unresolved?) (assoc :terminal/coordination-dispute-unresolved? coordination-dispute-unresolved?)
    (some? capacity-provisioned?)          (assoc :terminal/capacity-provisioned? capacity-provisioned?)
    (some? service-suspended?)             (assoc :terminal/service-suspended? service-suspended?)
    jurisdiction                           (assoc :terminal/jurisdiction jurisdiction)
    status                                 (assoc :terminal/status status)
    provisioning-number                    (assoc :terminal/provisioning-number provisioning-number)
    suspension-number                      (assoc :terminal/suspension-number suspension-number)))

(def ^:private terminal-pull
  [:terminal/id :terminal/holder-name :terminal/satellite-number
   :terminal/coordination-dispute-unresolved? :terminal/capacity-provisioned? :terminal/service-suspended?
   :terminal/jurisdiction :terminal/status :terminal/provisioning-number :terminal/suspension-number])

(defn- pull->terminal [m]
  (when (:terminal/id m)
    {:id (:terminal/id m) :holder-name (:terminal/holder-name m)
     :satellite-number (:terminal/satellite-number m)
     :coordination-dispute-unresolved? (boolean (:terminal/coordination-dispute-unresolved? m))
     :capacity-provisioned? (boolean (:terminal/capacity-provisioned? m))
     :service-suspended? (boolean (:terminal/service-suspended? m))
     :jurisdiction (:terminal/jurisdiction m) :status (:terminal/status m)
     :provisioning-number (:terminal/provisioning-number m) :suspension-number (:terminal/suspension-number m)}))

(defrecord DatomicStore [conn]
  Store
  (terminal [_ id]
    (pull->terminal (d/pull (d/db conn) terminal-pull [:terminal/id id])))
  (all-terminals [_]
    (->> (d/q '[:find [?id ...] :where [?e :terminal/id ?id]] (d/db conn))
         (map #(pull->terminal (d/pull (d/db conn) terminal-pull [:terminal/id %])))
         (sort-by :id)))
  (coordination-screen-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?tid
                :where [?k :coordination-screen/terminal-id ?tid] [?k :coordination-screen/payload ?p]]
              (d/db conn) id)))
  (identity-verification-of [_ terminal-id]
    (dec* (d/q '[:find ?p . :in $ ?tid
                :where [?a :verification/terminal-id ?tid] [?a :verification/payload ?p]]
              (d/db conn) terminal-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (provisioning-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :provisioning/seq ?s] [?e :provisioning/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (suspension-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :suspension/seq ?s] [?e :suspension/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-provisioning-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :provisioning-sequence/jurisdiction ?j] [?e :provisioning-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-suspension-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :suspension-sequence/jurisdiction ?j] [?e :suspension-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (terminal-already-provisioned? [s terminal-id]
    (boolean (:capacity-provisioned? (terminal s terminal-id))))
  (terminal-already-suspended? [s terminal-id]
    (boolean (:service-suspended? (terminal s terminal-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :terminal/upsert
      (d/transact! conn [(terminal->tx value)])

      :verification/set
      (d/transact! conn [{:verification/terminal-id (first path) :verification/payload (enc payload)}])

      :coordination-screen/set
      (d/transact! conn [{:coordination-screen/terminal-id (first path) :coordination-screen/payload (enc payload)}])

      :terminal/mark-provisioned
      (let [terminal-id (first path)
            {:keys [result terminal-patch]} (provision-capacity! s terminal-id)
            jurisdiction (:jurisdiction (terminal s terminal-id))
            next-n (inc (next-provisioning-sequence s jurisdiction))]
        (d/transact! conn
                     [(terminal->tx (assoc terminal-patch :id terminal-id))
                      {:provisioning-sequence/jurisdiction jurisdiction :provisioning-sequence/next next-n}
                      {:provisioning/seq (count (provisioning-history s)) :provisioning/record (enc (get result "record"))}])
        result)

      :terminal/mark-suspended
      (let [terminal-id (first path)
            {:keys [result terminal-patch]} (suspend-service! s terminal-id)
            jurisdiction (:jurisdiction (terminal s terminal-id))
            next-n (inc (next-suspension-sequence s jurisdiction))]
        (d/transact! conn
                     [(terminal->tx (assoc terminal-patch :id terminal-id))
                      {:suspension-sequence/jurisdiction jurisdiction :suspension-sequence/next next-n}
                      {:suspension/seq (count (suspension-history s)) :suspension/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-terminals [s terminals]
    (when (seq terminals) (d/transact! conn (mapv terminal->tx (vals terminals)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:terminals ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [terminals]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-terminals s terminals))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo terminal set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
