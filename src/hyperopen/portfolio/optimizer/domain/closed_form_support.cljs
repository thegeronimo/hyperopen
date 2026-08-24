(ns hyperopen.portfolio.optimizer.domain.closed-form-support
  "Centralized numeric tolerances and post-validation for the closed-form
  optimizer path. Validation re-checks computed weights against the same
  constraints a QP problem would encode plus each objective's own acceptance
  conditions, so an invalid closed-form solution is rejected (and the caller
  falls back) rather than returned. Pure; depends only on domain math."
  (:require [hyperopen.portfolio.optimizer.domain.math :as math]))

(def ^:private finite-number? math/finite-number?)

(def tolerances
  "Numeric tolerances for closed-form eligibility, solving, and validation.

  :constraint-match is ABSOLUTE and deliberately STRICTER than the
  target-selection re-validation tolerance (1.0e-5, absolute). Every constraint
  that target-selection re-checks (net equality, box bounds, gross/turnover L1,
  the target-return floor) is post-validated here against :constraint-match, so
  closed-form's acceptance region is a strict SUBSET of target-selection's for
  ANY constraint magnitude. That guarantees an accepted closed-form candidate
  can never be rejected downstream (which would fail the run with no fallback);
  the worst case is an unnecessary fall back to QP."
  {:constraint-match 1.0e-8
   :denominator 1.0e-10
   :volatility-match 1.0e-6
   :sqrt-clamp 1.0e-9
   :symmetry 1.0e-8})

(defn positive-beyond-epsilon?
  [value]
  (and (finite-number? value)
       (> value (:denominator tolerances))))

(defn- quadratic-scale
  [weights covariance]
  (reduce +
          0
          (map (fn [weight row]
                 (* (js/Math.abs weight)
                    (reduce +
                            0
                            (map (fn [coefficient other-weight]
                                   (js/Math.abs (* coefficient other-weight)))
                                 row
                                 weights))))
               weights
               covariance)))

(defn- within-bounds?
  "Every weight inside its bounds, where a missing bound means unbounded.

  Each bound vector is padded with nils rather than passed through as-is.
  `map` over three collections stops at the shortest, so a bounds vector
  shorter than the weight vector used to truncate the whole check -- including
  the OTHER side's bounds, which might well have covered those positions. A
  short `:lower-bounds` therefore disabled the upper-bound check on the tail,
  and post-validation is the entire safety argument for accepting a
  closed-form portfolio without re-solving it.

  Padding with nil is what makes an absent bound mean unbounded rather than
  violated: `finite-number?` is false for nil, so the position passes."
  [weights lower-bounds upper-bounds]
  (let [tol (:constraint-match tolerances)]
    (every? identity
            (map (fn [weight lower upper]
                   (and (or (not (finite-number? lower))
                            (>= weight (- lower tol)))
                        (or (not (finite-number? upper))
                            (<= weight (+ upper tol)))))
                 weights
                 (concat lower-bounds (repeat nil))
                 (concat upper-bounds (repeat nil))))))

(defn- abs-sum
  [values]
  (reduce + 0 (map js/Math.abs values)))

(defn- gross-floor-value
  [signs weights]
  (reduce + 0 (map * signs weights)))

(defn- l1-violations
  [weights encoded-constraints]
  (let [tol (:constraint-match tolerances)
        gross-max (get-in encoded-constraints [:gross-exposure :max])
        gross-floor (get-in encoded-constraints [:gross-floor :min])
        gross-floor-signs (get-in encoded-constraints [:gross-floor :signs])
        max-turnover (:max-turnover encoded-constraints)
        current-weights (:current-weights encoded-constraints)]
    (cond-> []
      (and (finite-number? gross-max)
           (> (abs-sum weights)
              (+ gross-max tol)))
      (conj {:code :gross-exposure-violated
             :value (abs-sum weights)
             :limit gross-max})

      ;; A closed-form GMV ignores inequalities, so reject any candidate below the
      ;; gross floor and let the QP path (which encodes the floor) take over.
      (and (finite-number? gross-floor)
           (sequential? gross-floor-signs)
           (= (count gross-floor-signs) (count weights))
           (< (gross-floor-value gross-floor-signs weights)
              (- gross-floor tol)))
      (conj {:code :gross-floor-violated
             :value (gross-floor-value gross-floor-signs weights)
             :limit gross-floor})

      (and (finite-number? max-turnover)
           (or (not= (count weights) (count current-weights))
               ;; The QP turnover encoding allows 2*max-turnover of L1 change.
               (> (abs-sum (map - weights current-weights))
                  (+ (* 2 max-turnover) tol))))
      (conj {:code :turnover-violated
             :limit max-turnover}))))

(defn- long-only-violations
  [weights encoded-constraints]
  (when (and (:long-only? encoded-constraints)
             (some (fn [w] (< w (- (:constraint-match tolerances)))) weights))
    [{:code :long-only-violated}]))

