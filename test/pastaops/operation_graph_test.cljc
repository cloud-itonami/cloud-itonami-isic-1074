(ns pastaops.operation-graph-test
  "Integration tests for `pastaops.operation/build` -- builds the REAL
  compiled `langgraph.graph` StateGraph and runs it end-to-end via
  `langgraph.graph/run*` through commit / hard-hold / escalate-approve /
  escalate-reject routes. These did not exist before: there was no
  `build` at all, no Advisor node, and `store/append-ledger!` had never
  been called from any real commit/hold path (only from
  `test/pastaops/store_test.cljc` setup code).

  Falsifiable claims each test proves, not just asserts:
    1. the ledger is verified EMPTY before the run (never pre-populated
       by test fixtures), so a post-run non-empty ledger is genuinely
       caused by this run's own `:commit`/`:hold` node, not residue;
    2. a HARD governor violation blocks the graph from EVER reaching
       `:commit` -- proven for an op that would otherwise always
       escalate to human approval, showing the hard-hold check runs
       and wins BEFORE the escalate branch, not merely that no ledger
       fact happens to appear;
    3. the Advisor's proposal is genuinely threaded through
       `:advise -> :govern -> :decide -> :commit` -- proven by injecting
       a custom `Advisor` (via `build`'s `:advisor` opt) whose proposal
       carries a random, single-use `:summary` string generated at test
       run time (impossible to have been hardcoded anywhere in
       `pastaops.operation`) and asserting the committed ledger fact
       carries that EXACT string."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [pastaops.advisor :as advisor]
            [pastaops.operation :as operation]
            [pastaops.store :as store]))

(def ^:private plant-op {:actor-id "plant-op-01" :role :plant-operator})

(def ^:private clean-batch
  "Governor-clean against every independent hard check (spec-basis,
  evidence-completeness, drying-temp/time, moisture, sanitation,
  allergen-label). Mirrors `test/pastaops/operation_test.cljc`'s fixture
  of the same name."
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

(defn- exec
  ([actor tid request] (exec actor tid request plant-op))
  ([actor tid request context]
   (g/run* actor {:request request :context context} {:thread-id tid})))

(deftest commit-path-clean-low-stakes-proposal
  (testing "a clean, low-stakes (:schedule-maintenance) proposal commits
            through the REAL compiled graph and appends exactly one fact
            to the audit ledger -- the ledger is verified EMPTY
            beforehand, proving the write is a genuine effect of THIS
            run, not test-setup residue"
    (let [s (store/mem-store)
          _ (store/register-batch! s "batch-001" clean-batch)
          actor (operation/build s)]
      (is (empty? (store/ledger s)) "ledger is empty before any run")
      (let [result (exec actor "t-commit" {:op :schedule-maintenance :subject "batch-001"})
            state (:state result)]
        (is (= :done (:status result)))
        (is (= :commit (:disposition state)))
        (let [ledger (store/ledger s)]
          (is (= 1 (count ledger)))
          (is (= :committed (:t (first ledger))))
          (is (= :schedule-maintenance (:op (first ledger))))
          (is (= "batch-001" (:subject (first ledger)))))))))

(deftest hard-hold-path-batch-never-staged
  (testing "log-production-batch against a batch that was NEVER staged
            (`:evidence-incomplete`) is a HARD governor violation -- the
            real graph routes straight to :hold (no interrupt, no
            human-approval detour) and durably records the hold fact;
            the underlying batch record stays nil (`mark-processed!`
            never fired), proving the write path is genuinely gated"
    (let [s (store/mem-store)
          actor (operation/build s)]
      (is (empty? (store/ledger s)))
      (let [result (exec actor "t-hold" {:op :log-production-batch :subject "batch-999"
                                          :jurisdiction :jp/prefectural})
            state (:state result)]
        (is (= :done (:status result)))
        (is (= :hold (:disposition state)))
        (let [ledger (store/ledger s)]
          (is (= 1 (count ledger)))
          (is (= :governor-hold (:t (first ledger))))
          (is (seq (:violations (first ledger))))))
      (is (nil? (store/production-batch (store/snapshot s) "batch-999"))
          "a held proposal never registers/mutates the batch record"))))

