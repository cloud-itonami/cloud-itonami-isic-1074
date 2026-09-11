(ns pastaops.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [pastaops.governor :as governor]))

;; ──────────────────────── Hard Violations ──────────────────────

(deftest spec-basis-violation-test
  (testing "proposal with no jurisdiction citation is a hard violation"
    (let [req {:op :log-production-batch :subject "batch-001"}
          prop {:cites [] :value {:jurisdiction nil}}
          result (governor/check req {:actor-id "gov-1"} prop {})]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :no-spec-basis) (:violations result)))))

  (testing "proposal with proper citation passes spec basis check"
    (let [batch-id "batch-001"
          store {:batches {batch-id
                           {:product-type :macaroni/elbow
                            :drying-temp-c 85
                            :drying-time-minutes 250
                            :moisture-percent 12.0
                            :jurisdiction :jp/prefectural
                            :evidence-checklist [:formulation-record :extrusion-log :drying-log
                                                 :moisture-test :allergen-declaration :weight-check]}}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "CODEX-STAN-249-2006"}] :value {:jurisdiction :jp/prefectural}}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result))))))

;; ──────────────────────── Drying Temperature Violations ──────────────────────

(deftest drying-temp-violation-test
  (testing "batch with drying temp out of range triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id
                           {:product-type :macaroni/elbow
                            :drying-temp-c 70
                            :evidence-checklist [:formulation-record :extrusion-log :drying-log
                                                 :moisture-test :allergen-declaration :weight-check]
                            :jurisdiction :jp/prefectural}}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "CODEX-STAN-249-2006"}] :value {:jurisdiction :jp/prefectural} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :drying-temp-out-of-range) (:violations result)))))

  (testing "batch with drying temp in range passes"
    (let [batch-id "batch-001"
          store {:batches {batch-id
                           {:product-type :macaroni/elbow
                            :drying-temp-c 85
                            :evidence-checklist [:formulation-record :extrusion-log :drying-log
                                                 :moisture-test :allergen-declaration :weight-check]
                            :jurisdiction :jp/prefectural}}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "CODEX-STAN-249-2006"}] :value {:jurisdiction :jp/prefectural} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result))))))

;; ──────────────────────── Moisture Violations (Mold-Growth Risk) ──────────────────────

(deftest moisture-violation-test
  (testing "batch with moisture out of range triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id
                           {:product-type :macaroni/elbow
                            :drying-temp-c 85
                            :drying-time-minutes 250
                            :moisture-percent 14.0
                            :evidence-checklist [:formulation-record :extrusion-log :drying-log
                                                 :moisture-test :allergen-declaration :weight-check]
                            :jurisdiction :jp/prefectural}}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "CODEX-STAN-249-2006"}] :value {:jurisdiction :jp/prefectural} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :moisture-out-of-target) (:violations result))))))

;; ──────────────────────── Allergen Labeling Violations ──────────────────────

(deftest allergen-label-violation-test
  (testing "batch with undeclared egg allergen triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id
                           {:product-type :noodle/egg
                            :drying-temp-c 55
                            :drying-time-minutes 300
                            :moisture-percent 11.0
                            :ingredients [:semolina/durum :egg/whole :egg/yolk]
                            :declared-allergens #{:wheat}
                            :sanitation-score 85
                            :evidence-checklist [:formulation-record :extrusion-log :drying-log
                                                 :moisture-test :allergen-declaration :weight-check]
                            :jurisdiction :jp/prefectural}}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "CODEX-STAN-249-2006"}] :value {:jurisdiction :jp/prefectural} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :allergen-label-mismatch) (:violations result))))))

;; ──────────────────────── Escalation (Low Confidence) ──────────────────────

(deftest low-confidence-escalation-test
  (testing "low confidence proposal escalates even when hard checks pass"
    (let [batch-id "batch-001"
          store {:batches {batch-id
                           {:product-type :macaroni/elbow
                            :drying-temp-c 85
                            :drying-time-minutes 250
                            :moisture-percent 12.0
                            :ingredients [:semolina/durum]
                            :declared-allergens #{:wheat}
                            :sanitation-score 85
                            :evidence-checklist [:formulation-record :extrusion-log :drying-log
                                                 :moisture-test :allergen-declaration :weight-check]
                            :jurisdiction :jp/prefectural}}}
          req {:op :schedule-maintenance :subject batch-id}
          prop {:cites [{:spec "CODEX-STAN-249-2006"}] :value {:jurisdiction :jp/prefectural} :confidence 0.5}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── High Stakes Escalation ──────────────────────

(deftest high-stakes-escalation-test
  (testing "log-production-batch escalates even when all checks pass"
    (let [batch-id "batch-001"
          store {:batches {batch-id
                           {:product-type :macaroni/elbow
                            :drying-temp-c 85
                            :drying-time-minutes 250
                            :moisture-percent 12.0
                            :ingredients [:semolina/durum]
                            :declared-allergens #{:wheat}
                            :sanitation-score 85
                            :evidence-checklist [:formulation-record :extrusion-log :drying-log
                                                 :moisture-test :allergen-declaration :weight-check]
                            :jurisdiction :jp/prefectural}}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "CODEX-STAN-249-2006"}] :value {:jurisdiction :jp/prefectural} :confidence 0.95}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── Already Processed Violation ──────────────────────

(deftest already-processed-violation-test
  (testing "batch already processed triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id
                           {:product-type :macaroni/elbow
                            :processed? true}}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "CODEX-STAN-249-2006"}] :value {:jurisdiction :jp/prefectural} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :already-processed) (:violations result))))))

;; ──────────────────────── Food-Safety Flag Escalation ──────────────────────

(deftest food-safety-flag-unresolved-test
  (testing "unresolved food-safety flag triggers hard violation on log-production-batch"
    (let [batch-id "batch-001"
          store {:batches {batch-id
                           {:product-type :macaroni/elbow
                            :drying-temp-c 85
                            :drying-time-minutes 250
                            :moisture-percent 12.0
                            :ingredients [:semolina/durum]
                            :declared-allergens #{:wheat}
                            :sanitation-score 85
                            :safety-concern-raised? true
                            :safety-concern-resolved? false
                            :evidence-checklist [:formulation-record :extrusion-log :drying-log
                                                 :moisture-test :allergen-declaration :weight-check]
                            :jurisdiction :jp/prefectural}}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "CODEX-STAN-249-2006"}] :value {:jurisdiction :jp/prefectural} :confidence 0.9}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :food-safety-flag-unresolved) (:violations result))))))

;; ──────────────────────── Op Not Allowed ──────────────────────

(deftest op-not-allowed-test
  (testing "an out-of-allowlist op (direct extrusion/drying-line control) is a hard, permanent block"
    (let [req {:op :control-drying-line :subject "batch-001"}
          prop {:cites [{:spec "Dryer-Manual"}] :value {:jurisdiction :jp/prefectural} :confidence 0.99 :effect :propose}
          result (governor/check req {:actor-id "gov-1"} prop {})]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :op-not-allowed) (:violations result))))))
