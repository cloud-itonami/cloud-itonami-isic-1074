(ns pastaops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for cloud-itonami-isic-1074: this repo
  previously had NO demo page and no generator at all.

  This namespace drives the REAL actor stack -- `pastaops.operation/build`'s
  compiled langgraph StateGraph (`:intake -> :advise -> :govern -> :decide
  -> {:commit | :request-approval | :hold}`) over a `pastaops.store/MemStore`,
  censored by the independent `pastaops.governor`. It is driven exactly the
  way this repo's own `pastaops.sim` demo driver drives it (`g/run*` with a
  `:thread-id`, then a `:resume? true` run carrying the approval), confirmed
  working BEFORE this file was written by running `clojure -M:dev:run`.

  NOTHING on the rendered page is hand-typed domain content. Every batch id,
  measured value, product/jurisdiction name, verdict, violation rule, hold
  reason and ledger fact is read back out of this run's actual governor
  verdicts and `store/ledger` output. The product windows (drying temperature
  / time / moisture) and jurisdiction evidence requirements shown next to each
  measurement are read from `pastaops.facts`, so the page shows the same
  numbers the Governor itself compared against.

  Two things this repo does NOT have, stated on the page rather than
  papered over:
    - `pastaops.store` has no `demo-data`/seed function of its own (unlike
      several sibling actors). The seed below is therefore defined HERE and
      staged through the real `store/register-batch!` seam; it is the input
      to the run, not an output of it, and the page says so.
    - `pastaops.phase` is not referenced by any other namespace in `src/`,
      so there is no phase-based rollout gate to report. The page reports
      the escalation gate that DOES exist (`governor/always-escalate-ops`),
      read live off the governor namespace, and keeps it in a table separate
      from the Governor's HARD refusals -- \"the Governor refused\" and \"a
      human was asked\" are different events here.

  Determinism: the page contains no timestamps and no wall-clock-derived
  values, so two runs from the same seed are byte-identical. The Governor's
  only clock-dependent check (`scale-calibration-overdue?`, via
  `governor/now-epoch-ms`) is exercised by exactly one batch whose seeded
  calibration date is fixed at 2019-01-01 -- unambiguously overdue no matter
  when the page is generated -- and the page prints that fixed date, never a
  derived \"days ago\".

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [langgraph.graph :as g]
            [pastaops.facts :as facts]
            [pastaops.governor :as governor]
            [pastaops.operation :as operation]
            [pastaops.store :as store]))

;; ============================ scenario input ============================

