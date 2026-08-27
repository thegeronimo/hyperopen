(ns hyperopen.portfolio.optimizer.domain.objectives
  (:require [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.domain.cash-collapse :as cash-collapse]
            [hyperopen.portfolio.optimizer.domain.closed-form :as closed-form]
            [hyperopen.portfolio.optimizer.domain.encoded-rows :as rows]
            [hyperopen.portfolio.optimizer.domain.equal-risk-plan :as equal-risk-plan]
            [hyperopen.portfolio.optimizer.domain.inverse-volatility-plan
             :as inverse-volatility-plan]))

(def default-frontier-point-count
  40)

(def ^:private min-return-tilt
  0.025)

(def ^:private max-return-tilt
  409.6)

(defn- bounded-frontier-point-count
  [point-count]
  (-> (or point-count default-frontier-point-count)
      (max 2)
      (min 80)
      (js/Math.floor)))

(defn- log-spaced-return-tilts
  [point-count]
  (let [point-count* (bounded-frontier-point-count point-count)
        non-zero-count (dec point-count*)
        growth-ratio (/ max-return-tilt min-return-tilt)]
    (into [0]
          (map (fn [idx]
                 (let [ratio (if (= 1 non-zero-count)
                               1
                               (/ idx (dec non-zero-count)))]
                   (* min-return-tilt
                      (js/Math.pow growth-ratio ratio)))))
          (range non-zero-count))))

(def default-return-tilts
  (log-spaced-return-tilts default-frontier-point-count))

(def ^:private finite-number? coercion/finite-number?)

