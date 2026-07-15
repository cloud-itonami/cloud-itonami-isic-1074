(ns pastaops.operation
  "OperationActor -- the pure-function driver for a single proposal.

  Contracts a proposal through Governor validation, and if passed, yields
  the audit facts (facts committed to the ledger).")

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
