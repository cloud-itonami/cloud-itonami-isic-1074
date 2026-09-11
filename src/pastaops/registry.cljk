(ns pastaops.registry
  "Pure validation functions for macaroni/noodle/couscous extrusion-drying
  production parameters. These are called by the Governor to independently
  verify physical/operational constraints -- the advisor's confidence is
  NOT sufficient to override these checks.

  All functions here are pure arithmetic/set predicates with no host-clock
  or I/O calls, so this namespace stays trivially portable across
  Clojure/ClojureScript. Callers that need the current time (see
  `scale-calibration-overdue?`) obtain it themselves via a `:clj`/`:cljs`
  reader-conditional at the call site (see `pastaops.governor`)."
  (:require [clojure.set :as set]))

(defn drying-temp-out-of-range?
  "Independently verify that the batch's actual drying temperature stays
  within the product's required range [min,max]. Both bounds are inclusive.
  Drying too cool risks incomplete moisture removal (mold-growth risk in
  storage); drying too hot risks case-hardening (a sealed dry outer layer
  trapping moisture inside) and cracking. Both are HARD limits."
  [actual-temp-c min-temp-c max-temp-c]
  (or (< actual-temp-c min-temp-c)
      (> actual-temp-c max-temp-c)))

(defn drying-time-exceeded?
  "Independently verify that the batch's actual drying time does not exceed
  the product's maximum. Time zero is drying-tunnel entry; time is recorded
  continuously via the dryer controller log."
  [actual-minutes max-minutes]
  (> actual-minutes max-minutes))

(defn moisture-out-of-target?
  "Independently verify that the batch's finished-product (post-drying)
  moisture falls within tolerance of the product's target moisture. For
  dried farinaceous products this is the primary food-safety boundary:
  moisture left too high is a genuine mold-growth hazard in ambient
  storage/distribution, not merely a texture defect."
  [actual-percent target-percent tolerance-percent]
  (or (< actual-percent (- target-percent tolerance-percent))
      (> actual-percent (+ target-percent tolerance-percent))))

(defn sanitation-score-insufficient?
  "Independently verify that the plant's pre-production sanitation score
  meets the minimum required. Score is 0-100, assessed by a third-party
  auditor against food-safety sanitation standards."
  [actual-score min-score-required]
  (< actual-score min-score-required))

(defn scale-calibration-overdue?
  "Independently verify that the ingredient/dosing scale was calibrated
  within the last 180 days. `last-calibration-epoch-ms` and `now-epoch-ms`
  are both epoch milliseconds -- callers obtain `now` via a `:clj`/`:cljs`
  reader-conditional, keeping this namespace free of any host-clock call."
  [last-calibration-epoch-ms now-epoch-ms]
  (> (- now-epoch-ms last-calibration-epoch-ms)
     (* 180 24 60 60 1000)))

(defn weight-variance-excessive?
  "Independently verify that a batch's finished-product (packaged) weight
  variance (drift from target, in grams) does not exceed the maximum
  tolerance. Excessive variance indicates the dosing scale is out of
  calibration or the formulation was measured incorrectly."
  [actual-variance-grams max-variance-grams]
  (> actual-variance-grams max-variance-grams))

(defn allergen-label-risk?
  "True when the formulation contains an allergen NOT present in the
  declared-allergens set (mislabeling / under-declaration risk -- a genuine
  food-safety hazard for allergic consumers, e.g. wheat semolina or egg in
  egg noodles left undeclared). Declaring MORE allergens than the
  formulation actually contains is conservative and never a risk."
  [formula-allergens declared-allergens]
  (not (set/subset? (set formula-allergens) (set declared-allergens))))
