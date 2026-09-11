(ns pastaops.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [pastaops.registry :as registry]))

;; ──────────────────────── Drying Temperature Safety ──────────────────────

(deftest drying-temp-out-of-range-test
  (testing "temperature within range returns false (no violation)"
    (is (false? (registry/drying-temp-out-of-range? 85 82 88))))

  (testing "temperature at minimum boundary returns false"
    (is (false? (registry/drying-temp-out-of-range? 82 82 88))))

  (testing "temperature at maximum boundary returns false"
    (is (false? (registry/drying-temp-out-of-range? 88 82 88))))

  (testing "temperature below minimum returns true (violation)"
    (is (true? (registry/drying-temp-out-of-range? 81 82 88))))

  (testing "temperature above maximum returns true (violation)"
    (is (true? (registry/drying-temp-out-of-range? 89 82 88)))))

;; ──────────────────────── Drying Time Safety ──────────────────────

(deftest drying-time-exceeded-test
  (testing "time within limit returns false (no violation)"
    (is (false? (registry/drying-time-exceeded? 250 300))))

  (testing "time at limit returns false"
    (is (false? (registry/drying-time-exceeded? 300 300))))

  (testing "time exceeding limit returns true (violation)"
    (is (true? (registry/drying-time-exceeded? 301 300)))))

;; ──────────────────────── Moisture Target (Mold-Growth Risk) ──────────────────────

(deftest moisture-out-of-target-test
  (testing "moisture at target with no tolerance returns false"
    (is (false? (registry/moisture-out-of-target? 12.0 12.0 0.5))))

  (testing "moisture within tolerance range returns false"
    (is (false? (registry/moisture-out-of-target? 11.7 12.0 0.5))))

  (testing "moisture below tolerance returns true (violation)"
    (is (true? (registry/moisture-out-of-target? 11.0 12.0 0.5))))

  (testing "moisture above tolerance returns true (mold-growth violation)"
    (is (true? (registry/moisture-out-of-target? 13.0 12.0 0.5)))))

;; ──────────────────────── Sanitation Score ──────────────────────

(deftest sanitation-score-insufficient-test
  (testing "score at minimum returns false (no violation)"
    (is (false? (registry/sanitation-score-insufficient? 75 75))))

  (testing "score above minimum returns false"
    (is (false? (registry/sanitation-score-insufficient? 85 75))))

  (testing "score below minimum returns true (violation)"
    (is (true? (registry/sanitation-score-insufficient? 74 75)))))

;; ──────────────────────── Scale Calibration ──────────────────────

(deftest scale-calibration-overdue-test
  (testing "recent calibration returns false (no violation)"
    ;; Assume calibrated 30 days ago
    (let [now #?(:clj (System/currentTimeMillis) :cljs (.now js/Date))
          thirty-days-ago (- now (* 30 24 60 60 1000))]
      (is (false? (registry/scale-calibration-overdue? thirty-days-ago now)))))

  (testing "overdue calibration returns true (violation)"
    (let [now #?(:clj (System/currentTimeMillis) :cljs (.now js/Date))
          one-ninety-days-ago (- now (* 190 24 60 60 1000))]
      (is (true? (registry/scale-calibration-overdue? one-ninety-days-ago now))))))

;; ──────────────────────── Weight Variance ──────────────────────

(deftest weight-variance-excessive-test
  (testing "variance within tolerance returns false (no violation)"
    (is (false? (registry/weight-variance-excessive? 45 50))))

  (testing "variance at tolerance returns false"
    (is (false? (registry/weight-variance-excessive? 50 50))))

  (testing "variance exceeding tolerance returns true (violation)"
    (is (true? (registry/weight-variance-excessive? 51 50)))))

;; ──────────────────────── Allergen Labeling ──────────────────────

(deftest allergen-label-risk-test
  (testing "declared allergens match formulation returns false (no risk)"
    (let [formula #{:wheat :eggs}
          declared #{:wheat :eggs}]
      (is (false? (registry/allergen-label-risk? formula declared)))))

  (testing "declared allergens exceed formulation returns false (conservative)"
    (let [formula #{:wheat}
          declared #{:wheat :eggs}]
      (is (false? (registry/allergen-label-risk? formula declared)))))

  (testing "formulation allergen undeclared returns true (risk)"
    (let [formula #{:wheat :eggs}
          declared #{:wheat}]
      (is (true? (registry/allergen-label-risk? formula declared))))))
