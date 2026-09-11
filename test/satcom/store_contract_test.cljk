(ns satcom.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `cloud-itonami-isic-6190`'s
  `telecom.store-contract-test` and `cloud-itonami-isic-6120`'s
  `wirelesstelecom.store-contract-test` for the same pattern on sibling
  actors."
  (:require [clojure.test :refer [deftest is testing]]
            [satcom.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Sakura Remote Clinic VSAT" (:holder-name (store/terminal s "term-1"))))
      (is (= "JPN" (:jurisdiction (store/terminal s "term-1"))))
      (is (= "+870123456789" (:satellite-number (store/terminal s "term-1"))))
      (is (false? (:coordination-dispute-unresolved? (store/terminal s "term-1"))))
      (is (= "0312345678" (:satellite-number (store/terminal s "term-3"))))
      (is (true? (:coordination-dispute-unresolved? (store/terminal s "term-4"))))
      (is (false? (:capacity-provisioned? (store/terminal s "term-1"))))
      (is (false? (:service-suspended? (store/terminal s "term-1"))))
      (is (= ["term-1" "term-2" "term-3" "term-4"]
             (mapv :id (store/all-terminals s))))
      (is (nil? (store/coordination-screen-of s "term-1")))
      (is (nil? (store/identity-verification-of s "term-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/provisioning-history s)))
      (is (= [] (store/suspension-history s)))
      (is (zero? (store/next-provisioning-sequence s "JPN")))
      (is (zero? (store/next-suspension-sequence s "JPN")))
      (is (false? (store/terminal-already-provisioned? s "term-1")))
      (is (false? (store/terminal-already-suspended? s "term-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :terminal/upsert
                                 :value {:id "term-1" :holder-name "Sakura Remote Clinic VSAT"}})
        (is (= "Sakura Remote Clinic VSAT" (:holder-name (store/terminal s "term-1"))))
        (is (= "+870123456789" (:satellite-number (store/terminal s "term-1"))) "unrelated field preserved"))
      (testing "verification / coordination-screen payloads commit and read back"
        (store/commit-record! s {:effect :verification/set :path ["term-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/identity-verification-of s "term-1")))
        (store/commit-record! s {:effect :coordination-screen/set :path ["term-1"]
                                 :payload {:terminal-id "term-1" :verdict :resolved}})
        (is (= {:terminal-id "term-1" :verdict :resolved} (store/coordination-screen-of s "term-1"))))
      (testing "capacity provisioning drafts a record and advances the sequence"
        (store/commit-record! s {:effect :terminal/mark-provisioned :path ["term-1"]})
        (is (= "JPN-CAP-000000" (get (first (store/provisioning-history s)) "record_id")))
        (is (= "capacity-provisioning-draft" (get (first (store/provisioning-history s)) "kind")))
        (is (true? (:capacity-provisioned? (store/terminal s "term-1"))))
        (is (= 1 (count (store/provisioning-history s))))
        (is (= 1 (store/next-provisioning-sequence s "JPN")))
        (is (true? (store/terminal-already-provisioned? s "term-1")))
        (is (false? (store/terminal-already-provisioned? s "term-2"))))
      (testing "service suspension drafts a record and advances the sequence"
        (store/commit-record! s {:effect :terminal/mark-suspended :path ["term-1"]})
        (is (= "JPN-SUS-000000" (get (first (store/suspension-history s)) "record_id")))
        (is (= "service-suspension-draft" (get (first (store/suspension-history s)) "kind")))
        (is (true? (:service-suspended? (store/terminal s "term-1"))))
        (is (= 1 (count (store/suspension-history s))))
        (is (= 1 (store/next-suspension-sequence s "JPN")))
        (is (true? (store/terminal-already-suspended? s "term-1")))
        (is (false? (store/terminal-already-suspended? s "term-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/terminal s "nope")))
    (is (= [] (store/all-terminals s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/provisioning-history s)))
    (is (= [] (store/suspension-history s)))
    (is (zero? (store/next-provisioning-sequence s "JPN")))
    (is (zero? (store/next-suspension-sequence s "JPN")))
    (store/with-terminals s {"x" {:id "x" :holder-name "n" :satellite-number "+870123456789"
                                  :coordination-dispute-unresolved? false
                                  :capacity-provisioned? false :service-suspended? false
                                  :jurisdiction "JPN" :status :intake}})
    (is (= "n" (:holder-name (store/terminal s "x"))))))
