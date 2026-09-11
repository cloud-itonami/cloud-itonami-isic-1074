(ns pastaops.facts
  "Reference facts for macaroni/noodles/couscous manufacturing (ISIC 1074):
  product-type extrusion/drying parameters (temperature/time/moisture
  windows), jurisdiction allergen-declaration and evidence-checklist
  requirements, and per-ingredient allergen data. This namespace contains
  pure lookup functions for regulatory/food-safety compliance checks -- the
  Governor calls these to independently validate proposals; the advisor's
  confidence is never sufficient on its own.

  Drying is the food-safety-critical step for dried farinaceous products:
  finished-product moisture must be brought down far enough, far enough
  below the mold-growth threshold, to be microbiologically stable at ambient
  storage (see CODEX STAN 249-2006, Standard for Dried Pasta, moisture
  ceiling 12.5% m/m) -- unlike bread baking (`bakeryops.facts`), where
  moisture windows are primarily a quality/texture concern, here excess
  post-drying moisture is itself the primary food-safety hazard this
  Governor exists to catch."
  (:require [clojure.set :as set]))

(def product-types
  "Valid macaroni/noodle/couscous product categories and their safe
  extrusion-drying windows. Drying times are in minutes (industrial
  dried-pasta drying commonly runs several hours; couscous granules,
  being much smaller, dry faster than long/short pasta shapes)."
  {:macaroni/elbow
   {:id :macaroni/elbow
    :name "マカロニ"
    :drying-temp-c-min 82
    :drying-temp-c-max 88
    :drying-time-min-minutes 210
    :drying-time-max-minutes 300
    :moisture-target-percent 12.0
    :moisture-tolerance-percent 0.5}

   :pasta/spaghetti
   {:id :pasta/spaghetti
    :name "スパゲッティ"
    :drying-temp-c-min 85
    :drying-temp-c-max 95
    :drying-time-min-minutes 180
    :drying-time-max-minutes 260
    :moisture-target-percent 12.0
    :moisture-tolerance-percent 0.5}

   :noodle/egg
   {:id :noodle/egg
    :name "卵麺"
    :drying-temp-c-min 50
    :drying-temp-c-max 60
    :drying-time-min-minutes 240
    :drying-time-max-minutes 420
    :moisture-target-percent 11.0
    :moisture-tolerance-percent 0.5}

   :couscous/semolina
   {:id :couscous/semolina
    :name "クスクス"
    :drying-temp-c-min 40
    :drying-temp-c-max 60
    :drying-time-min-minutes 60
    :drying-time-max-minutes 120
    :moisture-target-percent 10.0
    :moisture-tolerance-percent 0.5}})

(defn product-type-by-id [id]
  (get product-types id))

(def jurisdictions
  "Macaroni/noodle/couscous jurisdictions and their allergen-declaration and
  evidence-checklist requirements. Required evidence cites CODEX STAN
  249-2006 (Standard for Dried Pasta) compliance in addition to the usual
  formulation/process/moisture/allergen/weight checklist items."
  {:jp/prefectural
   {:id :jp/prefectural
    :name "日本 (食品表示法・都道府県)"
    :allergen-declaration-required true
    :major-allergens #{:wheat :eggs :milk :peanuts :tree-nuts :sesame :soy}
    :required-evidence
    [:formulation-record
     :extrusion-log
     :drying-log
     :moisture-test
     :allergen-declaration
     :weight-check]}

   :us/fda
   {:id :us/fda
    :name "United States (FDA/FALCPA)"
    :allergen-declaration-required true
    :major-allergens #{:wheat :eggs :milk :peanuts :tree-nuts :sesame :soy}
    :required-evidence
    [:formulation-record
     :extrusion-log
     :drying-log
     :moisture-test
     :allergen-declaration
     :weight-check]}

   :eu/efsa
   {:id :eu/efsa
    :name "European Union (EFSA)"
    :allergen-declaration-required true
    :major-allergens #{:wheat :eggs :milk :peanuts :tree-nuts :sesame :soy :celery :mustard}
    :required-evidence
    [:formulation-record
     :extrusion-log
     :drying-log
     :moisture-test
     :allergen-declaration
     :weight-check]}})

