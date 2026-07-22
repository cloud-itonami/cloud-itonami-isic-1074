(ns pastaops.operation
  "OperationActor for the macaroni/noodle/couscous plant-operations
  coordinator.

  `run-operation` is this actor's original pure-function driver for a
  single already-formed proposal: it contracts the proposal through
  Governor validation and yields the audit facts. It stays exactly as
  it was -- callers/tests that already depend on this thin, synchronous
  shape keep working unchanged.

  `build` is the missing plumbing: a REAL langgraph-clj StateGraph
  (`langgraph.graph/state-graph` + `compile-graph`) that seals the
  PastaOpsAdvisor into a single node (`:advise`), ALWAYS routes its
  proposal through the independent Pasta Governor (`:govern`) before
  anything commits, and gives `:log-production-batch` /
  `:coordinate-shipment` (real actuation events) and
  `:flag-food-safety-concern` (food-safety, never auto-resolved) a real
  human-in-the-loop approval gate (`:request-approval`,
  `interrupt-before` + checkpoint-based resume) instead of just
  returning `:ok? false` and stopping. Mirrors `bakeryops.operation`
  (cloud-itonami-isic-1071) node/edge structure exactly, wired to this
  repo's own advisor/governor/store.

  PRIOR BUGS (fixed here, same compounding class as sibling actors):
    1. `deps.edn` declared `io.github.kotoba-lang/langgraph` only under
       an unused `:dev`-only `:override-deps` entry, with an empty base
       `:deps` map -- `:override-deps` has nothing to override when the
       library was never in the graph, so `langgraph.graph` was never
       actually resolvable on the real build/run/test path. Fixed by
       moving the dependency into the base `:deps` map.
    2. `pastaops.advisor` was a docstring-only namespace: no protocol,
       no implementation, never `require`d here. There was no `:advise`
       node in any real flow. Fixed: the Advisor is now a real protocol
       (`pastaops.advisor/Advisor`), has a real mock implementation, and
       is genuinely a node inside the compiled graph below.
    3. `pastaops.store/append-fact` (the ledger-append function) existed
       but was called ONLY from `test/pastaops/store_test.cljc` test
       setup -- never from any real commit/hold path, since there was no
       real commit/hold path (`run-operation` above never appends
       anything to a store; it just returns facts to the caller). Fixed:
       `pastaops.store/append-ledger!` (the `Store`-protocol sibling of
       `append-fact`) is now genuinely called from the compiled graph's
       `:commit` and `:hold` node handlers below.

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store    (`pastaops.store/MemStore`, or any `Store` impl)
    - the Advisor  (mock today; real LLM is the next seam --
                     `pastaops.advisor/Advisor` is already the
                     injection point, see its docstring)

  One graph run = one plant-operations coordination request. No
  unbounded inner loop -- each run is auditable and checkpointed. A
  batch's operating history is advanced by MANY runs
  (log-production-batch / schedule-maintenance /
  flag-food-safety-concern / coordinate-shipment), each its own
  independent graph run, and every commit/hold/approval-rejected
  decision fact lands in `pastaops.store`'s append-only ledger
  (`store/append-ledger!`)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [pastaops.advisor :as advisor]
            [pastaops.governor :as governor]
            [pastaops.store :as store]))

(defn run-operation
  "Drive a single proposal through Governor validation.
  Returns {:ok? bool :facts [..] :verdict ..}."
  [request context proposal store governor-fn]
  (let [verdict (governor-fn request context proposal store)]
    (if (:ok? verdict)
      {:ok? true
       :facts []}
      {:ok? false
       :facts [((:hold-fact-fn context) request context verdict)]
       :verdict verdict})))

;; ----------------------------- StateGraph -----------------------------

(defn- commit-fact
  "The audit fact written when a proposal commits. `:record` carries the
  operational payload the advisor proposed -- pastaops has no separate
  stateful commit-record! entity beyond batch staging, so the ledger
  fact itself is the durable record of what happened."
  [request context proposal]
  {:t          :committed
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :commit
   :basis      (:cites proposal)
   :summary    (:summary proposal)
   :record     (:value proposal)})

(defn- commit-record [request _context proposal]
  {:effect  (:effect proposal)
   :path    [(:subject request)]
   :value   (or (:value proposal) {})
   :payload (:value proposal)})

(defn- apply-commit-mutation!
  "The store-level side effect a commit represents, if any:
  `:log-production-batch` flips the already-staged batch's
  `:processed?` flag; `:coordinate-shipment` flips
  `:shipment-finalized?`. `:schedule-maintenance` and
  `:flag-food-safety-concern` have no batch-record mutation of their
  own -- the ledger fact IS the durable record."
  [store request]
  (case (:op request)
    :log-production-batch (store/mark-processed! store (:subject request))
    :coordinate-shipment  (store/mark-shipment-finalized! store (:subject request))
    nil))

(defn build
  "Compiles an OperationActor graph bound to `store`. opts:
    :advisor      -- a `pastaops.advisor/Advisor` (default: mock-advisor)
    :checkpointer -- a `langgraph.checkpoint/Checkpointer`
                     (default: in-memory `cp/mem-checkpointer`)"
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal (store/snapshot store))}))

      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [disposition (cond
                               (:hard? verdict)     :hold
                               (:escalate? verdict)  :escalate
                               :else                 :commit)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(governor/hold-fact request context verdict)]}

              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested
                        :op (:op request) :subject (:subject request)
                        :reason (cond
                                  (contains? governor/high-stakes (:op request))
                                  :high-stakes-actuation

                                  (contains? governor/always-escalate-ops (:op request))
                                  :always-escalate

                                  :else :low-confidence)
                        :confidence (:confidence verdict)}]}

              :commit
              {:disposition :commit
               :record (commit-record request context proposal)}))))

      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc (commit-record request context proposal)
                            :payload (assoc (:value proposal)
                                            :approved-by (:by approval)))
             :audit [{:t :approval-granted :op (:op request)
                      :subject (:subject request) :by (:by approval)}]}
            {:disposition :hold
             :audit [(merge (governor/hold-fact request context
                                                (assoc verdict :violations
                                                       [{:rule :approver-rejected}]))
                            {:t :approval-rejected})]})))

      (g/add-node :commit
        (fn [{:keys [request context proposal]}]
          (let [f (commit-fact request context proposal)]
            (apply-commit-mutation! store request)
            (store/append-ledger! store f)
            {:audit [f]})))

      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
