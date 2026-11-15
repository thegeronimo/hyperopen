(ns hyperopen.portfolio.optimizer.application.engine.inverse-volatility-payload
  "The :inverse-volatility result-payload section: per-asset sizing rows that
  let the results card PROVE the objective's promise (|weight| * sigma equal
  across every asset the projection left free), plus the ideal pre-projection
  seed and the realized deviation from parity.

  A row is :moved-off-seed? when the projection pinned it to one of its
  encoded bounds AND its weight materially differs from the ideal seed — the
  honest 'a cap/lock/side limit moved this row' flag. A FREE row whose |w|·σ
  still deviates from the free-set parity is :turnover-limited? — only the
  turnover L1 budget can displace an unpinned row, since at the KKT optimum
  the sigma-weighted projection otherwise re-equalizes free rows exactly.

  Alongside the sizing section, solved runs also publish the objective-
  agnostic :risk-contributions / :current-risk-contributions /
  :risk-structure analytics (engine.risk-analytics-payload — the same pure
  weights+covariance sections Equal Risk emits). The contribution summary's
  targets are the uniform 1/n vector only because the summary math requires
  one; this objective does not target contributions, so the section carries
  :quality :diagnostic instead of a convergence quality. When the analytics
  inputs are unaligned or degenerate the diagnostics are simply omitted — the
  sizing section is the objective's primary proof and never depends on them."
  (:require [hyperopen.portfolio.optimizer.application.engine.risk-analytics-payload
             :as risk-analytics]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.domain.equal-risk :as equal-risk]))

(def ^:private finite-number? coercion/finite-number?)

(def ^:private bound-tolerance
  (:bound-feasibility equal-risk/tolerances))

(defn- at-bound?
  [weight lower upper]
  (or (and (finite-number? lower)
           (<= (js/Math.abs (- weight lower)) bound-tolerance))
      (and (finite-number? upper)
           (<= (js/Math.abs (- weight upper)) bound-tolerance))))

(defn- sizing-rows
  [{:keys [instrument-ids target-weights seed-weights sigmas
           lower-bounds upper-bounds]}]
  (mapv (fn [idx]
          (let [instrument-id (nth instrument-ids idx)
                weight (or (nth target-weights idx nil) 0)
                sigma (nth sigmas idx 0)
                seed (or (nth seed-weights idx nil) 0)
                pinned? (at-bound? weight
                                   (nth lower-bounds idx nil)
                                   (nth upper-bounds idx nil))]
            {:instrument-id instrument-id
             :weight weight
             :sigma sigma
             :risk-weight (* (js/Math.abs weight) sigma)
             :at-bound? pinned?
             :moved-off-seed? (boolean
                               (and pinned?
                                    (> (js/Math.abs (- weight seed))
                                       bound-tolerance)))}))
        (range (count instrument-ids))))

(defn- free-risk-weights
  [rows]
  (into []
        (comp (remove :moved-off-seed?)
              (map :risk-weight)
              (filter finite-number?))
        rows))

(defn- ideal-risk-weight
  "Mean |w|*sigma of the rows the projection left free — the parity value the
  sigma-weighted projection equalizes them to; nil when nothing is free."
  [rows]
  (let [free (free-risk-weights rows)]
    (when (seq free)
      (/ (reduce + 0 free) (count free)))))

(def ^:private turnover-limited-relative-tolerance
  "Relative |w|*sigma deviation from the free-set parity above which a FREE
  row is flagged :turnover-limited? — well past numerical solver noise."
  0.01)

(defn- annotate-turnover-limited
  "Flag free rows the turnover budget displaced: not pinned at a bound, yet
  more than 1% (relative) off the free-set parity — only the turnover L1 row
  can hold an unpinned row away from the KKT equalization. Drops the
  transient :at-bound? marker."
  [rows ideal]
  (mapv (fn [{:keys [at-bound? risk-weight] :as row}]
          (-> row
              (assoc :turnover-limited?
                     (boolean
                      (and (not at-bound?)
                           (finite-number? risk-weight)
                           (finite-number? ideal)
                           (pos? ideal)
                           (> (/ (js/Math.abs (- risk-weight ideal)) ideal)
                              turnover-limited-relative-tolerance))))
              (dissoc :at-bound?)))
        rows))

(defn- max-sizing-deviation
  "Largest relative spread of |w|*sigma among the rows the projection left
  free — 0 for a clean run; capped rows are excluded because their deviation
  is the point of the :moved-off-seed? flag, not a solve-quality signal."
  [rows]
  (let [free (free-risk-weights rows)]
    (if (< (count free) 2)
      0
      (let [high (reduce max free)
            low (reduce min free)]
        (if (pos? high)
          (/ (- high low) high)
          0)))))

(defn- analytics-sections
  "The shared diagnostic analytics over the published weights, with the
  uniform-target contribution summary stamped :quality :diagnostic. Empty map
  when the inputs cannot be summarized (see namespace docstring)."
  [{:keys [risk-result instrument-ids target-weights current-weights]}]
  (let [n (count instrument-ids)
        analytics (risk-analytics/analytics-sections
                   {:instrument-ids instrument-ids
                    :covariance (:covariance risk-result)
                    :target-weights target-weights
                    :current-weights current-weights
                    :targets (vec (repeat n (/ 1 n)))})]
    (if (:error analytics)
      {}
      (update analytics :risk-contributions assoc :quality :diagnostic))))

(defn inverse-volatility-sections
  "Builds {:inverse-volatility {...}} plus the diagnostic analytics sections
  from the FINAL published weights and the planned problem's
  seed/bounds/sigmas; nil when the plan carries no problem."
  [{:keys [solver-plan instrument-ids target-weights] :as inputs}]
  (when-let [problem (get-in solver-plan [:problems 0])]
    (let [rows (sizing-rows {:instrument-ids instrument-ids
                             :target-weights target-weights
                             :seed-weights (:seed-weights problem)
                             :sigmas (:sigmas problem)
                             :lower-bounds (:lower-bounds problem)
                             :upper-bounds (:upper-bounds problem)})
          ideal (ideal-risk-weight rows)]
      (merge
       {:inverse-volatility
        {:sizing-rows (annotate-turnover-limited rows ideal)
         :seed-weights (vec (:seed-weights problem))
         :ideal-risk-weight ideal
         :max-sizing-deviation (max-sizing-deviation rows)}}
       (analytics-sections inputs)))))