(deftest governor-hard-hold-blocks-ledger-write-before-commit
  (testing "a HARD governor violation (drying-temp-out-of-range) is
            caught BEFORE the graph would otherwise have escalated for
            human approval -- :log-production-batch is normally
            ALWAYS-escalate (high-stakes), so a plain '(:hold
            disposition)' assertion alone wouldn't distinguish
            hard-block from an unresolved escalation. This test proves
            the ledger contains ONLY a :governor-hold fact citing the
            actual violated rule -- never a :committed fact, never an
            :approval-requested-only outcome -- for a request whose op
            WOULD have interrupted for approval had it been governor-clean"
    (let [s (store/mem-store)
          _ (store/register-batch! s "batch-bad"
                                   (assoc clean-batch :drying-temp-c 40)) ;; macaroni/elbow safe window is [82,88]
          actor (operation/build s)
          result (exec actor "t-govhold" {:op :log-production-batch :subject "batch-bad"
                                           :jurisdiction :jp/prefectural})]
      (is (= :done (:status result)) "no interrupt -- HARD holds never pause for approval")
      (is (= :hold (:disposition (:state result))))
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (every? #(= :governor-hold (:t %)) ledger)
            "no :committed fact was ever written -- the governor hold
            blocked the ledger write before :commit could run")
        (is (some #{:drying-temp-out-of-range}
                  (map :rule (:violations (first ledger))))))
      (is (false? (store/batch-already-processed? (store/snapshot s) "batch-bad"))
          "store/mark-processed! never fired for a hard-held proposal"))))

(deftest escalate-then-approve-commits-and-genuinely-consults-advisor
  (testing ":log-production-batch ALWAYS escalates (high-stakes) -- the
            real graph GENUINELY interrupts (checkpointed) at
            :request-approval, and the ledger stays EMPTY until a human
            plant operator resumes it. A custom, non-default Advisor
            (injected at test time, NOT a call-site literal in
            `pastaops.operation`) proposes with a randomly generated,
            single-use `:summary` string. Only if the graph truly
            threads the Advisor's own proposal through
            :advise -> :govern -> :decide -> :commit (rather than
            re-deriving/hardcoding a proposal internally) can that exact
            string reach the ledger's committed fact."
    (let [distinctive-summary (str "TEST-ADVISOR-" (rand-int 1000000000))
          test-advisor (reify advisor/Advisor
                         (-advise [_ _store request]
                           {:op (:op request)
                            :effect :propose
                            :value {:jurisdiction :eu/efsa}
                            :cites [{:spec "batch-batch-001-formulation-record"}]
                            :summary distinctive-summary
                            :confidence 0.9}))
          s (store/mem-store)
          _ (store/register-batch! s "batch-001" (assoc clean-batch :jurisdiction :eu/efsa))
          actor (operation/build s {:advisor test-advisor})]
      (is (empty? (store/ledger s)))
      (let [held (exec actor "t-escalate" {:op :log-production-batch :subject "batch-001"
                                            :jurisdiction :eu/efsa})]
        (is (= :interrupted (:status held)))
        (is (= [:request-approval] (:frontier held)))
        (is (empty? (store/ledger s)) "not yet committed -- awaiting human sign-off")
        (let [approved (g/run* actor {:approval {:status :approved :by "plant-op-01"}}
                               {:thread-id "t-escalate" :resume? true})
              approved-state (:state approved)]
          (is (= :done (:status approved)))
          (is (= :commit (:disposition approved-state)))
          (let [ledger (store/ledger s)]
            (is (= 1 (count ledger)))
            (is (= :committed (:t (first ledger))))
            (is (= distinctive-summary (:summary (first ledger)))
                "the ledger's committed fact carries the INJECTED test
                Advisor's own distinctive summary -- proof the graph
                genuinely threads the Advisor's real proposal through
                :govern -> :decide -> :commit rather than hardcoding a
                pass-string or ignoring the Advisor node's output")
            (is (true? (store/batch-already-processed? (store/snapshot s) "batch-001")))))))))

(deftest escalate-then-reject-holds
  (testing "a human plant operator rejecting an escalated
            flag-food-safety-concern routes to :hold via the
            :request-approval node's own decision, and durably records
            the rejection -- not a hand-rolled parallel path"
    (let [s (store/mem-store)
          _ (store/register-batch! s "batch-001" clean-batch)
          actor (operation/build s)
          _held (exec actor "t-reject" {:op :flag-food-safety-concern :subject "batch-001"
                                         :jurisdiction :jp/prefectural
                                         :concern "possible allergen cross-contact"})
          rejected (g/run* actor {:approval {:status :rejected :by "plant-op-01"}}
                           {:thread-id "t-reject" :resume? true})
          rejected-state (:state rejected)]
      (is (= :done (:status rejected)))
      (is (= :hold (:disposition rejected-state)))
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (= :approval-rejected (:t (first ledger))))))))

(deftest coordinate-shipment-hard-hold-batch-not-registered
  (testing "`:coordinate-shipment` against a batch that was never
            registered is a HARD, permanent block
            (`:batch-not-registered`) distinct from
            `:evidence-incomplete` -- proven end-to-end through the
            compiled graph"
    (let [s (store/mem-store)
          actor (operation/build s)
          result (exec actor "t-ship-unreg" {:op :coordinate-shipment :subject "ghost-batch"
                                              :jurisdiction :jp/prefectural})]
      (is (= :hold (:disposition (:state result))))
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (some #{:batch-not-registered} (map :rule (:violations (first ledger))))))
      (is (false? (store/batch-shipment-finalized? (store/snapshot s) "ghost-batch"))))))