(defn- linear-vector
  [expected-returns return-tilt]
  (mapv #(- (* (or return-tilt 0) %)) expected-returns))

(defn- linear-spaced
  [start end point-count]
  (let [point-count* (-> (or point-count 1)
                         (max 1)
                         (js/Math.floor))]
    (if (= 1 point-count*)
      [end]
      (mapv (fn [idx]
              (+ start
                 (* (- end start)
                    (/ idx (dec point-count*)))))
            (range point-count*)))))

(defn- greedy-return
  [expected-returns lower-bounds upper-bounds target-net direction]
  (when (finite-number? target-net)
    (let [weights (vec lower-bounds)
          remaining (- target-net (reduce + 0 lower-bounds))
          indexes (->> expected-returns
                       (map-indexed (fn [idx value]
                                      {:idx idx
                                       :value value}))
                       (sort-by :value (if (= :max direction) > <)))]
      (when (>= remaining -1e-10)
        (loop [weights* weights
               remaining* remaining
               remaining-indexes indexes]
          (if (<= remaining* 1e-10)
            (reduce + 0 (map * expected-returns weights*))
            (when (seq remaining-indexes)
              (let [idx (:idx (first remaining-indexes))
                    room (- (nth upper-bounds idx)
                            (nth weights* idx))
                    allocation (min remaining* room)]
                (recur (assoc weights* idx (+ (nth weights* idx) allocation))
                       (- remaining* allocation)
                       (rest remaining-indexes))))))))))

(defn feasible-return-range
  [{:keys [expected-returns encoded-constraints]}]
  (let [lower-bounds (:lower-bounds encoded-constraints)
        upper-bounds (:upper-bounds encoded-constraints)
        target-net (:net-target encoded-constraints)]
    {:min-return (greedy-return expected-returns lower-bounds upper-bounds target-net :min)
     :max-return (greedy-return expected-returns lower-bounds upper-bounds target-net :max)}))

(defn- base-weight
  [lower upper]
  (cond
    (and (finite-number? lower) (pos? lower)) lower
    (and (finite-number? upper) (neg? upper)) upper
    :else 0))

(defn- return-bound-candidates
  [expected-returns lower-bounds upper-bounds]
  (->> (mapv (fn [idx expected-return lower upper]
               (let [base (base-weight lower upper)]
                 [{:idx idx
                   :sign 1
                   :capacity (max 0 (- upper base))
                   :marginal-return expected-return}
                  {:idx idx
                   :sign -1
                   :capacity (max 0 (- base lower))
                   :marginal-return (- expected-return)}]))
             (range)
             expected-returns
             lower-bounds
             upper-bounds)
       (apply concat)
       (filter #(pos? (:capacity %)))
       (sort-by :marginal-return >)))

(defn- net-min
  [encoded-constraints]
  (or (get-in encoded-constraints [:net-exposure :min])
      (:net-target encoded-constraints)
      js/Number.NEGATIVE_INFINITY))

(defn- net-max
  [encoded-constraints gross-max]
  (or (get-in encoded-constraints [:net-exposure :max])
      (:net-target encoded-constraints)
      gross-max
      js/Number.POSITIVE_INFINITY))

(defn- gross-max
  [encoded-constraints base-gross]
  (or (get-in encoded-constraints [:gross-exposure :max])
      (reduce + base-gross
              (map (fn [lower upper]
                     (max (js/Math.abs lower)
                          (js/Math.abs upper)))
                   (:lower-bounds encoded-constraints)
                   (:upper-bounds encoded-constraints)))))

(defn- signed-gross-max-return
  [{:keys [expected-returns encoded-constraints]}]
  (let [lower-bounds (:lower-bounds encoded-constraints)
        upper-bounds (:upper-bounds encoded-constraints)
        base-weights (mapv base-weight lower-bounds upper-bounds)
        base-gross (reduce + 0 (map js/Math.abs base-weights))
        gross-limit (gross-max encoded-constraints base-gross)
        net-lower (net-min encoded-constraints)
        net-upper (net-max encoded-constraints gross-limit)
        candidates (return-bound-candidates expected-returns lower-bounds upper-bounds)]
    (when (and (seq expected-returns)
               (finite-number? gross-limit))
      (loop [remaining candidates
             gross-used base-gross
             net-used (reduce + 0 base-weights)
             return-used (reduce + 0 (map * expected-returns base-weights))]
        (if-let [{:keys [sign capacity marginal-return]} (first remaining)]
          (let [needs-net? (< net-used net-lower)
                useful? (or (pos? marginal-return) needs-net?)
                net-room (if (pos? sign)
                           (- net-upper net-used)
                           (- net-used net-lower))
                gross-room (- gross-limit gross-used)
                allocation (min capacity gross-room net-room)]
            (if (and useful?
                     (> allocation 1.0e-10))
              (recur (rest remaining)
                     (+ gross-used allocation)
                     (+ net-used (* sign allocation))
                     (+ return-used (* allocation marginal-return)))
              (recur (rest remaining)
                     gross-used
                     net-used
                     return-used)))
          return-used)))))

(defn- frontier-max-return
  [opts]
  (let [range (feasible-return-range opts)]
    (or (:max-return range)
        (signed-gross-max-return opts))))

(defn- direct-problem
  [{:keys [objective
           instrument-ids
           expected-returns
           covariance
           encoded-constraints
           return-tilt]}]
  (let [n (count instrument-ids)
        target-return (rows/target-return-inequality expected-returns objective)]
    {:kind :quadratic-program
     :objective-kind (:kind objective)
     :instrument-ids instrument-ids
     :quadratic covariance
     :linear (linear-vector expected-returns return-tilt)
     :return-tilt (or return-tilt 0)
     :equalities (rows/equality-constraints encoded-constraints n)
     :inequalities (vec (concat (rows/net-inequalities encoded-constraints n)
                                (rows/net-band-inequalities encoded-constraints)
                                (when-let [floor (rows/gross-floor-inequality encoded-constraints)]
                                  [floor])
                                (when target-return [target-return])))
     :l1-constraints (rows/l1-constraints encoded-constraints)
     :lower-bounds (:lower-bounds encoded-constraints)
     :upper-bounds (:upper-bounds encoded-constraints)
     :locked-weights (:locked-weights encoded-constraints)
     :max-turnover (:max-turnover encoded-constraints)
     :rebalance-tolerance (:rebalance-tolerance encoded-constraints)}))

(defn- unbounded-bound-vector
  [bounds n unbounded-value]
  (if (and (sequential? bounds)
           (= n (count bounds)))
    (vec bounds)
    (vec (repeat n unbounded-value))))

(defn- closed-form-problem
  [{:keys [objective instrument-ids expected-returns covariance encoded-constraints]}
   eligibility]
  (let [n (count instrument-ids)
        target-return (rows/target-return-inequality expected-returns objective)]
    {:kind :closed-form-portfolio
     :objective-kind (:kind objective)
     :objective objective
     :instrument-ids instrument-ids
     :expected-returns expected-returns
     :covariance covariance
     :encoded-constraints encoded-constraints
     :closed-form-eligibility eligibility
     :return-tilt 0
     ;; The QP-equivalent constraint encoding lets existing solver-result
     ;; validation in target selection check closed-form weights unchanged.
     :equalities (rows/equality-constraints encoded-constraints n)
     :inequalities (vec (concat (rows/net-inequalities encoded-constraints n)
                                (rows/net-band-inequalities encoded-constraints)
                                (when-let [floor (rows/gross-floor-inequality encoded-constraints)]
                                  [floor])
                                (when target-return [target-return])))
     :l1-constraints (rows/l1-constraints encoded-constraints)
     :lower-bounds (unbounded-bound-vector (:lower-bounds encoded-constraints)
                                           n
                                           js/Number.NEGATIVE_INFINITY)
     :upper-bounds (unbounded-bound-vector (:upper-bounds encoded-constraints)
                                           n
                                           js/Number.POSITIVE_INFINITY)
     :locked-weights (vec (or (:locked-weights encoded-constraints) []))}))

(defn- closed-form-plan
  [{:keys [objective] :as opts}]
  (let [eligibility (closed-form/eligible? opts)]
    (when (:eligible? eligibility)
      {:status :ok
       :strategy :closed-form
       :selection-objective objective
       :problems [(closed-form-problem opts eligibility)]})))

(defn- target-return-infeasible
  [{:keys [objective expected-returns encoded-constraints]}]
  (when (and (= :target-return (:kind objective))
             (finite-number? (:target-return objective)))
    (let [{:keys [max-return]} (feasible-return-range
                                {:expected-returns expected-returns
                                 :encoded-constraints encoded-constraints})]
      (when (and (finite-number? max-return)
                 (> (:target-return objective) (+ max-return 1e-10)))
        {:status :infeasible
         :reason :target-return-above-feasible-maximum
         :details {:target-return (:target-return objective)
                   :max-return max-return}}))))
(def net-row-objective-kinds
  "Objective kinds whose planned problems actually CARRY the encoded net rows.

  Read off the plan builders, not assumed: `direct-problem` and
  `closed-form-problem` - and therefore `frontier-plan`,
  `target-return-frontier-plan`, `build-display-frontier-plan` and every
  closed-form fast path (`closed-form/closed-form-objective-kinds` is this same
  set) - concat `net-inequalities` + `net-band-inequalities` into :inequalities
  and `equality-constraints` into :equalities.

  The omissions are deliberate: equal-risk-plan/subproblem-template says
  \"Stored net rows are deliberately omitted for Equal Risk\", its plan-problem
  builds :inequalities from the gross floor ALONE, and inverse-volatility-plan
  reuses that template. request_builder never strips the net keys and
  actions.exposure/preserve-net-policy deliberately KEEPS them, so the stored
  band is always present and always ignored - which is why a net-derived
  presolve code must not speak for those two."
  #{:minimum-variance :target-return :max-sharpe :target-volatility})

(defn- conditional-presolve-plan
  "Promotes `encode-constraints`' :conditional-violations to a presolve rejection
  for the objective kinds that encode net rows.

  encode-constraints is objective-agnostic, so the joint reachability check
  cannot set :status there: it is derived from NET constraints, which Equal Risk
  and Risk-weighted sizing never encode. As a status it made the DEFAULT draft
  net pin (:net-min 1.0 / :net-max 1.0, defaults.cljs) plus any gross band
  dragged on the pad reject both objectives on requests they solve correctly - a
  false positive, the one failure mode this presolve may never have."
  [{:keys [objective encoded-constraints]}]
  (let [conditional (:conditional-violations encoded-constraints)]
    (when (and (seq conditional)
               (contains? net-row-objective-kinds (:kind objective)))
      {:status :infeasible
       :reason :constraint-presolve
       :details {:violations (vec conditional)}})))

(defn- frontier-return-tilts
  [objective return-tilts]
  (or return-tilts
      (log-spaced-return-tilts (:frontier-points objective))))

(defn- frontier-plan
  [{:keys [objective return-tilts] :as opts}]
  {:status :ok
   :strategy :frontier-sweep
   :selection-objective objective
   :problems (mapv (fn [return-tilt]
                     (direct-problem (assoc opts
                                            :objective {:kind :return-tilted}
                                            :return-tilt return-tilt)))
                   (frontier-return-tilts objective return-tilts))})

(defn- target-return-floor-values
  [objective max-return]
  (let [point-count (bounded-frontier-point-count (:frontier-points objective))
        floor-count (dec point-count)
        requested-floor (:target-return objective)
        start (cond
                (finite-number? requested-floor)
                (min requested-floor max-return)

                (pos? max-return)
                0

                :else
                max-return)]
    (when (and (pos? floor-count)
               (finite-number? max-return))
      (linear-spaced start max-return floor-count))))

(defn- target-return-frontier-plan
  [{:keys [objective return-tilts] :as opts}]
  (when-not return-tilts
    (when-let [max-return (frontier-max-return opts)]
      (when-let [target-returns (seq (target-return-floor-values objective max-return))]
        {:status :ok
         :strategy :frontier-sweep
         :selection-objective objective
         :problems (into [(direct-problem (assoc opts
                                                 :objective {:kind :return-tilted}
                                                 :return-tilt 0))]
                         (map (fn [target-return]
                                (direct-problem
                                 (assoc opts
                                        :objective {:kind :target-return
                                                    :target-return target-return}
                                        :return-tilt 0)))
                              target-returns))}))))

(defn build-display-frontier-plan
  [{:keys [objective] :as opts}]
  (case (:kind objective)
    :minimum-variance
    (or (target-return-frontier-plan opts)
        (frontier-plan opts))

    :target-return
    (or (target-return-frontier-plan opts)
        (frontier-plan opts))

    :max-sharpe
    (or (target-return-frontier-plan opts)
        (frontier-plan opts))

    :target-volatility
    (or (target-return-frontier-plan opts)
        (frontier-plan opts))

    nil))

;; Follow-up design note: selected-portfolio solving should stay separate
;; from display-frontier building as solver strategies grow.
;; - Unrestricted/equality-only selected portfolios: closed-form solve
;;   (implemented below via domain.closed-form).
;; - Constrained minimum variance / target return: existing single QP.
;; - Constrained Max Sharpe: future direct Schaible-transformed QP when the
;;   encoded constraints stay compatible with the transform.
;; - Constrained Target Volatility: future bisection over target-return QPs,
;;   or QCQP/SOCP solver support.
;; - Display frontier: future adaptive target-return refinement under the
;;   existing hard point cap, instead of the static return-tilt grid.
;; - True adaptive refinement needs a sequential strategy runner; the current
;;   solve engine maps over a pre-built vector of problems.
;;
;; Closed-form firing: the fast path solves the equality-core candidate and
;; accepts it only when post-validation confirms it already satisfies every
;; encoded constraint (bounds, long-only, gross/L1, turnover, locks). Because
;; the candidate is optimal over a superset of the constrained region, a
;; feasible candidate is optimal for the constrained problem too; an infeasible
;; one falls back to the QP/frontier plan below. See domain.closed-form.
(defn build-solver-plan
  [{:keys [objective encoded-constraints] :as opts}]
  (let [target-return-failure (target-return-infeasible opts)
        conditional-failure (conditional-presolve-plan opts)
        cash-collapse-failure (cash-collapse/collapse-to-cash-plan opts)]
    (cond
      (= :infeasible (:status encoded-constraints))
      {:status :infeasible
       :reason :constraint-presolve
       :details {:violations (:violations encoded-constraints)}}

      ;; Same :reason as the hard branch on purpose: to every consumer this IS a
      ;; constraint presolve rejection. Only WHICH objectives it applies to
      ;; differs, and that is decided here rather than in encode-constraints.
      conditional-failure
      conditional-failure

      target-return-failure
      target-return-failure

      ;; BEFORE closed-form-plan on purpose: a zero net pin still satisfies
      ;; core-eligibility (net-target 0 is not nil), and the GMV formula then
      ;; returns (0/a)·u = the all-cash vector, which passes post-validation
      ;; against constraints that are all ceilings. The fast path would answer
      ;; the degenerate problem just as wrongly as the QP.
      cash-collapse-failure
      cash-collapse-failure

      :else
      (or
       (closed-form-plan opts)
       (case (:kind objective)
        :minimum-variance
        {:status :ok
         :strategy :single-qp
         :problems [(direct-problem (assoc opts :return-tilt 0))]}

        :target-return
        {:status :ok
         :strategy :single-qp
         :problems [(direct-problem (assoc opts :return-tilt 0))]}

        :max-sharpe
        (frontier-plan opts)

        :target-volatility
        (frontier-plan opts)

        ;; Covariance-only: never consumes expected returns, never sweeps a
        ;; frontier. One :sequential-equal-risk problem (or presolve failure).
        :equal-risk
        (equal-risk-plan/build-plan opts)

        ;; Covariance-diagonal-only: the 1/σ seed made feasible by one
        ;; σ-weighted projection QP (or presolve failure).
        :inverse-volatility
        (inverse-volatility-plan/build-plan opts)

        {:status :infeasible
         :reason :unknown-objective
         :details {:objective (:kind objective)}})))))
