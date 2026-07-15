(ns pastaops.governor
  "Macaroni/Noodle/Couscous Governor -- the independent compliance layer
  that earns the PastaOpsAdvisor the right to commit. The LLM has no
  notion of:
    - Whether a batch's drying temperature stayed within safe range
    - Whether drying time exceeded limits for the product
    - Whether post-drying moisture falls within tolerance (mold-growth risk)
    - Whether the ingredient/dosing scale calibration is current
    - Whether final product weight variance is acceptable
    - Whether allergen labeling (wheat semolina, egg noodles) is complete
    - Whether sanitation/hygiene verification is passed
    - Whether an open food-safety concern has been resolved

  This MUST be a separate system able to *reject* a proposal and fall back
  to HOLD.

  Unlike direct extrusion/drying-line control (NEVER done by this actor --
  extruder and dryer operation remain exclusive to plant staff), the
  Governor operates on batch metadata: provenance, formulation, drying
  parameters, sanitation records, and food-safety flags. This is plant-
  operations coordination, not process control.

  CRITICAL: Any proposal involving food-safety concerns (allergen
  cross-contact between wheat/egg, moisture-related mold risk,
  contamination, sanitation failures, temperature/time violations) ALWAYS
  escalates to human operator for final sign-off. The LLM's confidence is
  never sufficient for food-safety decisions.

  Hard violations (always HOLD, no override):
    1. No jurisdiction citation (jurisdiction unknown -> can't verify reqs)
    2. Evidence incomplete (missing required-evidence per jurisdiction)
    3. Drying temperature out of range (product safety)
    4. Drying time exceeded (product safety)
    5. Moisture out of target range (mold-growth food-safety risk)
    6. Sanitation score insufficient (plant hygiene not verified)
    7. Scale calibration overdue (formulation accuracy at risk)
    8. Weight variance excessive (scale drift risk)
    9. Allergen labeling mismatch (food-safety / labeling violation)
   10. Food-safety flag unresolved (open concern, escalate required)

  Soft gates (always escalate for human):
    - Low confidence
    - Real actuation (`:log-production-batch`, `:coordinate-shipment`)
    - `:flag-food-safety-concern` (never auto-resolved by confidence alone)

  This design mirrors `bakeryops.governor` and `meatprocessing.governor` but
  specializes on macaroni/noodle/couscous-specific concerns: extrusion/
  drying-yield formulation, time-at-temperature during drying, post-drying
  moisture (a food-safety boundary here, not merely texture), and wheat/egg
  allergen accuracy — rather than baking-quality windows or cold-chain
  management."
  (:require [pastaops.facts :as facts]
            [pastaops.registry :as registry]
            [pastaops.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Logging a batch into production records (`:log-production-batch`) and
  coordinating shipment of finished product (`:coordinate-shipment`) are the
  two real-world actuation events this actor performs. Both require plant
  operator sign-off."
  #{:log-production-batch :coordinate-shipment})

(def always-escalate-ops
  "Operations that always require human sign-off, even when the Governor's
  hard checks are clean and confidence is high: the two high-stakes
  actuation events (`high-stakes`) plus `:flag-food-safety-concern` --
  a food-safety concern (e.g. allergen cross-contact between wheat and egg,
  or moisture-related mold risk) is never auto-resolved by advisor
  confidence alone, it always needs a human look."
  (conj high-stakes :flag-food-safety-concern))

(def allowed-ops
  "Closed allowlist of proposal operations this actor may ever make. Any
  proposal for an operation outside this set -- most importantly direct
  extrusion/drying-line control or food-safety CERTIFICATION authority -- is
  a hard, permanent block: this actor coordinates plant operations, it does
  not operate equipment and it does not certify food safety."
  #{:log-production-batch :schedule-maintenance :flag-food-safety-concern :coordinate-shipment})

;; ────────────────────────── Checks ──────────────────────────

(defn- op-not-allowed-violations
  "HARD, permanent block: any proposal outside the closed operation
  allowlist (e.g. direct extrusion/drying-line control, or a food-safety
  certification action) is refused unconditionally -- this actor has no
  authority to make such a proposal at all, let alone commit it."
  [{:keys [op]} _proposal]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowed
      :detail (str op " はこのactorの許可された提案種別 (log-production-batch/"
                  "schedule-maintenance/flag-food-safety-concern/coordinate-shipment) "
                  "に含まれない -- extrusion/drying-line制御やfood-safety認証権限はこのactorに無い")}]))

(defn- effect-not-propose-violations
  "HARD invariant: this actor's proposals are always `:effect :propose` --
  it never claims direct write/actuation authority for itself. A proposal
  asserting any other effect is refused unconditionally."
  [_request proposal]
  (when-let [effect (:effect proposal)]
    (when (not= effect :propose)
      [{:rule :effect-not-propose
        :detail (str "この actor の提案は :propose 以外の :effect を持てない (got " effect ")")}])))

(defn- shipment-batch-not-registered-violations
  "HARD invariant: a plant/batch record must be verified/registered in the
  store before `:coordinate-shipment` can be proposed against it --
  coordinating shipment of a batch this plant never checked in is out of
  scope for this actor."
  [{:keys [op subject]} st]
  (when (= op :coordinate-shipment)
    (when-not (store/production-batch st subject)
      [{:rule :batch-not-registered
        :detail (str subject " はプラントに登録されたバッチ記録が無い -- 出荷調整提案は進められない")}])))

(defn- spec-basis-violations
  "A proposal with no jurisdiction citation is a HARD violation -- never
  invent a jurisdiction's food-safety requirements."
  [{:keys [op]} proposal]
  (when (contains?
         #{:log-production-batch :coordinate-shipment :flag-food-safety-concern}
         op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :jurisdiction) (nil? (:jurisdiction value))))
        [{:rule :no-spec-basis
          :detail "公式仕様の引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:log-production-batch`, verify the batch's evidence checklist is
  complete per jurisdiction requirements."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when-not (and b
                     (facts/required-evidence-satisfied?
                      (:jurisdiction b)
                      (:evidence-checklist b)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(formulation/extrusion-log/drying-log/moisture-test等)が充足していない状態での提案"}]))))

(defn- drying-temp-out-of-range-violations
  "For `:log-production-batch`, INDEPENDENTLY verify the batch's actual
  drying temperature stays inside its safe range via
  `registry/drying-temp-out-of-range?`. Evaluated UNCONDITIONALLY."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:drying-temp-c b)
                 (registry/drying-temp-out-of-range?
                  (:drying-temp-c b)
                  (:drying-temp-c-min p)
                  (:drying-temp-c-max p)))
        [{:rule :drying-temp-out-of-range
          :detail (str subject " の乾燥温度(" (:drying-temp-c b) " ℃)が安全窓["
                      (:drying-temp-c-min p) ", "
                      (:drying-temp-c-max p) "] ℃ の外 -- バッチ登録提案は進められない")}]))))

(defn- drying-time-exceeded-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  drying time does not exceed the maximum via `registry/drying-time-exceeded?`.
  Evaluated UNCONDITIONALLY."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:drying-time-minutes b)
                 (registry/drying-time-exceeded?
                  (:drying-time-minutes b)
                  (:drying-time-max-minutes p)))
        [{:rule :drying-time-exceeded
          :detail (str subject " の乾燥時間(" (:drying-time-minutes b)
                      " 分)が限度(" (:drying-time-max-minutes p)
                      " 分)を超過 -- バッチ登録提案は進められない")}]))))

