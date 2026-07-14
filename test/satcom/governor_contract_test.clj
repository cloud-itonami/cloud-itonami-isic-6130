(ns satcom.governor-contract-test
  "The governor contract as executable tests -- the satellite-operator
  analog of `cloud-itonami-isic-6190`'s `telecom.governor-contract-
  test` and `cloud-itonami-isic-6120`'s `wirelesstelecom.governor-
  contract-test`. The single invariant under test:

    Satellite Operations Advisor never provisions capacity or
    suspends service the Satellite Network Governor would reject,
    `:actuation/provision-capacity`/`:actuation/suspend-service`
    NEVER auto-commit at any phase, `:terminal/intake` (no direct
    capital risk) MAY auto-commit when clean, and every decision
    (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [satcom.store :as store]
            [satcom.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :satellite-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- verify!
  "Walks `subject` through verify -> approve, leaving an identity
  verification on file. Uses distinct thread-ids per call site by
  suffixing `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-verify") {:op :identity/verify :subject subject} operator)
  (approve! actor (str tid-prefix "-verify")))

(defn- screen!
  "Walks `subject` through ITU-coordination-dispute screening ->
  approve, leaving a screening on file. Only safe to call for a
  terminal whose dispute status has already resolved -- an unresolved
  dispute HARD-holds the screen itself (see
  `coordination-dispute-is-held-and-unoverridable`)."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-screen") {:op :coordination/screen :subject subject} operator)
  (approve! actor (str tid-prefix "-screen")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :terminal/intake :subject "term-1"
                   :patch {:id "term-1" :holder-name "Sakura Remote Clinic VSAT"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Sakura Remote Clinic VSAT" (:holder-name (store/terminal db "term-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest identity-verify-always-needs-approval
  (testing "verify is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :identity/verify :subject "term-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/identity-verification-of db "term-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "an identity/verify proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :identity/verify :subject "term-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/identity-verification-of db "term-1")) "no verification written"))))

(deftest provision-capacity-without-verification-is-held
  (testing "actuation/provision-capacity before any identity verification -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :actuation/provision-capacity :subject "term-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest satellite-number-format-invalid-is-held
  (testing "a terminal whose own recorded satellite number is malformed -> HOLD"
    (let [[db actor] (fresh)
          _ (verify! actor "t5pre" "term-3")
          res (exec-op actor "t5" {:op :actuation/provision-capacity :subject "term-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:satellite-number-format-invalid} (-> (store/ledger db) last :basis)))
      (is (empty? (store/provisioning-history db))))))

(deftest coordination-dispute-is-held-and-unoverridable
  (testing "an unresolved ITU-coordination dispute on a terminal -> HOLD, and never reaches request-approval -- exercised via :coordination/screen DIRECTLY, not via the actuation op against an unscreened terminal (see this actor's governor ns docstring / telecom's [6190] and wirelesstelecom's [6120] own ADR-0001s)"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :coordination/screen :subject "term-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:coordination-dispute-unresolved} (-> (store/ledger db) first :basis)))
      (is (nil? (store/coordination-screen-of db "term-4")) "no clearance written"))))

(deftest provision-capacity-always-escalates-then-human-decides
  (testing "a clean, fully-verified, well-formed terminal still ALWAYS interrupts for human approval -- actuation/provision-capacity is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t7pre" "term-1")
          r1 (exec-op actor "t7" {:op :actuation/provision-capacity :subject "term-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, provisioning record drafted"
        (let [r2 (approve! actor "t7")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:capacity-provisioned? (store/terminal db "term-1"))))
          (is (= 1 (count (store/provisioning-history db))) "one draft provisioning record"))))))

(deftest suspend-service-always-escalates-then-human-decides
  (testing "a clean, fully-verified, resolved-dispute terminal still ALWAYS interrupts for human approval -- actuation/suspend-service is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t8pre" "term-1")
          _ (screen! actor "t8pre2" "term-1")
          r1 (exec-op actor "t8" {:op :actuation/suspend-service :subject "term-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, suspension record drafted"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:service-suspended? (store/terminal db "term-1"))))
          (is (= 1 (count (store/suspension-history db))) "one draft suspension record"))))))

(deftest provision-capacity-double-provisioning-is-held
  (testing "provisioning the same terminal's capacity twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t9pre" "term-1")
          _ (exec-op actor "t9a" {:op :actuation/provision-capacity :subject "term-1"} operator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :actuation/provision-capacity :subject "term-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-provisioned} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/provisioning-history db))) "still only the one earlier provisioning"))))

(deftest suspend-service-double-suspension-is-held
  (testing "suspending the same terminal's service twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t10pre" "term-1")
          _ (screen! actor "t10pre2" "term-1")
          _ (exec-op actor "t10a" {:op :actuation/suspend-service :subject "term-1"} operator)
          _ (approve! actor "t10a")
          res (exec-op actor "t10" {:op :actuation/suspend-service :subject "term-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-suspended} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/suspension-history db))) "still only the one earlier suspension"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :terminal/intake :subject "term-1"
                          :patch {:id "term-1" :holder-name "Sakura Remote Clinic VSAT"}} operator)
      (exec-op actor "b" {:op :identity/verify :subject "term-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