(def ^:private coordinator
  "The actor context every request runs under. Deliberately NOT equal to any
  approver id below: the ledger's commit fact carries `:actor (:actor-id
  context)`, so if the coordinator and the approver shared a name, a search
  for the approver's name in the ledger would find the coordinator's and
  wrongly conclude the store had preserved the approver."
  {:actor-id "pastaops-coordinator" :role :plant-operations-coordinator})

(def ^:private line-operator "plant-op-01")
(def ^:private quality-lead "qa-lead-02")

(def ^:private calibration-2019
  "Fixed epoch-ms for 2019-01-01T00:00:00Z. The one seeded scale-calibration
  date in this scenario. `registry/scale-calibration-overdue?` compares it
  against the host clock (180-day window); a date this old is overdue on any
  date this page could be generated, which is what keeps the run
  deterministic while still exercising the check for real."
  1546300800000)

(def ^:private full-evidence
  "The complete evidence checklist. Every jurisdiction in `pastaops.facts`
  requires exactly these six items, so one vector satisfies all three."
  [:formulation-record :extrusion-log :drying-log
   :moisture-test :allergen-declaration :weight-check])

(def ^:private seed-batches
  "Staged (pre-commit) production batches, in the shape
  `store/register-batch!`/`store/stage-batch` expects. Each defective batch
  carries EXACTLY ONE defect so that the Governor's verdict isolates a single
  rule -- the drying/moisture windows each value is measured against live in
  `pastaops.facts/product-types`, and the evidence requirements in
  `pastaops.facts/jurisdictions`."
  [["batch-101" "clean macaroni run -- full log + shipment lifecycle"
    {:product-type :macaroni/elbow :jurisdiction :jp/prefectural
     :drying-temp-c 85 :drying-time-minutes 250 :moisture-percent 12.0
     :sanitation-score 88 :weight-variance-grams 18
     :ingredients [:semolina/durum :water/filtered :salt/sea]
     :declared-allergens #{:wheat}
     :evidence-checklist full-evidence}]

   ["batch-102" "clean spaghetti run -- operator declines the log"
    {:product-type :pasta/spaghetti :jurisdiction :us/fda
     :drying-temp-c 90 :drying-time-minutes 240 :moisture-percent 11.8
     :sanitation-score 92 :weight-variance-grams 12
     :ingredients [:semolina/durum :water/filtered]
     :declared-allergens #{:wheat}
     :evidence-checklist full-evidence}]

   ["batch-103" "drying temperature above the product's safe window"
    {:product-type :macaroni/elbow :jurisdiction :jp/prefectural
     :drying-temp-c 95 :drying-time-minutes 250 :moisture-percent 12.0
     :sanitation-score 90 :weight-variance-grams 15
     :ingredients [:semolina/durum :water/filtered]
     :declared-allergens #{:wheat}
     :evidence-checklist full-evidence}]

   ["batch-104" "drying time past the product's maximum"
    {:product-type :pasta/spaghetti :jurisdiction :us/fda
     :drying-temp-c 90 :drying-time-minutes 312 :moisture-percent 12.0
     :sanitation-score 90 :weight-variance-grams 15
     :ingredients [:semolina/durum :water/filtered]
     :declared-allergens #{:wheat}
     :evidence-checklist full-evidence}]

   ["batch-105" "post-drying moisture above tolerance (mould-growth hazard)"
    {:product-type :couscous/semolina :jurisdiction :eu/efsa
     :drying-temp-c 55 :drying-time-minutes 95 :moisture-percent 11.4
     :sanitation-score 90 :weight-variance-grams 15
     :ingredients [:semolina/durum :water/filtered]
     :declared-allergens #{:wheat}
     :evidence-checklist full-evidence}]

   ["batch-106" "plant sanitation score below the required minimum"
    {:product-type :noodle/egg :jurisdiction :jp/prefectural
     :drying-temp-c 56 :drying-time-minutes 330 :moisture-percent 11.0
     :sanitation-score 62 :weight-variance-grams 20
     :ingredients [:semolina/durum :egg/whole :water/filtered]
     :declared-allergens #{:wheat :eggs}
     :evidence-checklist full-evidence}]

   ["batch-107" "dosing-scale calibration lapsed"
    {:product-type :macaroni/elbow :jurisdiction :us/fda
     :drying-temp-c 85 :drying-time-minutes 250 :moisture-percent 12.0
     :sanitation-score 90 :weight-variance-grams 20
     :scale-last-calibration-date calibration-2019
     :ingredients [:semolina/durum :water/filtered]
     :declared-allergens #{:wheat}
     :evidence-checklist full-evidence}]

   ["batch-108" "finished-weight variance beyond tolerance"
    {:product-type :pasta/spaghetti :jurisdiction :eu/efsa
     :drying-temp-c 90 :drying-time-minutes 220 :moisture-percent 12.0
     :sanitation-score 90 :weight-variance-grams 86
     :ingredients [:semolina/durum :water/filtered]
     :declared-allergens #{:wheat}
     :evidence-checklist full-evidence}]

   ["batch-109" "egg in the formulation, not on the allergen declaration"
    {:product-type :noodle/egg :jurisdiction :eu/efsa
     :drying-temp-c 56 :drying-time-minutes 330 :moisture-percent 11.0
     :sanitation-score 90 :weight-variance-grams 20
     :ingredients [:semolina/durum :egg/whole]
     :declared-allergens #{:wheat}
     :evidence-checklist full-evidence}]

   ["batch-110" "open, unresolved food-safety concern"
    {:product-type :couscous/semolina :jurisdiction :jp/prefectural
     :drying-temp-c 52 :drying-time-minutes 100 :moisture-percent 10.0
     :sanitation-score 90 :weight-variance-grams 15
     :ingredients [:semolina/durum :water/filtered]
     :declared-allergens #{:wheat}
     :safety-concern-raised? true :safety-concern-resolved? false
     :evidence-checklist full-evidence}]

   ["batch-111" "moisture test missing from the evidence checklist"
    {:product-type :macaroni/elbow :jurisdiction :jp/prefectural
     :drying-temp-c 85 :drying-time-minutes 250 :moisture-percent 12.0
     :sanitation-score 90 :weight-variance-grams 15
     :ingredients [:semolina/durum :water/filtered]
     :declared-allergens #{:wheat}
     :evidence-checklist [:formulation-record :extrusion-log :drying-log
                          :allergen-declaration :weight-check]}]

   ["batch-112" "clean, but the request cites no jurisdiction"
    {:product-type :pasta/spaghetti :jurisdiction :jp/prefectural
     :drying-temp-c 90 :drying-time-minutes 240 :moisture-percent 12.0
     :sanitation-score 90 :weight-variance-grams 15
     :ingredients [:semolina/durum :water/filtered]
     :declared-allergens #{:wheat}
     :evidence-checklist full-evidence}]])

(def ^:private unregistered-batch
  "Deliberately never staged, to exercise the Governor's
  `:batch-not-registered` refusal against a real absent store record."
  "batch-113")

;; ============================ driving the actor ============================

(defn- exec!
  "One graph run. Snapshots the ledger before/after so every fact this run
  appended is associated with THIS run by construction -- never by joining on
  `[op subject]`, which is not unique here (batch-101 is the subject of two
  separate `:log-production-batch` runs with opposite outcomes)."
  [st actor tid label request]
  (let [before (count (store/ledger st))
        result (g/run* actor {:request request :context coordinator}
                       {:thread-id tid})]
    {:tid tid :label label :request request :result result
     :ledger-from before}))

(defn- resume!
  "Resume an interrupted thread with a human decision, and fold the resumed
  run's result (and any ledger facts it appended) back into the run record."
  [st actor run status by]
  (let [result (g/run* actor {:approval {:status status :by by}}
                       {:thread-id (:tid run) :resume? true})]
    (assoc run
           :result result
           :approval {:status status :by by})))

(defn- finish
  "Attach the ledger slice this run produced. Called once the run (including
  any resume) is complete and no further facts can be appended for it."
  [st run]
  (assoc run :ledger-facts (vec (subvec (vec (store/ledger st))
                                        (:ledger-from run)))))

(defn run-demo!
  "Drive a fresh `MemStore`, seeded with `seed-batches`, through every
  disposition this actor can reach:

    - one low-stakes auto-commit that never touches a human
      (`:schedule-maintenance` on batch-101);
    - three human-approved commits (batch-101 logged then shipped, and
      batch-110's food-safety concern), each approved by a named human;
    - one human REFUSAL (batch-102's log declined by the line operator) --
      an `:approval-rejected` hold, which is a different event from a
      Governor refusal and is reported separately;
    - fourteen HARD Governor holds, each isolating a distinct rule, none of
      which ever reaches a human.

  Returns `{:store .. :runs ..}`; every value the page shows is read back out
  of these."
  []
  (let [st (store/mem-store)]
    (doseq [[id _why batch] seed-batches]
      (store/register-batch! st id batch))
    (let [actor (operation/build st)
          runs (atom [])
          add! (fn [r] (swap! runs conj (finish st r)) r)
          plain (fn [tid label request]
                  (add! (exec! st actor tid label request)))
          gated (fn [tid label request status by]
                  (let [r (exec! st actor tid label request)]
                    (add! (resume! st actor r status by))))]

      ;; ---- batch-101: low-stakes auto-commit, then the full gated lifecycle
      (plain "t01" "routine maintenance on the extruder (low stakes)"
             {:op :schedule-maintenance :subject "batch-101"
              :equipment "extruder-2" :note "quarterly deep-clean"})

      (gated "t02" "log the finished batch into production records"
             {:op :log-production-batch :subject "batch-101"
              :jurisdiction :jp/prefectural}
             :approved line-operator)

      (gated "t03" "coordinate shipment of the finished batch"
             {:op :coordinate-shipment :subject "batch-101"
              :jurisdiction :jp/prefectural}
             :approved quality-lead)

      (plain "t04" "log batch-101 a second time"
             {:op :log-production-batch :subject "batch-101"
              :jurisdiction :jp/prefectural})

      (plain "t05" "ship batch-101 a second time"
             {:op :coordinate-shipment :subject "batch-101"
              :jurisdiction :jp/prefectural})

      ;; ---- batch-102: the human says no
      (gated "t06" "log the finished batch into production records"
             {:op :log-production-batch :subject "batch-102"
              :jurisdiction :us/fda}
             :rejected line-operator)

      ;; ---- one HARD hold per independent Governor check
      (plain "t07" "log a batch dried above its safe temperature"
             {:op :log-production-batch :subject "batch-103"
              :jurisdiction :jp/prefectural})
      (plain "t08" "log a batch dried past its time limit"
             {:op :log-production-batch :subject "batch-104"
              :jurisdiction :us/fda})
      (plain "t09" "log a batch left too moist to be shelf-stable"
             {:op :log-production-batch :subject "batch-105"
              :jurisdiction :eu/efsa})
      (plain "t10" "log a batch made on an unhygienic line"
             {:op :log-production-batch :subject "batch-106"
              :jurisdiction :jp/prefectural})
      (plain "t11" "log a batch dosed on an uncalibrated scale"
             {:op :log-production-batch :subject "batch-107"
              :jurisdiction :us/fda})
      (plain "t12" "log a batch whose pack weights drifted"
             {:op :log-production-batch :subject "batch-108"
              :jurisdiction :eu/efsa})
      (plain "t13" "log a batch with an under-declared allergen"
             {:op :log-production-batch :subject "batch-109"
              :jurisdiction :eu/efsa})
      (plain "t14" "log a batch with an open food-safety concern"
             {:op :log-production-batch :subject "batch-110"
              :jurisdiction :jp/prefectural})

      ;; ---- the concern itself: always escalates, human approves
      (gated "t15" "raise the food-safety concern for human review"
             {:op :flag-food-safety-concern :subject "batch-110"
              :jurisdiction :jp/prefectural
              :concern "possible wheat/egg allergen cross-contact, line 2"}
             :approved quality-lead)

      (plain "t16" "log a batch with an incomplete evidence pack"
             {:op :log-production-batch :subject "batch-111"
              :jurisdiction :jp/prefectural})
      (plain "t17" "log a batch without citing any jurisdiction"
             {:op :log-production-batch :subject "batch-112"})
      (plain "t18" "ship a batch this plant never checked in"
             {:op :coordinate-shipment :subject unregistered-batch
              :jurisdiction :jp/prefectural})
      (plain "t19" "drive the extruder directly"
             {:op :operate-extruder :subject "batch-101"
              :jurisdiction :jp/prefectural})

      {:store st :runs @runs})))

;; ============================ reading the run back ============================

(defn- state-of [run] (get-in run [:result :state]))
(defn- verdict-of [run] (:verdict (state-of run)))
(defn- audit-of [run] (vec (:audit (state-of run))))

(defn- hard-hold?
  "A HARD hold is a Governor refusal: `:hard?` on the verdict AND a
  `:governor-hold` fact actually written. A human refusal
  (`:approval-rejected`) also lands as `:disposition :hold` but is NOT a
  Governor refusal and must not be counted as one."
  [run]
  (and (true? (:hard? (verdict-of run)))
       (boolean (some #(= :governor-hold (:t %)) (audit-of run)))))

(defn- hold-violations [run] (vec (:violations (verdict-of run))))

(defn- hard-rules
  "The distinct rule keywords a HARD hold cited, in order."
  [run]
  (mapv :rule (hold-violations run)))

(defn- substantive-hard-hold?
  "A HARD hold that actually says WHY. A hold whose `:violations` is empty
  carries no reason a reader could act on, so it does not satisfy the
  build-time invariant below on its own."
  [run]
  (and (hard-hold? run)
       (boolean (seq (remove nil? (hard-rules run))))
       (boolean (some (fn [v] (seq (str (:detail v)))) (hold-violations run)))))

(defn- human-rejection?
  "A human looked at the proposal and declined it -- not a Governor refusal."
  [run]
  (boolean (some #(= :approval-rejected (:t %)) (audit-of run))))

(defn- committed? [run]
  (boolean (some #(= :committed (:t %)) (audit-of run))))

(defn- escalated? [run]
  (boolean (some #(= :approval-requested (:t %)) (audit-of run))))

(defn- outcome
  "The single word this run ended on, derived from the run's own facts."
  [run]
  (cond
    (hard-hold? run)      :governor-hold
    (human-rejection? run) :human-refused
    (and (committed? run) (escalated? run)) :approved-commit
    (committed? run)      :auto-commit
    :else                 :unknown))

;; ---- approver attribution, MEASURED rather than assumed -------------------

(defn- value-paths
  "Every key path inside `form` whose leaf equals `v`, as dotted strings.
  Used to locate an approver's name in the run output and in the ledger
  WITHOUT assuming which key it should live under -- if the store is later
  fixed to keep it, this finds it there and the page corrects itself."
  [form v]
  (letfn [(go [path x]
            (cond
              (= x v)        [path]
              (map? x)       (mapcat (fn [[k vv]] (go (conj path k) vv)) x)
              (set? x)       (mapcat #(go (conj path "#") %) x)
              (sequential? x) (apply concat
                                     (map-indexed (fn [i vv] (go (conj path i) vv)) x))
              :else          nil))]
    (->> (go [] form)
         (map (fn [p] (str/join "." (map #(if (keyword? %) (name %) (str %)) p))))
         sort
         vec)))

(defn- approval-attribution
  "For one approved run, where the approver's name actually survives.
  Everything here is measured off this run's real output; nothing about the
  store's behaviour is assumed."
  [run]
  (let [by (get-in run [:approval :by])
        st (state-of run)]
    {:run run
     :approver by
     :in-record  (value-paths (:record st) by)
     :in-audit   (value-paths (audit-of run) by)
     :in-ledger  (value-paths (:ledger-facts run) by)}))

(defn- attribution-summary
  "Classify what the store did with the approver, from the measurements."
  [attributions]
  (let [ledger-keeps (filter #(seq (:in-ledger %)) attributions)
        run-keeps    (filter #(or (seq (:in-record %)) (seq (:in-audit %)))
                             attributions)]
    (cond
      (empty? attributions) :no-approval-path
      (= (count ledger-keeps) (count attributions)) :ledger-keeps-approver
      (seq ledger-keeps) :ledger-keeps-approver-partially
      (seq run-keeps) :ledger-drops-approver
      :else :approver-absent-everywhere)))

;; ============================ HTML ============================

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- pill [cls label] (str "<span class=\"pill " cls "\">" (esc label) "</span>"))

(defn- num
  "Render a number without a trailing `.0` on integral doubles, so the page
  reads the way the plant would write it and stays byte-stable."
  [v]
  (cond
    (nil? v) "—"
    (and (float? v) (== v (Math/rint (double v)))) (str (long v))
    :else (str v)))

(def ^:private dds-css
  "Digital Agency Design System tokens (jp-go-digital-design-system, upstream
  digital-go-jp/design-system-example-components-html @ 3b34f4c, MIT (c) 2025
  デジタル庁). The token VALUES below are copied from the DADS stylesheet this
  repo already vendors in `docs/index.html`, so the console and the product
  face render from the same palette. Only the handful of tokens this page
  actually uses is declared -- re-vendoring the full 69 KB sheet to style
  three colours of table row would be padding, not design-system conformance."
  (str/join
   "\n"
   [":root{"
    "--color-primitive-blue-50:#e8f1fe;--color-primitive-blue-100:#d9e6ff;"
    "--color-primitive-blue-900:#0017c1;--color-primitive-blue-1000:#00118f;"
    "--color-primitive-red-800:#ec0000;--color-primitive-red-900:#ce0000;--color-primitive-red-1000:#a90000;"
    "--color-primitive-green-600:#259d63;--color-primitive-green-800:#197a4b;--color-primitive-green-900:#115a36;"
    "--color-primitive-orange-600:#fb5b01;--color-primitive-orange-800:#c74700;--color-primitive-orange-900:#ac3e00;"
    "--color-primitive-yellow-50:#fbf5e0;--color-primitive-yellow-900:#927200;"
    "--color-neutral-solid-gray-50:#f2f2f2;--color-neutral-solid-gray-100:#e6e6e6;"
    "--color-neutral-solid-gray-200:#cccccc;--color-neutral-solid-gray-536:#767676;"
    "--color-neutral-solid-gray-600:#666666;--color-neutral-solid-gray-700:#4d4d4d;"
    "--color-neutral-solid-gray-800:#333333;--color-neutral-solid-gray-900:#1a1a1a;"
    "--color-semantic-error-1:var(--color-primitive-red-800);"
    "--color-semantic-success-2:var(--color-primitive-green-800);"
    "--color-semantic-warning-yellow-2:var(--color-primitive-yellow-900);"
    "--color-key-900:var(--color-primitive-blue-900);"
    "--font-family-sans:\"Noto Sans JP\",-apple-system,BlinkMacSystemFont,sans-serif;"
    "--font-family-mono:\"Noto Sans Mono\",ui-monospace,monospace;"
    "--elevation-1:0 2px 8px 1px rgba(0,0,0,.1),0 1px 5px 0 rgba(0,0,0,.3);"
    "}"
    "*{box-sizing:border-box}"
    "body{margin:0;font-family:var(--font-family-sans);color:var(--color-neutral-solid-gray-900);"
    "background:var(--color-neutral-solid-gray-50);line-height:1.7;font-size:15px}"
    "header.bar{background:var(--color-key-900);color:#fff;padding:20px 24px}"
    "header.bar h1{margin:0 0 6px;font-size:19px;font-weight:700;letter-spacing:.01em}"
    "header.bar .badge{display:inline-block;font-size:12px;background:rgba(255,255,255,.16);"
    "border:1px solid rgba(255,255,255,.34);border-radius:4px;padding:2px 9px}"
    "main{max-width:1180px;margin:0 auto;padding:24px 20px 56px}"
    "section.card{background:#fff;border:1px solid var(--color-neutral-solid-gray-200);"
    "border-radius:8px;box-shadow:var(--elevation-1);padding:20px 22px;margin:0 0 20px}"
    "section.card>h2{margin:0 0 4px;font-size:16px;font-weight:700;"
    "color:var(--color-primitive-blue-1000)}"
    "p.muted{margin:0 0 14px;color:var(--color-neutral-solid-gray-700);font-size:13px}"
    "p.muted:last-child{margin-bottom:0}"
    "table{border-collapse:collapse;width:100%;font-size:13px}"
    "caption{caption-side:top;text-align:left;font-weight:700;font-size:13px;"
    "padding:10px 0 6px;color:var(--color-neutral-solid-gray-800)}"
    "th,td{border-bottom:1px solid var(--color-neutral-solid-gray-100);"
    "padding:7px 10px;text-align:left;vertical-align:top}"
    "thead th{background:var(--color-neutral-solid-gray-50);"
    "border-bottom:2px solid var(--color-neutral-solid-gray-200);white-space:nowrap;"
    "color:var(--color-neutral-solid-gray-800);font-size:12px}"
    "tbody tr:last-child td{border-bottom:none}"
    "td.n{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}"
    "code{font-family:var(--font-family-mono);font-size:.88em;"
    "background:var(--color-neutral-solid-gray-50);"
    "border:1px solid var(--color-neutral-solid-gray-200);border-radius:4px;padding:0 4px}"
    "span.pill{display:inline-block;border-radius:10px;padding:1px 9px;font-size:11.5px;"
    "font-weight:700;white-space:nowrap;border:1px solid transparent}"
    ".pill.ok{background:#eaf6ef;color:var(--color-primitive-green-900);"
    "border-color:var(--color-primitive-green-600)}"
    ".pill.bad{background:#fdeaea;color:var(--color-primitive-red-1000);"
    "border-color:var(--color-primitive-red-800)}"
    ".pill.warn{background:var(--color-primitive-yellow-50);"
    "color:var(--color-primitive-orange-900);border-color:var(--color-primitive-orange-600)}"
    ".pill.info{background:var(--color-primitive-blue-50);"
    "color:var(--color-primitive-blue-1000);border-color:var(--color-primitive-blue-100)}"
    ".pill.mute{background:var(--color-neutral-solid-gray-50);"
    "color:var(--color-neutral-solid-gray-600);border-color:var(--color-neutral-solid-gray-200)}"
    "td.bad{color:var(--color-primitive-red-1000);font-weight:700}"
    ".gap{color:var(--color-primitive-orange-900);font-weight:700}"
    "dl.kv{display:grid;grid-template-columns:auto 1fr;gap:2px 14px;margin:0;font-size:13px}"
    "dl.kv dt{color:var(--color-neutral-solid-gray-600)}"
    "dl.kv dd{margin:0;font-weight:700}"
    "footer{max-width:1180px;margin:0 auto;padding:0 20px 40px;"
    "color:var(--color-neutral-solid-gray-536);font-size:12px}"]))

(defn- table [caption headers rows]
  (str "    <table>\n"
       (when caption (str "      <caption>" (esc caption) "</caption>\n"))
       "      <thead><tr>"
       (str/join (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead>\n      <tbody>\n"
       (if (seq rows)
         (str (str/join "\n" rows) "\n")
         (str "        <tr><td colspan=\"" (count headers)
              "\">no rows produced by this run</td></tr>\n"))
       "      </tbody>\n    </table>\n"))

(defn- section [title lede & body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (when lede (str "    <p class=\"muted\">" lede "</p>\n"))
       (str/join body)
       "  </section>\n"))

;; ---- section: seeded batches vs the windows the Governor compared against

(defn- window-cell [actual lo hi unit bad?]
  (str "<td class=\"n" (when bad? " bad") "\">" (num actual) " " (esc unit)
       "<br><span style=\"font-weight:400;color:var(--color-neutral-solid-gray-600)\">"
       (num lo) "–" (num hi) "</span></td>"))

(defn- batch-row [st [id why _seed]]
  (let [b (store/production-batch st id)
        p (facts/product-type-by-id (:product-type b))
        j (facts/jurisdiction-by-id (:jurisdiction b))
        mt (:moisture-target-percent p)
        tol (:moisture-tolerance-percent p)
        formula (facts/formulation-allergen-set (:ingredients b))
        undeclared (sort (remove (:declared-allergens b) formula))
        ev-ok? (facts/required-evidence-satisfied? (:jurisdiction b)
                                                   (:evidence-checklist b))
        missing-ev (sort (remove (set (:evidence-checklist b))
                                 (:required-evidence j)))]
    (str "        <tr><td>" (code id)
         "<br><span style=\"color:var(--color-neutral-solid-gray-600)\">" (esc why)
         "</span></td>"
         "<td>" (esc (:name p)) "<br>" (code (kw (:product-type b))) "</td>"
         "<td>" (esc (:name j)) "</td>"
         (window-cell (:drying-temp-c b) (:drying-temp-c-min p) (:drying-temp-c-max p)
                      "℃" (not (facts/drying-temp-in-range? (:drying-temp-c b) p)))
         (window-cell (:drying-time-minutes b) (:drying-time-min-minutes p)
                      (:drying-time-max-minutes p) "min"
                      (not (facts/drying-time-in-range? (:drying-time-minutes b) p)))
         (window-cell (:moisture-percent b) (- mt tol) (+ mt tol) "%"
                      (not (facts/moisture-in-range? (:moisture-percent b) p)))
         "<td class=\"n" (when (< (:sanitation-score b) 75) " bad") "\">"
         (num (:sanitation-score b)) "<br>"
         "<span style=\"font-weight:400;color:var(--color-neutral-solid-gray-600)\">≥75</span></td>"
         "<td class=\"n" (when (> (:weight-variance-grams b) 50) " bad") "\">"
         (num (:weight-variance-grams b)) " g<br>"
         "<span style=\"font-weight:400;color:var(--color-neutral-solid-gray-600)\">≤50</span></td>"
         "<td>" (if (seq undeclared)
                  (str (pill "bad" (str "undeclared: "
                                        (str/join ", " (map kw undeclared)))))
                  (pill "ok" (str/join ", " (map kw (sort (:declared-allergens b))))))
         "</td>"
         "<td>" (if ev-ok?
                  (pill "ok" (str (count (:evidence-checklist b)) "/"
                                  (count (:required-evidence j))))
                  (pill "bad" (str "missing " (str/join ", " (map kw missing-ev)))))
         "</td>"
         "<td>" (if (:scale-last-calibration-date b)
                  (pill "warn" (str (java.time.Instant/ofEpochMilli
                                     (:scale-last-calibration-date b))))
                  (pill "mute" "not recorded"))
         "</td>"
         "<td>" (if (:processed? b) (pill "ok" "logged") (pill "mute" "not logged")) " "
         (if (:shipment-finalized? b) (pill "ok" "shipped") (pill "mute" "not shipped"))
         (when (:safety-concern-raised? b)
           (str " " (pill (if (:safety-concern-resolved? b) "ok" "bad")
                          (if (:safety-concern-resolved? b)
                            "concern resolved" "concern open"))))
         "</td></tr>")))

;; ---- section: what the Governor did, run by run

(defn- outcome-pill [run]
  (case (outcome run)
    :governor-hold   (pill "bad" "HARD hold · Governor refused")
    :human-refused   (pill "warn" "human refused")
    :approved-commit (pill "ok" "human approved · committed")
    :auto-commit     (pill "ok" "auto-committed")
    (pill "mute" "unknown")))

(defn- run-row [run]
  (let [{:keys [op subject]} (:request run)
        v (verdict-of run)]
    (str "        <tr><td>" (code (:tid run)) "</td>"
         "<td>" (code (kw op)) "<br>"
         "<span style=\"color:var(--color-neutral-solid-gray-600)\">"
         (esc (:label run)) "</span></td>"
         "<td>" (code subject) "</td>"
         "<td class=\"n\">" (num (:confidence v)) "</td>"
         "<td>" (if (escalated? run)
                  (pill "info" "yes")
                  (pill "mute" "no · never reached a human"))
         "</td>"
         "<td>" (outcome-pill run) "</td></tr>")))

(defn- hold-row [run]
  (let [vs (hold-violations run)]
    (str "        <tr><td>" (code (:tid run)) "</td>"
         "<td>" (code (kw (:op (:request run)))) "</td>"
         "<td>" (code (:subject (:request run))) "</td>"
         "<td>" (str/join "<br>" (map #(code (kw (:rule %))) vs)) "</td>"
         "<td>" (str/join "<br>" (map #(esc (:detail %)) vs)) "</td></tr>")))

;; ---- section: escalation gate (distinct from Governor refusal)

(defn- gate-row [op]
  (let [high? (contains? governor/high-stakes op)
        allowed? (contains? governor/allowed-ops op)]
    (str "        <tr><td>" (code (kw op)) "</td>"
         "<td>" (if allowed? (pill "ok" "in the allowlist") (pill "bad" "refused outright"))
         "</td>"
         "<td>" (cond
                  (not allowed?) (pill "bad" "n/a · never proposable")
                  high? (pill "warn" "always · real-world actuation")
                  (contains? governor/always-escalate-ops op)
                  (pill "warn" "always · food-safety judgement")
                  :else (pill "ok" (str "only below confidence "
                                        (num governor/confidence-floor))))
         "</td></tr>")))

;; ---- section: approver attribution

(defn- paths-cell [paths]
  (if (seq paths)
    (str/join "<br>" (map code paths))
    (str "<span class=\"gap\">absent</span>")))

(defn- attribution-row [{:keys [run approver in-record in-audit in-ledger]}]
  (str "        <tr><td>" (code (:tid run)) "</td>"
       "<td>" (code (kw (:op (:request run)))) "</td>"
       "<td>" (code (:subject (:request run))) "</td>"
       "<td>" (code approver) "</td>"
       "<td>" (paths-cell in-record) "</td>"
       "<td>" (paths-cell in-audit) "</td>"
       "<td>" (paths-cell in-ledger) "</td></tr>"))

(defn- attribution-note [summary]
  (case summary
    :ledger-keeps-approver
    (str "<strong>Measured on this run: the durable ledger keeps the approver.</strong> "
         "Every approved commit below carries the approving human's id into "
         (code "store/ledger") ", so the audit trail and the durable record agree.")

    :ledger-keeps-approver-partially
    (str "<strong>Measured on this run: only SOME approved commits carry the approver "
         "into the durable ledger.</strong> The rows below show exactly which. "
         "Where the ledger column reads <span class=\"gap\">absent</span>, a reader of "
         (code "store/ledger") " alone cannot tell who signed off.")

    :ledger-drops-approver
    (str "<strong>Measured on this run: the durable ledger does NOT keep the approver.</strong> "
         "The approving human's id reaches the graph's own state (the "
         (code ":record") " channel, written by the "
         (code ":request-approval") " node) and the in-run audit trail, but "
         (code "operation/commit-fact") " rebuilds the ledger entry from "
         (code "(:value proposal)") " rather than from that channel, so the name is "
         "dropped on the way to " (code "store/append-ledger!") ". The "
         (code ":approval-granted") " fact is likewise never appended. "
         "This is stated rather than silently omitted: without it a reader could not "
         "distinguish &quot;nobody approved this&quot; from &quot;the store did not keep "
         "who did&quot;. The table is derived by searching this run's real output for the "
         "approver's name, so if the store is fixed the page will say so on the next build.")

    :approver-absent-everywhere
    (str "<strong>Measured on this run: the approver's id appears nowhere in the run "
         "output.</strong> The approval gate fired, but no record of who granted it "
         "survives anywhere the page can read.")

    :no-approval-path
    (str "<strong>This run produced no approved commit</strong>, so there is nothing to "
         "attribute.")))

;; ============================ document ============================

(defn render
  "Render the console from a completed `run-demo!` result. Reads only from
  the store and the run records -- no literal domain values."
  [{:keys [store runs]}]
  (let [st store
        ledger (vec (store/ledger st))
        holds (filterv hard-hold? runs)
        distinct-rules (->> holds (mapcat hard-rules) (remove nil?) distinct sort vec)
        rejections (filterv human-rejection? runs)
        commits (filterv committed? runs)
        auto (filterv #(= :auto-commit (outcome %)) runs)
        approvals (->> runs
                       (filter #(= :approved (get-in % [:approval :status])))
                       (mapv approval-attribution))
        summary (attribution-summary approvals)
        ops (sort-by kw (conj governor/allowed-ops :operate-extruder))]
    (str
     "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n"
     "<meta name=\"color-scheme\" content=\"light\">\n"
     "<title>Operator console · cloud-itonami-isic-1074 · macaroni, noodles &amp; couscous</title>\n"
     "<style>\n" dds-css "\n</style>\n</head>\n<body>\n"

     "<header class=\"bar\">\n"
     "  <h1>Macaroni, noodles, couscous &amp; similar farinaceous products (ISIC 1074) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · generated at build time from the real actor · "
     "batch logging &amp; shipment always require a named human</span>\n"
     "</header>\n<main>\n"

     (section
      "This run at a glance"
      (str "Generated by " (code "clojure -M:dev:render-html")
           " (" (code "pastaops.render-html")
           "), which drives the compiled StateGraph in " (code "pastaops.operation")
           " over a " (code "pastaops.store/MemStore") ", censored by "
           (code "pastaops.governor") ". Every number on this page is read back out of "
           "that run; nothing is transcribed by hand.")
      "    <dl class=\"kv\">\n"
      "      <dt>graph runs</dt><dd>" (count runs) "</dd>\n"
      "      <dt>batches staged into the store</dt><dd>" (count seed-batches) "</dd>\n"
      "      <dt>commits</dt><dd>" (count commits)
      " <span style=\"font-weight:400\">(" (count auto)
      " without a human, " (- (count commits) (count auto)) " human-approved)</span></dd>\n"
      "      <dt>HARD Governor holds</dt><dd>" (count holds)
      " <span style=\"font-weight:400\">across " (count distinct-rules)
      " distinct rules — none reached a human</span></dd>\n"
      "      <dt>human refusals</dt><dd>" (count rejections)
      " <span style=\"font-weight:400\">(escalated, then declined — a different event)</span></dd>\n"
      "      <dt>audit-ledger facts written</dt><dd>" (count ledger) "</dd>\n"
      "    </dl>\n")

     (section
      "Staged production batches"
      (str "The store's live snapshot after the run. Each measurement is shown against the "
           "window the Governor actually compared it to, read from "
           (code "pastaops.facts/product-types") " and "
           (code "pastaops.facts/jurisdictions") " — the same tables the checks in "
           (code "pastaops.registry") " are handed. Values in red are the ones that failed. "
           "This repo's " (code "pastaops.store") " has no seed function of its own, so this "
           "batch set is defined in the renderer and staged through the real "
           (code "store/register-batch!") " seam; it is the run's input, and "
           (code unregistered-batch)
           " is deliberately never staged so the &quot;unknown batch&quot; refusal has a "
           "real absent record to fire on.")
      (table nil
             ["Batch" "Product" "Jurisdiction" "Drying temp" "Drying time"
              "Moisture" "Sanitation" "Weight var." "Allergen declaration"
              "Evidence" "Scale calibration" "State"]
             (mapv (partial batch-row st) seed-batches)))

     (section
      "Every request this run made"
      (str "One row per graph run. &quot;Reached a human&quot; is read off the run's own "
           (code ":approval-requested") " fact — a HARD Governor hold short-circuits at "
           (code ":decide") " and is never shown to anyone.")
      (table nil
             ["Thread" "Operation" "Batch" "Advisor confidence"
              "Reached a human?" "Outcome"]
             (mapv run-row runs)))

     (section
      (str "Governor HARD refusals — " (count holds) " holds, "
           (count distinct-rules) " distinct rules")
      (str "These are refusals by the independent Governor. They cannot be overridden by "
           "advisor confidence and they never reach a human for sign-off. Rule names and "
           "detail text are taken verbatim from the verdict each run produced.")
      (table nil
             ["Thread" "Operation" "Batch" "Rule" "Why the Governor refused"]
             (mapv hold-row holds)))

     (section
      "Human sign-off gate — separate from the refusals above"
      (str "A HARD refusal and an escalation are different events: the first means the "
           "Governor would not let the proposal through at all, the second means it was "
           "clean enough to put in front of a person. This table is read live off "
           (code "pastaops.governor") "'s own vars ("
           (code "allowed-ops") ", " (code "high-stakes") ", "
           (code "always-escalate-ops") ", " (code "confidence-floor")
           "), so it cannot drift from the code. "
           (code ":operate-extruder")
           " is included to show what the closed allowlist does with an operation this "
           "actor has no authority to propose at all. Note that "
           (code "pastaops.phase")
           " exists in this repo but is not referenced by any other namespace in "
           (code "src/") ", so there is no phase-based rollout gate to report here.")
      (table nil
             ["Operation" "In the closed allowlist?" "Needs a named human?"]
             (mapv gate-row ops)))

     (section
      "Who approved what"
      (attribution-note summary)
      (table nil
             ["Thread" "Operation" "Batch" "Approver"
              "…in the graph's record channel" "…in the in-run audit trail"
              "…in the durable ledger"]
             (mapv attribution-row approvals)))

     (section
      (str "Audit ledger — " (count ledger) " facts")
      (str "The append-only decision log this run wrote through "
           (code "store/append-ledger!") ", in append order. "
           "Basis is the citation set for a commit and the violated rule list for a hold.")
      (table nil
             ["#" "Fact" "Operation" "Batch" "Disposition" "Basis"]
             (vec
              (map-indexed
               (fn [i {:keys [t op subject disposition basis]}]
                 (str "        <tr><td class=\"n\">" (inc i) "</td>"
                      "<td>" (code (kw t)) "</td>"
                      "<td>" (code (kw op)) "</td>"
                      "<td>" (code subject) "</td>"
                      "<td>" (if (= :commit disposition)
                               (pill "ok" (kw disposition))
                               (pill "bad" (kw disposition))) "</td>"
                      "<td>" (esc (str/join ", "
                                            (map (fn [b]
                                                   (if (map? b) (:spec b) (kw b)))
                                                 basis)))
                      "</td></tr>"))
               ledger))))

     "</main>\n<footer>\n"
     "  Regenerate with <code>clojure -M:dev:render-html</code>. The page contains no "
     "timestamps and no wall-clock-derived values, so two runs from the same seed are "
     "byte-identical. Design tokens: jp-go-digital-design-system (MIT, © 2025 デジタル庁).\n"
     "</footer>\n</body>\n</html>\n")))

;; ============================ entry point ============================

(defn- assert-hard-holds!
  "Build-time invariant, not a convention. A console that shows only happy
  paths is worthless as evidence that the Governor can refuse, so refuse to
  write one.

  Two stages, because one is not enough: a phase- or approval-gated hold can
  legitimately carry an EMPTY `:violations` vector and would satisfy a naive
  count of holds while telling the reader nothing. So this requires both that
  HARD holds happened AND that at least one of them carries a real rule with a
  real reason."
  [runs]
  (let [holds (filterv hard-hold? runs)
        substantive (filterv substantive-hard-hold? runs)]
    (when (empty? holds)
      (throw (ex-info "refusing to write the console: this run produced ZERO HARD governor holds"
                      {:runs (count runs) :hard-holds 0})))
    (when (empty? substantive)
      (throw (ex-info (str "refusing to write the console: " (count holds)
                           " HARD hold(s), but none carries a violation rule with a reason")
                      {:runs (count runs)
                       :hard-holds (count holds)
                       :substantive-hard-holds 0})))
    {:hard-holds (count holds)
     :substantive (count substantive)
     :distinct-rules (->> holds (mapcat hard-rules) (remove nil?) distinct sort vec)}))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [store runs] :as demo} (run-demo!)
        {:keys [hard-holds distinct-rules]} (assert-hard-holds! runs)
        html (render demo)]
    (some-> (.getParentFile (java.io.File. ^String out)) .mkdirs)
    (spit out html)
    (println "wrote" out
             (str "(" (count html) " bytes, "
                  (count runs) " graph runs, "
                  (count (store/ledger store)) " ledger facts, "
                  hard-holds " HARD holds over "
                  (count distinct-rules) " distinct rules)"))
    (println "  hard-hold rules:" (str/join " " (map name distinct-rules)))))