(defn- locked-violations
  "A locked weight is a hard equality w_i = locked_i. The unrestricted
  candidate ignores it, so accept only if the candidate already lands on every
  locked value within tolerance; otherwise the candidate is rejected."
  [weights {:keys [instrument-ids locked-weights]}]
  (let [tol (:constraint-match tolerances)
        index-by-id (zipmap instrument-ids (range))]
    (->> (or locked-weights [])
         (keep (fn [{:keys [instrument-id weight]}]
                 (let [idx (get index-by-id instrument-id)
                       actual (when (and (number? idx) (< idx (count weights)))
                                (nth weights idx))]
                   (when-not (and (finite-number? actual)
                                  (finite-number? weight)
                                  (<= (js/Math.abs (- actual weight))
                                      tol))
                     {:code :locked-weights-violated
                      :instrument-id instrument-id
                      :target weight
                      :value actual}))))
         vec)))

(defn- objective-violations
  [{:keys [objective weights]} {:keys [expected-return volatility]}]
  (let [tol (:constraint-match tolerances)]
    (case (:kind objective)
      :target-return
      ;; The floor is a target-selection-rechecked inequality, so use the
      ;; absolute constraint-match tolerance to stay inside its 1e-5 window.
      (let [r-target (:target-return objective)]
        (when (< expected-return
                 (- r-target tol))
          [{:code :target-return-floor-violated
            :value expected-return
            :limit r-target}]))

      :max-sharpe
      (let [risk-free-rate (or (:risk-free-rate objective) 0)
            excess (- expected-return
                      (* risk-free-rate (reduce + 0 weights)))]
        (cond-> []
          (not (positive-beyond-epsilon? volatility))
          (conj {:code :non-positive-volatility
                 :value volatility})

          (not (positive-beyond-epsilon? excess))
          (conj {:code :non-positive-excess-return
                 :value excess})))

      :target-volatility
      (let [sigma (:target-volatility objective)]
        (when (> (js/Math.abs (- volatility sigma))
                 (* (:volatility-match tolerances) (max 1 sigma)))
          [{:code :target-volatility-missed
            :value volatility
            :limit sigma}]))

      nil)))

(defn validate-solution
  "Post-validates closed-form weights against the same constraints a QP problem
  would encode plus the objective's own acceptance conditions. Returns
  {:valid? true :metrics ...} or {:valid? false :violations [...]}."
  [{:keys [weights net-target objective expected-returns covariance encoded-constraints]
    :as solution}]
  (if-not (and (sequential? weights)
               (every? finite-number? weights))
    {:valid? false
     :violations [{:code :non-finite-weights}]}
    (let [expected-return (math/portfolio-return weights expected-returns)
          variance (math/portfolio-variance weights covariance)
          variance-floor (* (:denominator tolerances)
                            (max 1 (quadratic-scale weights covariance)))
          volatility (when (finite-number? variance)
                       (js/Math.sqrt (max 0 variance)))
          metrics {:expected-return expected-return
                   :variance variance
                   :volatility volatility}
          weight-sum (reduce + 0 weights)
          violations
          (vec
           (concat
            (when-not (and (finite-number? expected-return)
                           (finite-number? variance)
                           (finite-number? volatility))
              [{:code :non-finite-metrics}])
            (when (and (finite-number? variance)
                       (< variance (- variance-floor)))
              [{:code :negative-variance
                :value variance}])
            (when (> (js/Math.abs (- weight-sum net-target))
                     (:constraint-match tolerances))
              [{:code :net-target-violated
                :value weight-sum
                :limit net-target}])
            (when-not (within-bounds? weights
                                      (:lower-bounds encoded-constraints)
                                      (:upper-bounds encoded-constraints))
              [{:code :bound-violated}])
            (long-only-violations weights encoded-constraints)
            (locked-violations weights encoded-constraints)
            (l1-violations weights encoded-constraints)
            (when (and (finite-number? expected-return)
                       (finite-number? volatility))
              (objective-violations solution metrics))))]
      (if (seq violations)
        {:valid? false
         :violations violations}
        {:valid? true
         :metrics metrics}))))

(defn rejection-reason
  "Maps the first post-validation violation to a stable rejection-reason
  keyword for diagnostics and fallback signalling."
  [violations]
  (case (:code (first violations))
    :non-finite-weights :non-finite
    :non-finite-metrics :non-finite
    :negative-variance :non-finite
    :net-target-violated :violates-net-target
    :bound-violated :violates-bounds
    :long-only-violated :violates-bounds
    :locked-weights-violated :violates-locked-weights
    :gross-exposure-violated :violates-gross
    :gross-floor-violated :violates-gross-floor
    :turnover-violated :violates-turnover
    :target-return-floor-violated :violates-target-return
    :non-positive-volatility :violates-sharpe-conditions
    :non-positive-excess-return :violates-sharpe-conditions
    :target-volatility-missed :violates-target-volatility
    :invalid-solution))
