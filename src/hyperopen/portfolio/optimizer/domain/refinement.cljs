(ns hyperopen.portfolio.optimizer.domain.refinement
  "Pure refinement policy: how a user-requested refinement depth maps to a frontier
  point budget, how a solved result is graded into a quality/stability tier, and how a
  refined result compares against the draft it replaced.

  Refinement is a re-run of the existing solver at a higher frontier density (the engine
  maps over a pre-built problem vector; it is not a resumable/adaptive runner). Selection
  for minimum-variance / target-return (and any closed-form-eligible objective) is solved
  exactly and is independent of frontier density, so refinement only sharpens the chart in
  those cases; only a constrained max-sharpe / target-volatility selection is picked from
  the grid and can move when refined."
  (:require [hyperopen.portfolio.optimizer.coercion :as coercion]))

(def depth->points
  {:quick 56
   :thorough 72
   :maximum 80})

(def depth-order
  [:quick :thorough :maximum])

(def default-depth :thorough)

(def maximum-points 80)

(def ^:private finite-number? coercion/finite-number?)

(defn normalize-depth
  [depth]
  (let [depth* (cond
                 (keyword? depth) depth
                 (string? depth) (keyword depth)
                 :else nil)]
    (when (contains? depth->points depth*)
      depth*)))

(defn depth-points
  "Frontier point budget for a refinement depth, or nil for an unknown depth."
  [depth]
  (get depth->points (normalize-depth depth)))

(def ^:private quality-high-points 50)
(def ^:private quality-medium-points 12)

(defn- display-point-count
  [result]
  (let [n (get-in result [:frontier-summary :point-count])]
    (when (finite-number? n)
      n)))

(defn- frontier-truncated?
  [result]
  (boolean (some #(= :display-frontier-unavailable (:code %))
                 (:warnings result))))

(defn frontier-quality
  [result]
  (let [n (display-point-count result)]
    (cond
      (frontier-truncated? result) :low
      (nil? n) :low
      (>= n quality-high-points) :high
      (>= n quality-medium-points) :medium
      :else :low)))

(defn result-tier
  [result]
  (let [n (display-point-count result)]
    (cond
      (frontier-truncated? result) :partial
      (nil? n) :draft
      (>= n maximum-points) :maximum
      (>= n quality-high-points) :refined
      :else :draft)))

(defn selection-source
  "How the selected portfolio was determined: :closed-form / :single-qp are exact and
  density-independent; :frontier-sweep is picked from the grid and can move when refined."
  [result]
  (case (get-in result [:solver :strategy])
    :frontier-sweep :frontier-sweep
    :closed-form :closed-form
    :single-qp :single-qp
    :single-qp))

(defn exact-selection?
  [result]
  (not= :frontier-sweep (selection-source result)))

(defn- weight-sensitive?
  [result]
  (boolean (seq (get-in result [:diagnostics :weight-sensitivity-by-instrument]))))

(defn selection-stability
  [result]
  (let [tier (result-tier result)]
    (cond
      (and (not (exact-selection? result))
           (not= :maximum tier))
      :provisional

      (weight-sensitive? result) :moderate
      :else :stable)))

(defn stop-reason
  [result]
  (case (result-tier result)
    :partial :frontier-sweep-incomplete
    :maximum :maximum-density-reached
    :refined :refined-density-reached
    :draft-budget-reached))

(defn next-step
  [result]
  (case (result-tier result)
    :maximum :none
    :refined :refine-further
    :refine-optimization))

(defn assess-result
  "Grade a solved result into refinement labels; nil for a non-solved/absent result."
  [result]
  (when (and (map? result)
             (= :solved (:status result)))
    {:tier (result-tier result)
     :frontier-quality (frontier-quality result)
     :selection-source (selection-source result)
     :selection-stability (selection-stability result)
     :exact-selection? (exact-selection? result)
     :stop-reason (stop-reason result)
     :next-step (next-step result)
     :point-count (display-point-count result)}))

(def material-weight-l1-threshold 0.02)
(def material-sharpe-threshold 0.02)

(defn- sharpe
  [result]
  (let [s (get-in result [:performance :in-sample-sharpe])]
    (when (finite-number? s)
      s)))

(defn- finite-or-nil
  [x]
  (when (finite-number? x)
    x))

(defn- delta
  [before after]
  (when (and (number? before)
             (number? after))
    (- after before)))

(defn- weight-l1
  [baseline-result result]
  (let [wa (or (:target-weights-by-instrument baseline-result) {})
        wb (or (:target-weights-by-instrument result) {})
        ids (into #{} (concat (keys wa) (keys wb)))]
    (reduce (fn [acc id]
              (+ acc (js/Math.abs (- (or (finite-or-nil (get wb id)) 0)
                                     (or (finite-or-nil (get wa id)) 0)))))
            0
            ids)))

(defn selection-change
  "Compare a refined result against the draft baseline it replaced. nil unless both are
  solved. weight-l1-delta is the L1 allocation change; material? trips on a meaningful
  allocation or Sharpe move."
  [baseline-result result]
  (when (and (map? baseline-result)
             (map? result)
             (= :solved (:status baseline-result))
             (= :solved (:status result)))
    (let [sharpe-delta (delta (sharpe baseline-result) (sharpe result))
          l1 (weight-l1 baseline-result result)
          material? (boolean (or (> l1 material-weight-l1-threshold)
                                 (and sharpe-delta
                                      (> (js/Math.abs sharpe-delta)
                                         material-sharpe-threshold))))]
      {:sharpe-delta sharpe-delta
       :return-delta (delta (finite-or-nil (:expected-return baseline-result))
                            (finite-or-nil (:expected-return result)))
       :vol-delta (delta (finite-or-nil (:volatility baseline-result))
                         (finite-or-nil (:volatility result)))
       :weight-l1-delta l1
       :material? material?})))
