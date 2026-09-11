(ns pastaops.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [pastaops.facts :as facts]))

;; ──────────────────────── Product Type Lookups ──────────────────────

(deftest product-type-by-id-test
  (testing "macaroni elbow product type exists"
    (let [p (facts/product-type-by-id :macaroni/elbow)]
      (is (some? p))
      (is (= (:id p) :macaroni/elbow))
      (is (= (:drying-temp-c-min p) 82))
      (is (= (:drying-temp-c-max p) 88))))

  (testing "egg noodle product type exists"
    (let [p (facts/product-type-by-id :noodle/egg)]
      (is (some? p))
      (is (= (:drying-temp-c-min p) 50))
      (is (= (:drying-temp-c-max p) 60))))

  (testing "couscous product type exists"
    (let [p (facts/product-type-by-id :couscous/semolina)]
      (is (some? p))
      (is (= (:moisture-target-percent p) 10.0))))

  (testing "nonexistent product type returns nil"
    (is (nil? (facts/product-type-by-id :pasta/nonexistent)))))

;; ──────────────────────── Jurisdiction Lookups ──────────────────────

(deftest jurisdiction-by-id-test
  (testing "JP prefectural jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :jp/prefectural)]
      (is (some? j))
      (is (true? (:allergen-declaration-required j)))
      (is (contains? (:major-allergens j) :wheat))))

  (testing "US FDA jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :us/fda)]
      (is (some? j))
      (is (contains? (:major-allergens j) :eggs))))

  (testing "nonexistent jurisdiction returns nil"
    (is (nil? (facts/jurisdiction-by-id :xx/unknown)))))

;; ──────────────────────── Allergen Lookups ──────────────────────

(deftest ingredient-allergens-test
  (testing "durum semolina has wheat allergen"
    (let [a (facts/ingredient-allergens :semolina/durum)]
      (is (= (:primary-allergen a) :wheat))
      (is (contains? (:cross-contact-risk a) :tree-nuts))))

  (testing "whole egg has eggs allergen"
    (let [a (facts/ingredient-allergens :egg/whole)]
      (is (= (:primary-allergen a) :eggs))))

  (testing "nonexistent ingredient returns nil"
    (is (nil? (facts/ingredient-allergens :unknown/ingredient)))))

;; ──────────────────────── Drying Safety Predicates ──────────────────────

(deftest drying-temp-in-range-test
  (testing "temp within range passes"
    (let [p (facts/product-type-by-id :macaroni/elbow)]
      (is (true? (facts/drying-temp-in-range? 85 p)))))

  (testing "temp below minimum fails"
    (let [p (facts/product-type-by-id :macaroni/elbow)]
      (is (false? (facts/drying-temp-in-range? 70 p)))))

  (testing "temp above maximum fails"
    (let [p (facts/product-type-by-id :macaroni/elbow)]
      (is (false? (facts/drying-temp-in-range? 95 p))))))

(deftest drying-time-in-range-test
  (testing "time within range passes"
    (let [p (facts/product-type-by-id :macaroni/elbow)]
      (is (true? (facts/drying-time-in-range? 250 p)))))

  (testing "time below minimum fails"
    (let [p (facts/product-type-by-id :macaroni/elbow)]
      (is (false? (facts/drying-time-in-range? 100 p)))))

  (testing "time above maximum fails"
    (let [p (facts/product-type-by-id :macaroni/elbow)]
      (is (false? (facts/drying-time-in-range? 400 p))))))

(deftest moisture-in-range-test
  (testing "moisture within tolerance passes"
    (let [p (facts/product-type-by-id :macaroni/elbow)]
      (is (true? (facts/moisture-in-range? 12.0 p)))))

  (testing "moisture at lower tolerance boundary passes"
    (let [p (facts/product-type-by-id :macaroni/elbow)]
      (is (true? (facts/moisture-in-range? 11.5 p)))))

  (testing "moisture below range fails"
    (let [p (facts/product-type-by-id :macaroni/elbow)]
      (is (false? (facts/moisture-in-range? 11.0 p)))))

  (testing "moisture above range fails (mold-growth risk)"
    (let [p (facts/product-type-by-id :macaroni/elbow)]
      (is (false? (facts/moisture-in-range? 13.0 p))))))

;; ──────────────────────── Allergen Traceability ──────────────────────

(deftest formulation-allergen-set-test
  (testing "durum-semolina formulation collects wheat allergen"
    (let [ingredients [:semolina/durum :salt/sea :water/filtered]
          allergens (facts/formulation-allergen-set ingredients)]
      (is (contains? allergens :wheat))))

  (testing "egg-noodle formulation includes multiple allergens"
    (let [ingredients [:semolina/durum :egg/whole :egg/yolk]
          allergens (facts/formulation-allergen-set ingredients)]
      (is (contains? allergens :wheat))
      (is (contains? allergens :eggs))))

  (testing "allergen-free ingredients produce empty set"
    (let [ingredients [:salt/sea :water/filtered]
          allergens (facts/formulation-allergen-set ingredients)]
      (is (empty? allergens)))))

(deftest allergen-declaration-complete-test
  (testing "declaration matches formulation for jurisdiction"
    (let [j (facts/jurisdiction-by-id :jp/prefectural)
          ingredients [:semolina/durum]
          declared #{:wheat}]
      (is (true? (facts/allergen-declaration-complete? j ingredients declared)))))

  (testing "incomplete declaration fails"
    (let [j (facts/jurisdiction-by-id :jp/prefectural)
          ingredients [:semolina/durum :egg/whole]
          declared #{:wheat}]
      (is (false? (facts/allergen-declaration-complete? j ingredients declared)))))

  (testing "extra declarations pass (conservative)"
    (let [j (facts/jurisdiction-by-id :jp/prefectural)
          ingredients [:semolina/durum]
          declared #{:wheat :eggs}]
      (is (true? (facts/allergen-declaration-complete? j ingredients declared))))))

;; ──────────────────────── Evidence Completeness ──────────────────────

(deftest required-evidence-satisfied-test
  (testing "complete evidence checklist passes"
    (let [j (facts/jurisdiction-by-id :jp/prefectural)
          evidence [:formulation-record :extrusion-log :drying-log
                    :moisture-test :allergen-declaration :weight-check]]
      (is (true? (facts/required-evidence-satisfied? j evidence)))))

  (testing "incomplete evidence fails"
    (let [j (facts/jurisdiction-by-id :jp/prefectural)
          evidence [:formulation-record :extrusion-log]]
      (is (false? (facts/required-evidence-satisfied? j evidence))))))