(defn jurisdiction-by-id [id]
  (get jurisdictions id))

(def ingredient-allergen-table
  "Per-ingredient primary allergen and cross-contact risk, used to derive a
  formulation's allergen set for label-accuracy verification. Ingredients
  with no allergen relevance map to nil."
  {:semolina/durum   {:primary-allergen :wheat :cross-contact-risk #{:tree-nuts :soy}}
   :flour/common     {:primary-allergen :wheat :cross-contact-risk #{:tree-nuts}}
   :egg/whole        {:primary-allergen :eggs :cross-contact-risk #{}}
   :egg/yolk         {:primary-allergen :eggs :cross-contact-risk #{}}
   :milk/whole       {:primary-allergen :milk :cross-contact-risk #{}}
   :soy/lecithin     {:primary-allergen :soy :cross-contact-risk #{}}
   :sesame/oil       {:primary-allergen :sesame :cross-contact-risk #{}}
   :salt/sea         nil
   :water/filtered   nil
   :oil/vegetable    nil
   :spinach/puree    nil
   :tomato/puree     nil})

(defn ingredient-allergens [id]
  (get ingredient-allergen-table id))

(defn formulation-allergen-set
  "Given a formulation's ingredient-id list, return the set of primary
  allergens actually present. Non-allergenic / unknown ingredient ids
  contribute nothing."
  [ingredients]
  (into #{}
        (keep (fn [id] (:primary-allergen (ingredient-allergens id))))
        ingredients))

(defn allergen-declaration-complete?
  "Verify that `declared` allergens are a superset of the formulation's
  actual allergens for `ingredients`. Extra (conservative) declarations
  pass; omissions fail. `jurisdiction` is accepted for call-site symmetry
  with other facts lookups."
  [_jurisdiction ingredients declared]
  (set/subset? (formulation-allergen-set ingredients) (set declared)))

(defn required-evidence-satisfied?
  "Verify that every item in the jurisdiction's `:required-evidence` list is
  present in `evidence`. `jurisdiction` may be a resolved jurisdiction map
  (as returned by `jurisdiction-by-id`) or a raw jurisdiction id -- both
  call conventions are in use (tests pass a resolved map; the Governor
  passes the raw id straight off batch metadata)."
  [jurisdiction evidence]
  (let [j (if (map? jurisdiction) jurisdiction (jurisdiction-by-id jurisdiction))]
    (if-not j
      false
      (set/subset? (set (:required-evidence j)) (set evidence)))))

(defn drying-temp-in-range?
  "Positive-sense convenience predicate: does `temp-c` fall within
  `product`'s safe drying window (inclusive)?"
  [temp-c product]
  (boolean
   (and (some? product)
        (>= temp-c (:drying-temp-c-min product))
        (<= temp-c (:drying-temp-c-max product)))))

(defn drying-time-in-range?
  "Positive-sense convenience predicate: does `minutes` fall within
  `product`'s expected drying-time window (inclusive)?"
  [minutes product]
  (boolean
   (and (some? product)
        (>= minutes (:drying-time-min-minutes product))
        (<= minutes (:drying-time-max-minutes product)))))

(defn moisture-in-range?
  "Positive-sense convenience predicate: does `percent` fall within
  `product`'s moisture tolerance window (inclusive) around its target?
  For dried farinaceous products this window is a food-safety boundary
  (mold-growth risk above it), not merely a texture/quality one."
  [percent product]
  (boolean
   (and (some? product)
        (let [target (:moisture-target-percent product)
              tol (:moisture-tolerance-percent product)]
          (and (>= percent (- target tol))
               (<= percent (+ target tol)))))))
