(ns satcom.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean terminal through
  intake -> identity verification -> ITU-coordination-dispute
  screening -> capacity-provisioning proposal (always escalates) ->
  human approval -> commit, then through service-suspension proposal
  (always escalates) -> human approval -> commit, then shows five HARD
  holds (a jurisdiction with no spec-basis, a malformed satellite
  number, an unresolved ITU-coordination dispute screened directly via
  `:coordination/screen` [never via an actuation op against an
  unscreened terminal -- see this actor's own governor ns docstring /
  the lesson `telecom`'s (`6190`) and `wirelesstelecom`'s (`6120`) own
  ADR-0001s already recorded], and a double capacity-provisioning/
  service-suspension of an already-processed terminal) that never
  reach a human at all, and prints the audit ledger + the draft
  capacity-provisioning and service-suspension records."
  (:require [langgraph.graph :as g]
            [satcom.store :as store]
            [satcom.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :satellite-operator :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== terminal/intake term-1 (JPN, clean; valid satellite number, no coordination dispute) ==")
    (println (exec! actor "t1" {:op :terminal/intake :subject "term-1"
                                :patch {:id "term-1" :holder-name "Sakura Remote Clinic VSAT"}} operator))

    (println "== identity/verify term-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :identity/verify :subject "term-1"} operator))
    (println (approve! actor "t2"))

    (println "== coordination/screen term-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :coordination/screen :subject "term-1"} operator))
    (println (approve! actor "t3"))

    (println "== actuation/provision-capacity term-1 (always escalates -- actuation/provision-capacity) ==")
    (let [r (exec! actor "t4" {:op :actuation/provision-capacity :subject "term-1"} operator)]
      (println r)
      (println "-- human satellite operator approves --")
      (println (approve! actor "t4")))

    (println "== actuation/suspend-service term-1 (always escalates -- actuation/suspend-service) ==")
    (let [r (exec! actor "t5" {:op :actuation/suspend-service :subject "term-1"} operator)]
      (println r)
      (println "-- human satellite operator approves --")
      (println (approve! actor "t5")))

    (println "== identity/verify term-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t6" {:op :identity/verify :subject "term-2" :no-spec? true} operator))

    (println "== identity/verify term-3 (escalates -- human approves; sets up the malformed-number test) ==")
    (println (exec! actor "t7" {:op :identity/verify :subject "term-3"} operator))
    (println (approve! actor "t7"))

    (println "== actuation/provision-capacity term-3 (\"0312345678\" is not valid satellite-number format -> HARD hold) ==")
    (println (exec! actor "t8" {:op :actuation/provision-capacity :subject "term-3"} operator))

    (println "== coordination/screen term-4 (unresolved -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t9" {:op :coordination/screen :subject "term-4"} operator))

    (println "== actuation/provision-capacity term-1 AGAIN (double-provisioning -> HARD hold) ==")
    (println (exec! actor "t10" {:op :actuation/provision-capacity :subject "term-1"} operator))

    (println "== actuation/suspend-service term-1 AGAIN (double-suspension -> HARD hold) ==")
    (println (exec! actor "t11" {:op :actuation/suspend-service :subject "term-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft capacity-provisioning records ==")
    (doseq [r (store/provisioning-history db)] (println r))

    (println "== draft service-suspension records ==")
    (doseq [r (store/suspension-history db)] (println r))))
