(ns pastaops.store
  "Store abstraction for macaroni/noodle/couscous production batches.

  Two layers:

    1. PURE VALUE HELPERS (`production-batch` / `batch-already-processed?`
       / `batch-shipment-finalized?` / `log-batch` / `finalize-shipment` /
       `stage-batch` / `mark-processed` / `audit-trail` / `append-fact`) --
       plain functions over an immutable `{:batches {batch-id batch-map}}`
       value. `pastaops.governor`'s independent checks call these directly
       against whatever snapshot they're handed (a raw test fixture map,
       or `(snapshot store)` below) -- this is this actor's original seam
       and stays unchanged so the Governor never has to care whether it's
       looking at a plain map or a live `Store`.

    2. `Store` PROTOCOL -- the backend seam every other cloud-itonami
       actor in this fleet uses (mirrors `bakeryops.store`,
       cloud-itonami-isic-1071): `MemStore` (atom, deterministic default
       for dev/tests/demo). Holds the SAME `{:batches {...}}` shape the
       pure helpers above expect (`snapshot` returns it) plus an
       append-only audit ledger (`ledger`/`append-ledger!`) -- this
       actor's core missing plumbing until now. `pastaops.operation`'s
       `:commit`/`:hold` graph nodes append every committed/held/
       approval-rejected decision fact here, so a batch's full operating
       history is always a query over an immutable log.

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

  The ledger (`:facts` for the pure layer, `ledger`/`append-ledger!` for
  the `Store` layer) is a separate append-only log of audit facts.")

;; ----------------------- pure value helpers (unchanged seam) -----------------------

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
  (one-way flag) in ONE step -- a wholesale replace. Used by tests /
  callers that don't distinguish staging from committing."
  [st batch-id batch-data]
  (assoc-in st [:batches batch-id] (assoc batch-data :processed? true)))

(defn finalize-shipment
  "Mark an existing batch's shipment as finalized (one-way flag), leaving
  all other fields untouched. Used once a `:coordinate-shipment` proposal
  commits."
  [st batch-id]
  (assoc-in st [:batches batch-id :shipment-finalized?] true))

(defn stage-batch
  "Register/update a batch's PRE-COMMIT data (formulation, drying
  parameters, sanitation, evidence-checklist, jurisdiction -- whatever
  `pastaops.governor`'s independent checks validate) WITHOUT marking it
  processed. Used by tests, simulation, and plant-operator/telemetry
  onboarding BEFORE any `:log-production-batch` proposal is made --
  `governor/already-processed-violations` exists precisely to enforce
  that a batch isn't logged twice, which only means something if staging
  (this fn) and committing (`mark-processed`) are distinct steps."
  [st batch-id batch-data]
  (assoc-in st [:batches batch-id] batch-data))

(defn mark-processed
  "Flip `:processed?` true on an ALREADY-STAGED batch record, preserving
  every other field (unlike `log-batch`, which replaces the batch's data
  wholesale). Used by the `:commit` graph node once a
  `:log-production-batch` proposal clears the Governor."
  [st batch-id]
  (update-in st [:batches batch-id] assoc :processed? true))

(defn audit-trail
  "Return the append-only audit ledger (empty vector if none yet)."
  [st]
  (get st :facts []))

(defn append-fact
  "Append `fact` to the store's audit ledger."
  [st fact]
  (update st :facts (fnil conj []) fact))

;; ----------------------------- Store protocol -----------------------------

(defprotocol Store
  (snapshot [store]
    "The current `{:batches {batch-id batch-map}}` value -- the exact
    shape `pastaops.governor`/the pure helpers above expect. Pass this
    straight to `governor/check` or any `production-batch`-family fn.")
  (register-batch! [store batch-id batch-data]
    "Register/update a batch's pre-commit data (`stage-batch`). Used by
    tests, simulation, and plant-operator/telemetry onboarding.")
  (mark-processed! [store batch-id]
    "Flip `:processed?` true on an already-staged batch (`mark-processed`).
    Used by the `:commit` graph node for `:log-production-batch`.")
  (mark-shipment-finalized! [store batch-id]
    "Flip `:shipment-finalized?` true on an existing batch
    (`finalize-shipment`). Used by the `:commit` graph node for
    `:coordinate-shipment`.")
  (ledger [store]
    "The append-only audit ledger: every committed/held/approval-rejected
    decision fact, in append order.")
  (append-ledger! [store fact]
    "Append one immutable decision fact to the ledger. Returns fact.
    THIS is the call that was previously reachable only from test setup
    (`store/append-fact` on a plain map) and never from the real
    `:commit`/`:hold` node handlers -- fixed by wiring it into
    `pastaops.operation/build`'s compiled StateGraph."))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [state-atom ledger-atom]
  Store
  (snapshot [_store] @state-atom)
  (register-batch! [_store batch-id batch-data]
    (swap! state-atom stage-batch batch-id batch-data)
    batch-data)
  (mark-processed! [_store batch-id]
    (swap! state-atom mark-processed batch-id)
    nil)
  (mark-shipment-finalized! [_store batch-id]
    (swap! state-atom finalize-shipment batch-id)
    nil)
  (ledger [_store] @ledger-atom)
  (append-ledger! [_store fact]
    (swap! ledger-atom conj fact)
    fact))

(defn mem-store
  "Create an in-memory store. `initial-batches` is an optional map of
  batch-id -> batch-record (pre-commit data, as staged via
  `register-batch!`/`stage-batch`)."
  [& [{:keys [initial-batches] :or {initial-batches {}}}]]
  (MemStore. (atom {:batches initial-batches}) (atom [])))