(defn- moisture-out-of-target-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's final
  (post-drying) moisture falls within tolerance via
  `registry/moisture-out-of-target?`. Excess moisture here is a mold-growth
  food-safety hazard, not merely a texture defect."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:moisture-percent b)
                 (registry/moisture-out-of-target?
                  (:moisture-percent b)
                  (:moisture-target-percent p)
                  (:moisture-tolerance-percent p)))
        [{:rule :moisture-out-of-target
          :detail (str subject " の水分(" (:moisture-percent b)
                      "%)が目標範囲外 -- カビ発生リスクのためバッチ登録提案は進められない")}]))))

(defn- sanitation-score-insufficient-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the plant's
  sanitation score meets minimum requirements."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (:sanitation-score b)
                 (registry/sanitation-score-insufficient? (:sanitation-score b) 75))
        [{:rule :sanitation-score-insufficient
          :detail (str subject " のプラント衛生スコア(" (:sanitation-score b)
                      ")が最低要件(75)を下回る -- バッチ登録提案は進められない")}]))))

(defn- now-epoch-ms
  "Current time in epoch milliseconds, portable across Clojure/
  ClojureScript. Isolated to this single call site so the rest of the
  namespace (and all of `pastaops.registry`) stays free of host-clock
  calls."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (js/Date.now)))

(defn- scale-calibration-overdue-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the ingredient/
  dosing scale's calibration is current (recalibration required every 180
  days)."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (:scale-last-calibration-date b)
                 (registry/scale-calibration-overdue? (:scale-last-calibration-date b) (now-epoch-ms)))
        [{:rule :scale-calibration-overdue
          :detail (str subject " のスケール校正が期限切れ -- バッチ登録提案は進められない")}]))))

