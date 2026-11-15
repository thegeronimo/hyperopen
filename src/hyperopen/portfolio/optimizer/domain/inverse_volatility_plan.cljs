(ns hyperopen.portfolio.optimizer.domain.inverse-volatility-plan
  "Solver plan for the Risk-weighted sizing (:inverse-volatility) objective.

  The user has already fixed every directional decision (assets, sides, gross
  target G); this objective sizes each asset by its own volatility only:
  |w_i| proportional to 1/sigma_i, scaled so the book magnitudes sum to G,
  correlations deliberately ignored. That ideal seed is then made feasible by
  ONE sigma-weighted projection QP over the same convex region Equal Risk
  uses (signed-gross equality, per-asset bounds and locks, turnover L1):

      minimize 0.5 * (w - seed)' * diag(sigma) * (w - seed)

  The sigma weighting is what makes cap handling honest: at the KKT optimum
  every asset NOT pinned to a bound satisfies |w_i| * sigma_i = constant, so
  excess from a capped asset re-equalizes risk-weight parity among the free
  assets (water-filling) instead of shifting weights uniformly. When nothing
  binds, the QP returns the seed itself.

  Feasibility screening reuses domain.equal-risk-presolve verbatim (fixed
  sides, gross sanity, aggregate capacity, covariance validity) with messages
  naming this objective. Gross floor/ceiling rows are deliberately absent
  from the QP: presolve rejects any target outside [floor, ceiling] and the
  signed-gross EQUALITY then pins realized gross exactly, so the rows would
  be redundant. Expected returns are never consumed."
  (:require [hyperopen.portfolio.optimizer.domain.equal-risk-plan :as equal-risk-plan]
            [hyperopen.portfolio.optimizer.domain.equal-risk-presolve
             :as equal-risk-presolve]))

(def objective-label
  "User-facing name threaded into reused presolve violation messages."
  "Risk-weighted sizing")

(defn- sigmas-of
  [covariance n]
  (mapv #(js/Math.sqrt (max 0 (get-in covariance [% %]))) (range n)))

(defn- zero-volatility-violation
  [instrument-ids sigmas]
  (let [zero-vol-ids (vec (keep-indexed (fn [idx sigma]
                                          (when-not (pos? sigma)
                                            (nth instrument-ids idx)))
                                        sigmas))]
    (when (seq zero-vol-ids)
      {:code :inverse-volatility-zero-volatility-asset
       :instrument-ids zero-vol-ids
       :message (str (count zero-vol-ids)
                     (if (= 1 (count zero-vol-ids))
                       " asset has"
                       " assets have")
                     " zero estimated volatility, so 1/σ sizing is undefined. "
                     "Exclude the asset or extend its price history.")})))

(defn- ideal-seed
  "The UNCLIPPED 1/σ sizing: |w_i| = G·(1/σ_i)/Σ(1/σ), signed by book. Caps,
  locks, and turnover are deliberately NOT applied here — the projection QP
  owns feasibility, and the payload compares published weights against this
  ideal to flag the rows a constraint moved (:moved-off-seed?)."
  [books targets sigmas]
  (let [n (count sigmas)
        signs (equal-risk-plan/gross-coefficients books n)
        inverses (mapv #(/ 1 %) sigmas)
        total (reduce + 0 inverses)]
    (mapv (fn [idx]
            (* (nth signs idx)
               (:gross targets)
               (/ (nth inverses idx) total)))
          (range n))))

(defn- projection-quadratic
  "diag(sigma) normalized to mean 1 — the argmin is scale-invariant and the
  normalization keeps the QP well-conditioned for daily-vol magnitudes."
  [sigmas]
  (let [n (count sigmas)
        mean-sigma (/ (reduce + 0 sigmas) (max 1 n))
        scaled (mapv #(/ % mean-sigma) sigmas)]
    (mapv (fn [row]
            (mapv (fn [col] (if (= row col) (nth scaled row) 0)) (range n)))
          (range n))))

(defn- projection-problem
  [encoded-constraints books targets seed sigmas]
  (let [quadratic (projection-quadratic sigmas)
        diagonal (mapv #(nth (nth quadratic %) %) (range (count seed)))]
    (-> (equal-risk-plan/subproblem-template encoded-constraints books targets)
        (assoc :objective-kind :inverse-volatility
               :quadratic quadratic
               :linear (mapv (fn [d s] (- (* d s))) diagonal seed)
               :seed-weights (vec seed)
               :sigmas (vec sigmas)))))

(defn build-plan
  "Solver plan for :inverse-volatility: presolve fixed-side/gross/covariance
  feasibility, screen zero-volatility assets, build the 1/σ seed, and plan
  ONE sigma-weighted projection QP handed to the injected adapter."
  [{:keys [instrument-ids covariance encoded-constraints]}]
  (let [encoded (assoc encoded-constraints :instrument-ids (vec instrument-ids))
        presolve-result (equal-risk-presolve/presolve
                         encoded
                         covariance
                         {:objective-label objective-label})]
    (if (= :infeasible (:status presolve-result))
      {:status :infeasible
       :reason :inverse-volatility-presolve
       :details {:violations (:violations presolve-result)}}
      (let [{:keys [books targets]} presolve-result
            covariance* (:covariance presolve-result)
            sigmas (sigmas-of covariance* (count instrument-ids))]
        (if-let [violation (zero-volatility-violation instrument-ids sigmas)]
          {:status :infeasible
           :reason :inverse-volatility-presolve
           :details {:violations [violation]}}
          (let [seed (ideal-seed books targets sigmas)]
            {:status :ok
             :strategy :inverse-volatility
             :selection-objective {:kind :inverse-volatility}
             :warnings (vec (:warnings presolve-result))
             :problems [(projection-problem encoded books targets seed sigmas)]}))))))
