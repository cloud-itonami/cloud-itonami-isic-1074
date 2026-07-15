(ns pastaops.store
  "Store abstraction for macaroni/noodle/couscous production batches.
  Current implementation operates on plain data (`{:batches {batch-id
  batch-map} :facts [...]}`); production should migrate this seam to
  Datomic/kotoba-server (the same seam point all cloud-itonami actors use)
  while keeping the same pure-function surface.

  A production batch is the minimal unit of work: one extrusion/drying run
  of a farinaceous product, tracked from formulation through drying,
  inspection, and shipment. Representative batch keys:
    - :product-type keyword product id (see `pastaops.facts/product-types`)
    - :jurisdiction keyword jurisdiction id (see `pastaops.facts/jurisdictions`)
    - :drying-temp-c / :drying-time-minutes / :moisture-percent actuals
    - :sanitation-score 0-100 plant hygiene score
    - :scale-last-calibration-date epoch-ms of last dosing-scale calibration
    - :weight-variance-grams finished-product weight drift from target
    - :ingredients formulation ingredient ids
    - :declared-allergens set of declared allergen keywords
    - :evidence-checklist evidence items present for the batch
    - :safety-concern-raised? / :safety-concern-resolved? food-safety flag
    - :processed? true once a `:log-production-batch` proposal commits
    - :shipment-finalized? true once a `:coordinate-shipment` proposal commits

  The ledger (`:facts`) is a separate append-only vector of audit facts,
  kept alongside `:batches` in the same store value.")

(defn production-batch
  "Retrieve a batch by id, or nil if it does not exist / is not yet
  registered."
  [st batch-id]
  (get-in st [:batches batch-id]))

(defn batch-already-processed?
  "True only if the batch exists and has already been marked processed."
  [st batch-id]
  (true? (:processed? (production-batch st batch-id))))

(defn batch-shipment-finalized?
  "True only if the batch exists and its shipment has already been
  finalized."
  [st batch-id]
  (true? (:shipment-finalized? (production-batch st batch-id))))

(defn log-batch
  "Register/update `batch-data` under `batch-id` and mark it processed
  (one-way flag). Used once a `:log-production-batch` proposal commits."
  [st batch-id batch-data]
  (assoc-in st [:batches batch-id] (assoc batch-data :processed? true)))

(defn finalize-shipment
  "Mark an existing batch's shipment as finalized (one-way flag). Used once
  a `:coordinate-shipment` proposal commits."
  [st batch-id]
  (assoc-in st [:batches batch-id :shipment-finalized?] true))

(defn audit-trail
  "Return the append-only audit ledger (empty vector if none yet)."
  [st]
  (get st :facts []))

(defn append-fact
  "Append `fact` to the store's audit ledger."
  [st fact]
  (update st :facts (fnil conj []) fact))
