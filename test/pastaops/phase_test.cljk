(ns pastaops.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [pastaops.phase :as phase]))

;; ──────────────────────── Phase Validity ──────────────────────

(deftest valid-phase-test
  (testing "intake is valid"
    (is (true? (phase/valid-phase? :intake))))

  (testing "produce is valid"
    (is (true? (phase/valid-phase? :produce))))

  (testing "archived is valid"
    (is (true? (phase/valid-phase? :archived))))

  (testing "invalid phase returns false"
    (is (false? (phase/valid-phase? :invalid)))))

;; ──────────────────────── Phase Transitions ──────────────────────

(deftest can-transition-test
  (testing "intake -> design is valid (forward progression)"
    (is (true? (phase/can-transition? :intake :design))))

  (testing "intake -> produce is valid (skip design)"
    (is (true? (phase/can-transition? :intake :produce))))

  (testing "design -> intake is invalid (backward)"
    (is (false? (phase/can-transition? :design :intake))))

  (testing "produce -> archived is valid (forward to end)"
    (is (true? (phase/can-transition? :produce :archived))))

  (testing "archived -> intake is invalid (backward from end)"
    (is (false? (phase/can-transition? :archived :intake))))

  (testing "same phase is invalid"
    (is (false? (phase/can-transition? :produce :produce))))

  (testing "invalid phases return false"
    (is (false? (phase/can-transition? :invalid :produce)))
    (is (false? (phase/can-transition? :produce :invalid)))))
