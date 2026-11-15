(ns hyperopen.portfolio.optimizer.domain.equal-risk-plan
  "Solver-plan and QP-subproblem shapes for the Equal Risk (:equal-risk)
  objective. `build-plan` presolves the request (domain.equal-risk-presolve)
  and, when feasible, plans ONE :sequential-equal-risk problem carrying the
  FULL constraint encoding for final-result validation; the per-iteration
  subproblems the sequential solver hands the injected QP adapter carry the
  minimal independent rows (one signed-gross equality + bounds + the L1
  turnover channel). Tolerances, books, and seeds live in domain.equal-risk."
  (:require [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.domain.equal-risk :as equal-risk]
            [hyperopen.portfolio.optimizer.domain.equal-risk-presolve :as equal-risk-presolve]))

(def ^:private finite-number? coercion/finite-number?)

(defn gross-coefficients
  "Signed unit coefficients per index (+1 long, -1 short, 0 unselected) from
  the presolved books. Two consumers: the gross-equality row here and the
  inverse-volatility plan's ideal-seed signs."
  [books n]
  (reduce (fn [coefficients [idx sign]]
            (assoc coefficients idx sign))
          (vec (repeat n 0))
          (concat (map #(vector % 1) (:long books))
                  (map #(vector % -1) (:short books)))))

(defn- gross-equality
  [books targets n]
  [{:code :gross-target
    :coefficients (gross-coefficients books n)
    :target (:gross targets)}])

(defn- turnover-l1-constraints
  "The existing solver turnover convention: user-facing :max-turnover is
  one-sided (0.5 * sum|delta|), the L1 row carries the two-sided budget."
  [{:keys [max-turnover current-weights]}]
  (if (finite-number? max-turnover)
    [{:code :turnover
      :max (* 2 max-turnover)
      :current-weights (vec current-weights)
      :requires-split-variables? true}]
    []))

(defn subproblem-template
  "The convex feasible region every sequential subproblem shares: one
  signed-gross equality, the per-asset bounds (locks are already tight bound
  pairs), and the turnover L1 budget when active. Stored net rows are
  deliberately omitted for Equal Risk and are not reintroduced for final
  validation."
  [{:keys [instrument-ids lower-bounds upper-bounds locked-weights] :as encoded-constraints}
   books
   targets]
  (let [n (count instrument-ids)]
    {:kind :quadratic-program
     :objective-kind :equal-risk
     :instrument-ids (vec instrument-ids)
     :equalities (gross-equality books targets n)
     :inequalities []
     :l1-constraints (turnover-l1-constraints encoded-constraints)
     :lower-bounds (vec lower-bounds)
     :upper-bounds (vec upper-bounds)
     :locked-weights (vec (or locked-weights []))}))

(defn projection-problem
  "Feasible projection of a raw seed: minimize 0.5*||y - seed||^2 over the
  template region (QP with identity quadratic and linear = -seed)."
  [template seed]
  (let [n (count seed)]
    (assoc template
           :quadratic (mapv (fn [row]
                              (mapv (fn [col] (if (= row col) 1 0)) (range n)))
                            (range n))
           :linear (mapv - seed))))

(defn iteration-problem
  "SQP subproblem at iterate w with model Hessian B and gradient g:
  minimize 0.5*y'By + (g - Bw)'y over the template region, which equals the
  damped-Newton model 0.5*(y-w)'B(y-w) + g'(y-w) up to a constant."
  [template b g w]
  (assoc template
         :quadratic b
         :linear (mapv - g (map (fn [row] (reduce + 0 (map * row w))) b))))

(defn weight-violations
  "Violations of `weights` against the template region, using the documented
  feasibility tolerances (QP solvers legitimately land ~1e-9 outside)."
  [template weights]
  (let [bound-tolerance (:bound-feasibility equal-risk/tolerances)
        exposure-tolerance (:exposure-feasibility equal-risk/tolerances)]
    (vec
     (concat
      (keep-indexed (fn [idx weight]
                      (let [lower (nth (:lower-bounds template) idx)
                            upper (nth (:upper-bounds template) idx)]
                        (when (or (and (number? lower)
                                       (< weight (- lower bound-tolerance)))
                                  (and (number? upper)
                                       (> weight (+ upper bound-tolerance))))
                          {:code :bound :index idx :weight weight
                           :lower lower :upper upper})))
                    weights)
      (keep (fn [{:keys [code coefficients target]}]
              (let [value (reduce + 0 (map * coefficients weights))]
                (when (> (js/Math.abs (- value target)) exposure-tolerance)
                  {:code code :value value :target target})))
            (:equalities template))
      (keep (fn [{:keys [code max current-weights]}]
              (when (= :turnover code)
                (let [value (reduce + 0 (map (fn [w c] (js/Math.abs (- w c)))
                                             weights current-weights))]
                  (when (> value (+ max exposure-tolerance))
                    {:code :turnover :value value :max max}))))
            (:l1-constraints template))))))

(defn- plan-problem
  "The plan-level problem handed to the solve dispatcher and, afterwards, to
  target-selection's solver-result validation. It carries the FULL constraint
  encoding (books, signed gross, gross floor/cap, turnover, bounds, locks) so
  the final published point is independently re-checked;
  the per-iteration subproblems use only the minimal independent rows."
  [{:keys [instrument-ids] :as encoded-constraints} covariance books targets template]
  (let [n (count instrument-ids)
        signs (gross-coefficients books n)
        gross-floor (get-in encoded-constraints [:gross-floor :min])
        gross-max (get-in encoded-constraints [:gross-exposure :max])]
    (assoc template
           :kind :sequential-equal-risk
           :covariance covariance
           :books books
           :targets (assoc targets
                           :relative-contributions
                           (vec (repeat n (/ 1 n))))
           :current-weights (vec (:current-weights encoded-constraints))
           :max-turnover (:max-turnover encoded-constraints)
           :rebalance-tolerance (:rebalance-tolerance encoded-constraints)
           :equalities (vec (:equalities template))
           :inequalities (vec (concat
                               (when (finite-number? gross-floor)
                                 [{:code :gross-floor
                                   :coefficients signs
                                   :lower gross-floor}])))
           :l1-constraints (vec (concat
                                 (when (finite-number? gross-max)
                                   [{:code :gross-exposure
                                     :max gross-max
                                     :requires-split-variables? true}])
                                 (:l1-constraints template))))))

(defn build-plan
  "Solver plan for :equal-risk: presolve feasibility, then one
  :sequential-equal-risk problem carrying everything the iterative solver and
  the final validation need. Expected returns are deliberately absent — this
  objective never consumes them."
  [{:keys [instrument-ids covariance encoded-constraints]}]
  (let [presolve-result (equal-risk-presolve/presolve
                         (assoc encoded-constraints
                                :instrument-ids (vec instrument-ids))
                         covariance)]
    (if (= :infeasible (:status presolve-result))
      {:status :infeasible
       :reason :equal-risk-presolve
       :details {:violations (:violations presolve-result)}}
      (let [{:keys [books targets]} presolve-result
            covariance* (:covariance presolve-result)
            template (subproblem-template (assoc encoded-constraints
                                                 :instrument-ids (vec instrument-ids))
                                          books
                                          targets)]
        {:status :ok
         :strategy :sequential-equal-risk
         :selection-objective {:kind :equal-risk}
         :warnings (vec (:warnings presolve-result))
         :problems [(plan-problem (assoc encoded-constraints
                                         :instrument-ids (vec instrument-ids))
                                  covariance*
                                  books
                                  targets
                                  template)]}))))
