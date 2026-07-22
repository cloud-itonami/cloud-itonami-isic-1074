(ns pastaops.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a staged production batch
  through a clean auto-commit (:schedule-maintenance), an always-escalate
  batch logging (plant operator approves), an always-escalate food-safety
  concern (plant operator rejects), and a hard-hold (batch never staged),
  then prints the resulting audit ledger. Mirrors `bakeryops.sim`
  (cloud-itonami-isic-1071)."
  (:require [langgraph.graph :as g]
            [pastaops.operation :as operation]
            [pastaops.store :as store]))

(def plant-op {:actor-id "plant-op-01" :role :plant-operator})

(def ^:private clean-batch
  "A batch record clean against every independent Governor check
  (spec-basis, evidence-completeness, drying-temp/time, moisture,
  sanitation, allergen-label). Mirrors
  `test/pastaops/operation_test.cljc`'s `clean-batch` fixture."
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

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "plant-op-01"}}
          {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "plant-op-01"}}
          {:thread-id tid :resume? true}))

(defn demo
  "Run the compiled StateGraph through a commit path, an
  escalate->approve->commit path, an escalate->reject->hold path, and a
  hard-hold path; print each result and the final audit ledger."
  []
  (let [st (store/mem-store)
        _  (store/register-batch! st "batch-001" clean-batch)
        actor (operation/build st)]

    (println "=== Macaroni/Noodle/Couscous Plant-Operations Coordinator Demo ===")

    (println "\n== schedule-maintenance batch-001 (governor-clean, low-stakes -> commit) ==")
    (println (exec-op actor "t1"
                      {:op :schedule-maintenance :subject "batch-001"
                       :equipment "extruder-2" :note "quarterly deep-clean"}
                      plant-op))

    (println "\n== log-production-batch batch-001 (ALWAYS escalates -- plant operator approves) ==")
    (let [r (exec-op actor "t2"
                     {:op :log-production-batch :subject "batch-001"
                      :jurisdiction :jp/prefectural}
                     plant-op)]
      (println r)
      (println "-- plant operator approves --")
      (println (approve! actor "t2")))

    (println "\n== flag-food-safety-concern batch-001 (ALWAYS escalates -- plant operator rejects) ==")
    (let [r (exec-op actor "t3"
                     {:op :flag-food-safety-concern :subject "batch-001"
                      :jurisdiction :jp/prefectural
                      :concern "possible wheat/egg allergen cross-contact, line 2"}
                     plant-op)]
      (println r)
      (println "-- plant operator rejects (insufficient evidence to confirm/dismiss) --")
      (println (reject! actor "t3")))

    (println "\n== log-production-batch batch-999 (never staged -> HARD hold, no interrupt) ==")
    (println (exec-op actor "t4"
                      {:op :log-production-batch :subject "batch-999"
                       :jurisdiction :jp/prefectural}
                      plant-op))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger st)] (println f))

    {:ledger (store/ledger st)}))

(defn -main
  "clojure -M:run entrypoint."
  [& _args]
  (demo))

(comment
  ;; In a real REPL:
  (demo))
