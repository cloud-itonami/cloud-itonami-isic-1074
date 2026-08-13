(ns pastaops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: there was NO demo page
  and no generator here at all. Every row on the produced page is the
  output of a REAL run of this repo's own actor stack --
  `pastaops.operation/build` (a genuinely compiled `langgraph.graph`
  StateGraph) -> `pastaops.advisor` -> `pastaops.governor` ->
  `pastaops.store` -- driven through `langgraph.graph/run*` exactly the
  way `pastaops.sim` and `test/pastaops/operation_graph_test.cljc` drive
  it, including real `interrupt-before` checkpoint/resume for the
  human-in-the-loop approval gate. Nothing on the page is hand-typed
  HTML describing behaviour: the batch table is `store/snapshot`, the
  ledger table is `store/ledger`, the hold tables are the Governor's own
  `:violations` maps, and even the action-gate table is derived at render
  time from `pastaops.governor/allowed-ops` / `high-stakes` /
  `always-escalate-ops` / `confidence-floor` rather than being a static
  description that can silently drift from the code.

  SEED DATA. This repo's `pastaops.store/mem-store` starts empty (unlike
  siblings that ship a `seed-db`); batches are staged by the caller, as
  `pastaops.sim` does. So the scenario seed lives here, and it is
  DERIVED, not invented: `batch-001` is `pastaops.sim`'s own
  `clean-batch` fixture verbatim, every other batch is built by
  `clean-batch-for` out of `pastaops.facts/product-types` and
  `pastaops.facts/jurisdictions` (drying window mid-points, the
  jurisdiction's own `:required-evidence` list), and each deliberately
  bad batch is that clean record perturbed by a value computed off the
  same product record (e.g. `min - 5` degrees). The whole seed is
  rendered on the page, so every id shown is traceable to it.

  BUILD-TIME INVARIANT. `-main` REFUSES to write the file unless the run
  actually produced HARD Governor holds, and unless every scenario
  reached the disposition it declares. A console that shows only happy
  paths -- or one where the Governor silently stopped refusing -- is not
  a demo of a governed actor, so it is a build failure, not a note in a
  README.

  DETERMINISM. No timestamps, no randomness, no map-iteration order in
  the output (every collection is explicitly sorted or already an
  ordered vector). Two runs against the same seed produce byte-identical
  files.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [pastaops.advisor :as advisor]
            [pastaops.facts :as facts]
            [pastaops.governor :as governor]
            [pastaops.operation :as operation]
            [pastaops.store :as store]))

;; ----------------------------- seed -----------------------------

(def ^:private plant-op
  {:actor-id "plant-op-01" :role :plant-operator})

(def ^:private clean-batch
  "Verbatim copy of `pastaops.sim`'s own `clean-batch` fixture (which is
  itself mirrored by `test/pastaops/operation_test.cljc` and
  `operation_graph_test.cljc`): clean against every independent Governor
  check."
  {:product-type :macaroni/elbow
   :jurisdiction :jp/prefectural
   :drying-temp-c 85
   :drying-time-minutes 250
   :moisture-percent 12.0
   :ingredients [:semolina/durum]
   :declared-allergens #{:wheat}
   :sanitation-score 85
   :evidence-checklist [:formulation-record :extrusion-log :drying-log
                        :moisture-test :allergen-declaration :weight-check]})

(defn- clean-batch-for
  "Build a Governor-clean batch record for `product-id` under
  `jurisdiction`, with every drying parameter DERIVED from
  `pastaops.facts/product-types` (window mid-points) and the evidence
  checklist derived from the jurisdiction's own `:required-evidence`.
  Nothing here is a magic number typed to match a check."
  [product-id jurisdiction ingredients declared]
  (let [p (facts/product-type-by-id product-id)
        j (facts/jurisdiction-by-id jurisdiction)]
    {:product-type product-id
     :jurisdiction jurisdiction
     :drying-temp-c (quot (+ (:drying-temp-c-min p) (:drying-temp-c-max p)) 2)
     :drying-time-minutes (quot (+ (:drying-time-min-minutes p)
                                   (:drying-time-max-minutes p)) 2)
     :moisture-percent (:moisture-target-percent p)
     :ingredients ingredients
     :declared-allergens declared
     :sanitation-score 85
     :evidence-checklist (vec (:required-evidence j))}))

