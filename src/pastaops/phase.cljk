(ns pastaops.phase
  "Phase machine: the states a macaroni/noodle/couscous batch transits
  through.

  State machine:
    :intake -> :design -> :produce -> :inspect -> :package -> :audit -> :archived

  Each transition can accept a proposal and yield an audit fact.")

(def all-phases
  "All valid phases in the extrusion/drying production workflow."
  [:intake :design :produce :inspect :package :audit :archived])

(def phase-sequence
  "Ordered phases representing normal batch progression."
  [:intake :design :produce :inspect :package :audit :archived])

(defn valid-phase?
  "Check if a phase is valid."
  [phase]
  (contains? (set all-phases) phase))

(defn- index-of
  "Portable index-of a value in a vector -- returns the first matching
  index, or -1 if absent. `.indexOf` is JVM-only (ClojureScript's
  PersistentVector does not implement it), so this uses a portable
  `keep-indexed`-based scan instead (matches the fix applied to the
  mirrored cloud-itonami-isic-1071 `bakeryops.phase` reference's latent
  JVM-only `.indexOf` interop bug)."
  [coll x]
  (or (first (keep-indexed (fn [i v] (when (= v x) i)) coll))
      -1))

(defn can-transition?
  "Check if a transition from one phase to another is valid
  (must be forward-only in the sequence, no backtracking). Always returns a
  boolean (never nil), including when either phase is invalid."
  [from-phase to-phase]
  (boolean
   (and (valid-phase? from-phase) (valid-phase? to-phase)
        (let [from-idx (index-of phase-sequence from-phase)
              to-idx (index-of phase-sequence to-phase)]
          (and (>= from-idx 0) (>= to-idx 0) (< from-idx to-idx))))))