(defn- weight-variance-excessive-violations
  "For `:log-production-batch`, INDEPENDENTLY verify the weight variance."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (:weight-variance-grams b)
                 (registry/weight-variance-excessive? (:weight-variance-grams b) 50))
        [{:rule :weight-variance-excessive
          :detail (str subject " の重量分散(" (:weight-variance-grams b)
                      "g)が許容範囲(50g)を超過 -- バッチ登録提案は進められない")}]))))

(defn- allergen-label-mismatch-violations
  "For `:log-production-batch`, INDEPENDENTLY verify allergen declaration
  completeness and accuracy via `registry/allergen-label-risk?` -- e.g. a
  wheat-semolina base or an egg-noodle formulation left undeclared."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          formula-allergens (facts/formulation-allergen-set (:ingredients b))]
      (when (and b formula-allergens (:declared-allergens b)
                 (registry/allergen-label-risk? formula-allergens (:declared-allergens b)))
        [{:rule :allergen-label-mismatch
          :detail (str subject " のアレルゲン宣言が不完全 -- バッチ登録提案は進められない")}]))))

(defn- food-safety-flag-unresolved-violations
  "An unresolved food-safety flag is a HARD, un-overridable hold.
  Food-safety concerns (suspected wheat/egg allergen cross-contact,
  moisture-related mold risk, contamination, unexpected defects) raised
  during production or inspection MUST be resolved before the batch can be
  logged. Evaluated UNCONDITIONALLY at `:log-production-batch`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and (true? (:safety-concern-raised? b))
                 (not (true? (:safety-concern-resolved? b))))
        [{:rule :food-safety-flag-unresolved
          :detail (str subject " は未解決の食品安全フラグがある -- バッチ登録提案は進められない")}]))))

(defn- already-processed-violations
  "For `:log-production-batch`, refuse to process the SAME batch twice, off
  a dedicated `:processed?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (when (store/batch-already-processed? st subject)
      [{:rule :already-processed
        :detail (str subject " は既に登録済み")}])))

(defn- already-shipment-finalized-violations
  "For `:coordinate-shipment`, refuse to finalize the SAME batch's shipment
  twice, off a dedicated `:shipment-finalized?` fact."
  [{:keys [op subject]} st]
  (when (= op :coordinate-shipment)
    (when (store/batch-shipment-finalized? st subject)
      [{:rule :already-shipment-finalized
        :detail (str subject " は既に出荷確定済み")}])))

(defn check
  "Censors a PastaOpsAdvisor proposal against the Governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}.

  Stakes (high-stakes actuation vs. always-escalate) are read off the
  REQUEST's `:op` -- not off the proposal -- since the operation being
  proposed (not the advisor's self-reported stake) is what determines
  whether a human must sign off."
  [request _context proposal st]
  (let [hard (into []
                   (concat (op-not-allowed-violations request proposal)
                           (effect-not-propose-violations request proposal)
                           (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (drying-temp-out-of-range-violations request st)
                           (drying-time-exceeded-violations request st)
                           (moisture-out-of-target-violations request st)
                           (sanitation-score-insufficient-violations request st)
                           (scale-calibration-overdue-violations request st)
                           (weight-variance-excessive-violations request st)
                           (allergen-label-mismatch-violations request st)
                           (food-safety-flag-unresolved-violations request st)
                           (already-processed-violations request st)
                           (already-shipment-finalized-violations request st)
                           (shipment-batch-not-registered-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        actuation? (boolean (high-stakes (:op request)))
        escalate-op? (boolean (always-escalate-ops (:op request)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not escalate-op?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? escalate-op?))
     :high-stakes? actuation?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
