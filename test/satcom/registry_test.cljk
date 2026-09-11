(ns satcom.registry-test
  (:require [clojure.test :refer [deftest is]]
            [satcom.registry :as r]))

;; ----------------------------- satellite-number-invalid-format? -----------------------------

(deftest valid-when-well-formed
  (is (not (r/satellite-number-invalid-format? {:satellite-number "+870123456789"})))
  (is (not (r/satellite-number-invalid-format? {:satellite-number "+881123456789"}))))

(deftest invalid-when-missing-leading-plus-or-leading-zero-or-wrong-length
  (is (r/satellite-number-invalid-format? {:satellite-number "0312345678"}) "no leading +")
  (is (r/satellite-number-invalid-format? {:satellite-number "+0312345678"}) "leading zero after +")
  (is (r/satellite-number-invalid-format? {:satellite-number "+1234567"}) "too short (7 digits)")
  (is (r/satellite-number-invalid-format? {:satellite-number "+1234567890123456"}) "too long (16 digits)"))

(deftest invalid-is-true-on-missing-field
  (is (r/satellite-number-invalid-format? {}))
  (is (r/satellite-number-invalid-format? {:satellite-number nil})))

;; ----------------------------- register-capacity-provisioning -----------------------------

(deftest provisioning-is-a-draft-not-a-real-provisioning
  (let [result (r/register-capacity-provisioning "term-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest provisioning-assigns-provisioning-number
  (let [result (r/register-capacity-provisioning "term-1" "JPN" 7)]
    (is (= (get result "provisioning_number") "JPN-CAP-000007"))
    (is (= (get-in result ["record" "terminal_id"]) "term-1"))
    (is (= (get-in result ["record" "kind"]) "capacity-provisioning-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest provisioning-validation-rules
  (is (thrown? Exception (r/register-capacity-provisioning "" "JPN" 0)))
  (is (thrown? Exception (r/register-capacity-provisioning "term-1" "" 0)))
  (is (thrown? Exception (r/register-capacity-provisioning "term-1" "JPN" -1))))

;; ----------------------------- register-service-suspension -----------------------------

(deftest suspension-is-a-draft-not-a-real-suspension
  (let [result (r/register-service-suspension "term-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest suspension-assigns-suspension-number
  (let [result (r/register-service-suspension "term-1" "JPN" 3)]
    (is (= (get result "suspension_number") "JPN-SUS-000003"))
    (is (= (get-in result ["record" "terminal_id"]) "term-1"))
    (is (= (get-in result ["record" "kind"]) "service-suspension-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest suspension-validation-rules
  (is (thrown? Exception (r/register-service-suspension "" "JPN" 0)))
  (is (thrown? Exception (r/register-service-suspension "term-1" "" 0)))
  (is (thrown? Exception (r/register-service-suspension "term-1" "JPN" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-capacity-provisioning "term-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-capacity-provisioning "term-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-CAP-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-CAP-000001" (get-in hist2 [1 "record_id"])))))