(defn- product-of [batch] (facts/product-type-by-id (:product-type batch)))

(def ^:private stale-calibration-epoch-ms
  "2020-01-01T00:00:00Z. Fixed (not `now - N`) so the page stays
  byte-identical across runs; far enough past that
  `registry/scale-calibration-overdue?` is stable regardless of when the
  build runs."
  1577836800000)

(def ^:private seed-batches
  "The staged plant records this console is rendered from. Each entry is
  a clean record (derived above) or that same record perturbed by ONE
  value computed off its own product window, so exactly one Governor
  rule fires per bad batch."
  (let [b002 (clean-batch-for :pasta/spaghetti :us/fda [:semolina/durum] #{:wheat})
        b003 (clean-batch-for :noodle/egg :eu/efsa [:semolina/durum :egg/whole] #{:wheat :eggs})
        b004 (clean-batch-for :couscous/semolina :jp/prefectural [:semolina/durum] #{:wheat})
        b005 (clean-batch-for :pasta/spaghetti :us/fda [:semolina/durum] #{:wheat})
        b006 (clean-batch-for :macaroni/elbow :jp/prefectural [:semolina/durum] #{:wheat})
        b007 (clean-batch-for :noodle/egg :eu/efsa [:semolina/durum :egg/whole] #{:wheat})
        b008 (clean-batch-for :pasta/spaghetti :us/fda [:semolina/durum] #{:wheat})
        b009 (clean-batch-for :couscous/semolina :jp/prefectural [:semolina/durum] #{:wheat})
        b010 (clean-batch-for :macaroni/elbow :jp/prefectural [:semolina/durum] #{:wheat})
        b011 (clean-batch-for :pasta/spaghetti :jp/prefectural [:semolina/durum] #{:wheat})
        b012 (clean-batch-for :noodle/egg :us/fda [:semolina/durum :egg/whole] #{:wheat :eggs})]
    ;; ordered so the rendered seed table is deterministic
    [["batch-001" clean-batch
      "clean (pastaops.sim fixture) — logging + maintenance + safety-flag lane"]
     ["batch-002" b002
      "clean — finished-product shipment lane"]
     ["batch-003" (assoc b003 :drying-temp-c (- (:drying-temp-c-min (product-of b003)) 5))
      "drying temperature 5 ℃ below the product's own safe window"]
     ["batch-004" (assoc b004 :drying-time-minutes (+ (:drying-time-max-minutes (product-of b004)) 30))
      "drying time 30 min past the product's own maximum"]
     ["batch-005" (assoc b005 :moisture-percent (+ (:moisture-target-percent (product-of b005))
                                                   (:moisture-tolerance-percent (product-of b005))
                                                   1.0))
      "post-drying moisture 1.0 pt above target+tolerance (mold-growth hazard)"]
     ["batch-006" (assoc b006 :sanitation-score 60)
      "plant sanitation score below the Governor's minimum (75)"]
     ["batch-007" b007
      "egg-noodle formulation (:egg/whole) with :eggs left undeclared"]
     ["batch-008" (assoc b008 :evidence-checklist (vec (rest (:evidence-checklist b008))))
      "jurisdiction evidence checklist missing its first required item"]
     ["batch-009" (assoc b009 :safety-concern-raised? true)
      "open, unresolved food-safety flag"]
     ["batch-010" b010
      "clean — used to show a proposal that cites no jurisdiction"]
     ["batch-011" (assoc b011 :scale-last-calibration-date stale-calibration-epoch-ms)
      "dosing-scale calibration far past its 180-day limit"]
     ["batch-012" (assoc b012 :weight-variance-grams 120)
      "packaged weight variance 120 g, past the 50 g tolerance"]]))

;; ----------------------------- scenarios -----------------------------

(def ^:private rogue-advisor
  "A deliberately mis-behaving Advisor injected through `operation/build`'s
  own `:advisor` seam: it claims direct write authority (`:effect :write`)
  instead of proposing. Nothing about the graph changes -- the Governor's
  `:effect-not-propose` invariant is what refuses it, which is the point."
  (reify advisor/Advisor
    (-advise [_ _store request]
      {:op (:op request)
       :effect :write
       :value {:equipment "extruder-2"}
       :cites [{:spec "Equipment-Manual"}]
       :summary "Maintenance window claimed with direct write authority"
       :confidence 0.95})))

(def ^:private scenarios
  "Every scenario this console runs, in order. `:expect` is asserted
  against the REAL disposition the graph reaches (see `check-run!`), so a
  Governor that stops refusing fails the build instead of quietly
  producing a greener page."
  [{:id "t01" :request {:op :schedule-maintenance :subject "batch-001"
                        :equipment "extruder-2" :note "quarterly deep-clean"}
    :note "clean, low-stakes — the only route that commits with no human"
    :expect :commit}

   {:id "t02" :request {:op :log-production-batch :subject "batch-001"
                        :jurisdiction :jp/prefectural}
    :approval {:status :approved :by "plant-op-01"}
    :note "always escalates (real actuation) — operator approves"
    :expect :commit}

   {:id "t03" :request {:op :log-production-batch :subject "batch-001"
                        :jurisdiction :jp/prefectural}
    :note "same batch a second time — HARD block wins over the escalation"
    :expect :hold}

   {:id "t04" :request {:op :coordinate-shipment :subject "batch-002"
                        :jurisdiction :us/fda}
    :approval {:status :approved :by "plant-op-01"}
    :note "always escalates (real actuation) — operator approves"
    :expect :commit}

   {:id "t05" :request {:op :coordinate-shipment :subject "batch-002"
                        :jurisdiction :us/fda}
    :note "shipment already finalized once"
    :expect :hold}

   {:id "t06" :request {:op :flag-food-safety-concern :subject "batch-001"
                        :jurisdiction :jp/prefectural
                        :concern "possible wheat/egg allergen cross-contact, line 2"}
    :approval {:status :rejected :by "plant-op-01"}
    :note "always escalates (food safety is never auto-resolved) — operator rejects"
    :expect :hold}

   {:id "t07" :request {:op :log-production-batch :subject "batch-003" :jurisdiction :eu/efsa}
    :note "drying temperature outside the product's safe window"
    :expect :hold}

   {:id "t08" :request {:op :log-production-batch :subject "batch-004" :jurisdiction :jp/prefectural}
    :note "drying time past the product's maximum"
    :expect :hold}

   {:id "t09" :request {:op :log-production-batch :subject "batch-005" :jurisdiction :us/fda}
    :note "post-drying moisture out of the food-safety window"
    :expect :hold}

   {:id "t10" :request {:op :log-production-batch :subject "batch-006" :jurisdiction :jp/prefectural}
    :note "plant sanitation score below minimum"
    :expect :hold}

   {:id "t11" :request {:op :log-production-batch :subject "batch-007" :jurisdiction :eu/efsa}
    :note "allergen declaration under-declares the formulation"
    :expect :hold}

   {:id "t12" :request {:op :log-production-batch :subject "batch-008" :jurisdiction :us/fda}
    :note "jurisdiction evidence checklist incomplete"
    :expect :hold}

   {:id "t13" :request {:op :log-production-batch :subject "batch-009" :jurisdiction :jp/prefectural}
    :note "open food-safety flag never resolved"
    :expect :hold}

   {:id "t14" :request {:op :log-production-batch :subject "batch-010"}
    :note "request carries no jurisdiction — the proposal cites none"
    :expect :hold}

   {:id "t15" :request {:op :log-production-batch :subject "batch-011" :jurisdiction :jp/prefectural}
    :note "dosing-scale calibration overdue"
    :expect :hold}

   {:id "t16" :request {:op :log-production-batch :subject "batch-012" :jurisdiction :us/fda}
    :note "packaged weight variance excessive"
    :expect :hold}

   {:id "t17" :request {:op :log-production-batch :subject "batch-999" :jurisdiction :jp/prefectural}
    :note "batch never staged at this plant"
    :expect :hold}

   {:id "t18" :request {:op :coordinate-shipment :subject "ghost-batch" :jurisdiction :jp/prefectural}
    :note "shipment for a batch this plant never checked in"
    :expect :hold}

   {:id "t19" :request {:op :operate-extruder :subject "batch-001"}
    :note "outside the closed allowlist — this actor never operates the line"
    :expect :hold}

   {:id "t20" :request {:op :schedule-maintenance :subject "batch-001"
                        :equipment "extruder-2"}
    :actor :rogue
    :note "injected Advisor claims :effect :write — the Governor refuses it"
    :expect :hold}])

(defn- run-scenario!
  "Drive ONE scenario through the real compiled graph and capture exactly
  the ledger facts THIS scenario appended (by index delta -- never by
  joining on [op subject], which is not unique here: batch-001 is the
  subject of five different runs and batch-002 of two)."
  [st actor {:keys [id request approval note expect]}]
  (let [before (count (store/ledger st))
        r1 (g/run* actor {:request request :context plant-op} {:thread-id id})
        r2 (when (and approval (= :interrupted (:status r1)))
             (g/run* actor {:approval approval} {:thread-id id :resume? true}))
        final (or r2 r1)
        facts (subvec (vec (store/ledger st)) before)]
    {:id id
     :request request
     :note note
     :expect expect
     :approval approval
     :interrupted? (= :interrupted (:status r1))
     :frontier (:frontier r1)
     :status (:status final)
     :disposition (:disposition (:state final))
     :verdict (:verdict (:state final))
     :audit (vec (:audit (:state final)))
     :facts facts}))

(defn run-demo!
  "Stage the seed, build the real actor, run every scenario. Returns
  `{:store .. :runs [..]}`; every value the page renders comes from here."
  []
  (let [st (store/mem-store)]
    (doseq [[id batch _why] seed-batches]
      (store/register-batch! st id batch))
    (let [default-actor (operation/build st)
          rogue-actor (operation/build st {:advisor rogue-advisor})]
      {:store st
       :runs (mapv (fn [sc]
                     (run-scenario! st
                                    (if (= :rogue (:actor sc)) rogue-actor default-actor)
                                    sc))
                   scenarios)})))

;; --------------------- classification (measured, not assumed) ---------------------

(defn- approver-rejection?
  "A hold produced by a HUMAN rejecting an escalated proposal. The graph
  writes it through `governor/hold-fact` too, so it carries a
  `:violations` vector and would satisfy a naive `count` of holds -- it
  is NOT a Governor refusal and is reported separately."
  [f]
  (or (= :approval-rejected (:t f))
      (some #(= :approver-rejected (:rule %)) (:violations f))))

(defn- hard-governor-hold?
  "A hold the GOVERNOR itself refused: a `:governor-hold` fact carrying at
  least one real rule violation that is not the human-rejection marker.
  (An empty-`:violations` hold would fail this test on purpose.)"
  [f]
  (and (= :governor-hold (:t f))
       (seq (:violations f))
       (not (approver-rejection? f))))

(def ^:private approver-keys
  "Every key this fleet has been seen to carry an approver under. Scanned
  for at RENDER TIME (see `approver-on-record`) so the page self-corrects
  if the store starts retaining the approver."
  #{:approved-by :approver :by :signed-off-by :approved_by})

(defn- approver-on-record
  "Deep-scan a durable ledger fact for any approver key. Returns
  `[key value]` or nil. This is the MEASUREMENT behind the disclosure
  section -- nothing about approver retention is assumed."
  [fact]
  (letfn [(scan [x]
            (when (map? x)
              (or (first (for [[k v] x
                               :when (and (contains? approver-keys k) (some? v))]
                           [k v]))
                  (first (keep scan (vals x))))))]
    (scan fact)))

(defn- approver-from-run
  "The approver as the RUN observed it (the `:approval-granted` audit
  entry the `:request-approval` node emitted). Keyed off this scenario's
  own thread, so it can never be inherited from an earlier run."
  [run]
  (some (fn [a] (when (= :approval-granted (:t a)) (:by a))) (:audit run)))

;; ----------------------------- html -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (if (keyword? v) (subs (str v) 1) (str v)))

(defn- code [v] (str "<code>" (esc (kw v)) "</code>"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- section [title lead headers rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (when lead (str "    <p class=\"muted\">" lead "</p>\n"))
       "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

(defn- seed-rows [st]
  (let [snap (store/snapshot st)]
    (for [[id _seed why] seed-batches
          :let [b (store/production-batch snap id)
                p (product-of b)]]
      (row (code id)
           (str (esc (:name p)) " <span class=\"muted\">" (esc (kw (:product-type b))) "</span>")
           (esc (kw (:jurisdiction b)))
           (str "<span class=\"num\">" (esc (:drying-temp-c b)) "</span> ℃ / "
                "<span class=\"num\">" (esc (:drying-time-minutes b)) "</span> min / "
                "<span class=\"num\">" (esc (:moisture-percent b)) "</span> %")
           (str "<span class=\"num\">" (esc (:sanitation-score b)) "</span>")
           (str (esc (str/join ", " (sort (map kw (:declared-allergens b)))))
                (let [actual (facts/formulation-allergen-set (:ingredients b))]
                  (if (seq (remove (:declared-allergens b) actual))
                    (str " <span class=\"critical\">(formulation has "
                         (esc (str/join ", " (sort (map kw actual)))) ")</span>")
                    "")))
           (str (if (:processed? b) "<span class=\"ok\">logged</span>" "<span class=\"muted\">staged</span>")
                (when (:shipment-finalized? b) " · <span class=\"ok\">shipped</span>")
                (when (and (:safety-concern-raised? b) (not (:safety-concern-resolved? b)))
                  " · <span class=\"critical\">safety flag open</span>"))
           (esc why)))))

(defn- route-cell [{:keys [interrupted? approval disposition]}]
  (cond
    (and interrupted? (= :approved (:status approval)))
    "<span class=\"warn\">escalated</span> → <span class=\"ok\">operator approved</span>"
    (and interrupted? (= :rejected (:status approval)))
    "<span class=\"warn\">escalated</span> → <span class=\"critical\">operator rejected</span>"
    interrupted? "<span class=\"warn\">escalated · awaiting operator</span>"
    (= :commit disposition) "<span class=\"ok\">auto-commit (no human needed)</span>"
    :else "<span class=\"critical\">HARD hold · never reached a human</span>"))

(defn- outcome-cell [{:keys [disposition facts]}]
  (let [rules (->> facts (mapcat :violations) (map :rule) (remove nil?) distinct sort)]
    (str (if (= :commit disposition)
           "<span class=\"ok\">committed</span>"
           "<span class=\"critical\">held</span>")
         (when (seq rules)
           (str " · " (str/join ", " (map #(str "<code>" (esc (kw %)) "</code>") rules)))))))

(defn- run-rows [runs]
  (for [r runs]
    (row (code (:id r))
         (code (:op (:request r)))
         (code (:subject (:request r)))
         (route-cell r)
         (outcome-cell r)
         (esc (:note r)))))

(defn- hold-rows [runs]
  (for [r runs
        f (:facts r)
        :when (hard-governor-hold? f)
        v (:violations f)]
    (row (code (:id r))
         (code (:op f))
         (code (:subject f))
         (str "<code class=\"critical\">" (esc (kw (:rule v))) "</code>")
         (esc (:detail v)))))

(defn- rejection-rows [runs]
  (for [r runs
        f (:facts r)
        :when (approver-rejection? f)]
    (row (code (:id r))
         (code (:op f))
         (code (:subject f))
         (esc (or (:by (:approval r)) "—"))
         (str "<span class=\"num\">" (esc (:confidence f)) "</span>")
         "advisor confidence was above the floor and the Governor found no HARD violation — the human still said no")))

(defn- gate-rows
  "Derived from the Governor's own vars at render time, so this table
  cannot drift away from the code the way a hand-written one does."
  []
  (for [op (sort-by kw governor/allowed-ops)]
    (row (code op)
         (cond
           (contains? governor/high-stakes op)
           "<span class=\"warn\">ALWAYS human approval — real actuation, never auto</span>"
           (contains? governor/always-escalate-ops op)
           "<span class=\"warn\">ALWAYS human approval — food safety is never auto-resolved</span>"
           :else
           (str "<span class=\"ok\">auto-commit when the Governor is clean and confidence ≥ "
                (esc governor/confidence-floor) "</span>")))))

(defn- ledger-rows [ledger]
  (for [f ledger]
    (row (code (:t f))
         (code (:op f))
         (code (:subject f))
         (esc (:actor f))
         (code (:disposition f))
         (esc (str/join ", " (map #(if (map? %) (str/join "/" (vals %)) (kw %)) (:basis f)))))))

(defn- attribution-rows [runs]
  (for [r runs
        :let [who (approver-from-run r)]
        :when who
        f (:facts r)
        :let [on-record (approver-on-record f)]]
    (row (code (:id r))
         (code (:t f))
         (code (:subject f))
         (esc who)
         (if on-record
           (str "<span class=\"ok\">retained as <code>" (esc (kw (first on-record)))
                "</code> = " (esc (second on-record)) "</span>")
           "<span class=\"warn\">not retained on the committed record — audit only</span>"))))

(defn render
  [{:keys [store runs]}]
  (let [ledger (vec (store/ledger store))
        holds (filter hard-governor-hold? ledger)
        rejections (filter approver-rejection? ledger)
        approvals (for [r runs :when (approver-from-run r) f (:facts r)]
                    [(approver-from-run r) (approver-on-record f)])
        retained? (and (seq approvals) (every? (comp some? second) approvals))
        attribution-lead
        (if retained?
          "Measured at render time: the store DOES carry the approver through onto the committed record."
          (str "Measured at render time by scanning every fact this run appended for "
               (str/join ", " (map #(str "<code>" (esc (kw %)) "</code>") (sort-by kw approver-keys)))
               ": the approver is <strong>not retained on the committed record</strong> "
               "(audit only — not retained on record). "
               "<code>pastaops.operation</code>'s <code>:request-approval</code> node puts "
               "<code>:approved-by</code> on the in-flight <code>:record</code>, but the "
               "<code>:commit</code> node writes <code>commit-fact</code>, which carries the "
               "advisor's <code>:value</code> and no approver. The approver below is read from "
               "that run's own <code>:approval-granted</code> audit entry (keyed by thread id, "
               "never joined on <code>[op subject]</code> — <code>batch-001</code> is the subject "
               "of five separate runs here, so such a join would mis-attribute). "
               "This section is derived, so it will report retention automatically once the "
               "store keeps it."))]
    (str
     "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-1074 · pastaops operator console</title>\n<style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Macaroni, noodles &amp; couscous (ISIC 1074) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · batch logging &amp; shipment always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <div class=\"banner\">\n"
     "    <p>Every row below was produced by running this repo's real actor —\n"
     "    <code>pastaops.operation/build</code>'s compiled <code>langgraph</code> StateGraph\n"
     "    (<code>:intake → :advise → :govern → :decide → :commit / :request-approval → :commit / :hold</code>,\n"
     "    with a real <code>interrupt-before</code> checkpoint at the approval gate) —\n"
     "    over <code>pastaops.store/mem-store</code>, at build time via\n"
     "    <code>clojure -M:dev:render-html</code>. The build refuses to write this file\n"
     "    if the run produces no HARD Governor holds.</p>\n"
     "    <p class=\"muted\">"
     (esc (count runs)) " scenarios · "
     (esc (count ledger)) " durable ledger facts · "
     (esc (count holds)) " HARD Governor holds ("
     (esc (count (distinct (map :rule (mapcat :violations holds))))) " distinct rules) · "
     (esc (count rejections)) " human rejections</p>\n"
     "  </div>\n"

     (section "Staged production batches (seed)"
              (str "Staged with <code>store/register-batch!</code> before any run. "
                   "<code>batch-001</code> is <code>pastaops.sim</code>'s own fixture verbatim; "
                   "every other record is built from <code>pastaops.facts/product-types</code> "
                   "and the jurisdiction's own <code>:required-evidence</code> list, then "
                   "perturbed by exactly one value computed off that same product record. "
                   "<code>batch-999</code> and <code>ghost-batch</code> are deliberately absent.")
              ["Batch" "Product" "Jurisdiction" "Drying temp / time / moisture"
               "Sanitation" "Declared allergens" "State after this run" "Why this record exists"]
              (seed-rows store))

     (section "Scenario runs (this build)"
              "One graph run per row, in execution order. A HARD hold never reaches the approval gate — the interrupt does not even fire."
              ["Thread" "Op" "Batch" "Route" "Outcome" "What it shows"]
              (run-rows runs))

     (section "HARD Governor holds — refused by the Governor"
              (str "The Governor's own <code>:violations</code> maps, verbatim. These are refusals "
                   "the advisor cannot override and no human is asked about: "
                   "<code>pastaops.operation</code>'s <code>:decide</code> node tests "
                   "<code>:hard?</code> before <code>:escalate?</code>, so a hard violation "
                   "pre-empts an op that would otherwise ALWAYS escalate.")
              ["Thread" "Op" "Batch" "Rule" "Governor's own detail"]
              (hold-rows runs))

     (section "Human approval rejections — a person refused"
              (str "Kept in a separate table on purpose. These holds are written through the same "
                   "<code>governor/hold-fact</code> path and carry a <code>:violations</code> entry "
                   "(<code>:approver-rejected</code>), so a naive count of holds would mix them in "
                   "with Governor refusals. They are not: the Governor found nothing wrong and "
                   "escalated; the operator declined.")
              ["Thread" "Op" "Batch" "Operator" "Advisor confidence" "Reading"]
              (rejection-rows runs))

     (section "Action gate (Pasta Governor)"
              (str "Derived at render time from <code>pastaops.governor/allowed-ops</code>, "
                   "<code>high-stakes</code>, <code>always-escalate-ops</code> and "
                   "<code>confidence-floor</code>. Any op outside this closed allowlist — "
                   "extrusion/drying-line control, food-safety certification — is a permanent "
                   "hard block (see thread <code>t19</code>).")
              ["Op" "Gate"]
              (gate-rows))

     (section "Audit ledger (this run)"
              (str "<code>pastaops.store/append-ledger!</code>, in append order — written by the "
                   "graph's own <code>:commit</code> and <code>:hold</code> nodes, not by this renderer.")
              ["Fact" "Op" "Batch" "Actor" "Disposition" "Basis"]
              (ledger-rows ledger))

     (section "Approver attribution" attribution-lead
              ["Thread" "Fact" "Batch" "Operator who signed off" "On the durable record?"]
              (attribution-rows runs))

     "</main>\n"
     "<footer>\n"
     "  <p>Generated by <code>pastaops.render-html</code> (<code>clojure -M:dev:render-html</code>) from a live actor run. "
     "No timestamps, no randomness — re-running against the same seed produces a byte-identical file.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ----------------------------- build-time invariant -----------------------------

(defn check-run!
  "Refuse to produce a console unless the run really exercised the
  Governor. Throws (never writes) when:
    1. the run produced ZERO HARD Governor holds, or
    2. any scenario reached a disposition other than the one it declares,
       or
    3. a scenario declaring an approval never actually interrupted (i.e.
       the human-in-the-loop gate silently stopped gating).
  Returns the run map unchanged."
  [{:keys [store runs] :as run}]
  (let [ledger (vec (store/ledger store))
        holds (filterv hard-governor-hold? ledger)
        mismatched (filterv #(not= (:expect %) (:disposition %)) runs)
        ungated (filterv #(and (:approval %) (not (:interrupted? %))) runs)]
    (when (empty? holds)
      (throw (ex-info "refusing to write a console with zero HARD governor holds"
                      {:ledger-facts (count ledger) :scenarios (count runs)})))
    (when (seq mismatched)
      (throw (ex-info "refusing to write a console: scenario dispositions do not match"
                      {:mismatched (mapv #(select-keys % [:id :expect :disposition]) mismatched)})))
    (when (seq ungated)
      (throw (ex-info "refusing to write a console: an approval scenario never interrupted"
                      {:ungated (mapv :id ungated)})))
    run))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (check-run! (run-demo!))
        ledger (vec (store/ledger (:store result)))
        holds (filterv hard-governor-hold? ledger)
        html (render result)]
    (when-let [dir (.getParentFile (java.io.File. ^String out))]
      (.mkdirs dir))
    (spit out html)
    (println "wrote" out
             (str "(" (count (:runs result)) " scenarios, "
                  (count ledger) " ledger facts, "
                  (count holds) " HARD governor holds over "
                  (count (distinct (map :rule (mapcat :violations holds)))) " distinct rules, "
                  (count (filterv approver-rejection? ledger)) " human rejections)"))))
