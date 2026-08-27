(ns hyperopen.portfolio.optimizer.application.engine.target-selection
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.domain.frontier :as frontier]
            [hyperopen.portfolio.optimizer.domain.math :as math]))

(def ^:private solution-tolerance
  1.0e-5)

(defn- sqrt
  [value]
  (js/Math.sqrt (max 0 value)))

(defn- finite-weights?
  [weights]
  (and (sequential? weights)
       (every? math/finite-number? weights)))

(defn- expected-weight-count?
  [problem weights]
  (= (count (:instrument-ids problem))
     (count weights)))

(defn- format-number
  [value]
  (if (math/finite-number? value)
    (.toFixed value 4)
    "N/A"))

(defn- constraint-label
  [constraint fallback]
  (if-let [code (:code constraint)]
    (name code)
    fallback))

(defn- range-phrase
  "How an inequality row's admissible range reads in a violation message.

  Most rows here are ONE-SIDED: the gross floor is `sum(sign_i*w_i) >= G` and
  each percentage net-band edge is a single `<=` or `>=` row, so `:upper` (or
  `:lower`) is legitimately absent. Rendering those through a fixed
  \"between <lower> and <upper>\" template printed the missing side as `N/A`,
  which read as corrupt data on a perfectly ordinary one-sided constraint and
  sent at least one bug report chasing the wrong thing."
  [lower upper]
  (let [lower? (math/finite-number? lower)
        upper? (math/finite-number? upper)]
    (cond
      (and lower? upper?) (str "expected between "
                               (format-number lower)
                               " and "
                               (format-number upper))
      lower? (str "expected at least " (format-number lower))
      upper? (str "expected at most " (format-number upper))
      ;; No finite bound at all cannot produce a violation (within-lower?/
      ;; within-upper? both pass), so this arm exists only for totality.
      :else "expected within its bounds")))

(def ^:private no-value-phrase
  ;; A row whose VALUE could not be computed at all -- a :turnover L1 row whose
  ;; :current-weights are absent or the wrong length, or a linear row whose
  ;; coefficients do not match the returned weight vector -- used to render as
  ;; "... but solver returned N/A.": a sentence shaped like a numeric
  ;; comparison with a non-number spliced into the number's place. That reads
  ;; as corrupt data rather than as the missing input it actually is, which is
  ;; the same wart `range-phrase` fixed for a missing BOUND.
  "but the solver returned no value for this row")

(defn- comparison-message
  "\"<label> <expectation> but solver returned <value>.\", degrading to a plain
  statement when the value could not be computed instead of printing \"N/A\"
  where a number belongs."
  [label expectation value]
  (if (math/finite-number? value)
    (str label " " expectation " but solver returned " (format-number value) ".")
    (str label " " expectation " " no-value-phrase ".")))

(defn- within-lower?
  [value lower]
  (or (not (math/finite-number? lower))
      (>= value (- lower solution-tolerance))))

(defn- within-upper?
  [value upper]
  (or (not (math/finite-number? upper))
      (<= value (+ upper solution-tolerance))))

(defn- linear-constraint-value
  [weights constraint]
  (let [coefficients (:coefficients constraint)]
    (when (and (sequential? coefficients)
               (= (count weights) (count coefficients))
               (every? math/finite-number? coefficients))
      (math/dot coefficients weights))))

(defn- abs-sum
  [values]
  (reduce + 0 (map js/Math.abs values)))

(defn- bounds-violations
  [problem weights]
  (let [lower-bounds (:lower-bounds problem)
        upper-bounds (:upper-bounds problem)]
    (cond
      (not= (count weights) (count lower-bounds) (count upper-bounds))
      [{:code :solver-result-bounds-shape-violation
        :message "Solver result bounds did not match the returned weight count."}]

      :else
      (->> (map-indexed (fn [idx [weight lower upper]]
                          (cond
                            (not (within-lower? weight lower))
                            {:code :solver-result-bound-violation
                             :bound :lower
                             :instrument-id (get-in problem [:instrument-ids idx])
                             :index idx
                             :target lower
                             :value weight
                             :message (str "weight " idx " lower bound "
                                           (format-number lower)
                                           " but solver returned "
                                           (format-number weight)
                                           ".")}

                            (not (within-upper? weight upper))
                            {:code :solver-result-bound-violation
                             :bound :upper
                             :instrument-id (get-in problem [:instrument-ids idx])
                             :index idx
                             :target upper
                             :value weight
                             :message (str "weight " idx " upper bound "
                                           (format-number upper)
                                           " but solver returned "
                                           (format-number weight)
                                           ".")}))
                        (map vector weights lower-bounds upper-bounds))
           (remove nil?)
           vec))))

(defn- equality-violations
  [problem weights]
  (->> (or (:equalities problem) [])
       (keep (fn [equality]
               (let [target (:target equality)
                     value (linear-constraint-value weights equality)]
                 (when-not (and (math/finite-number? target)
                                (math/finite-number? value)
                                (<= (js/Math.abs (- value target))
                                    solution-tolerance))
                   {:code :solver-result-equality-violation
                    :constraint-code (:code equality)
                    :target target
                    :value value
                    :difference (when (and (math/finite-number? target)
                                           (math/finite-number? value))
                                  (- value target))
                    :message (if (math/finite-number? target)
                               (comparison-message
                                (constraint-label equality "equality")
                                (str "expected " (format-number target))
                                value)
                               ;; A non-finite :target is a broken encoding, not
                               ;; a number the solver missed; say that instead of
                               ;; "expected N/A but solver returned 0.0000."
                               (str (constraint-label equality "equality")
                                    " target is not a finite number."))}))))
       vec))

(defn- inequality-violations
  [problem weights]
  (->> (or (:inequalities problem) [])
       (keep (fn [inequality]
               (let [value (linear-constraint-value weights inequality)
                     lower (:lower inequality)
                     upper (:upper inequality)]
                 (when-not (and (math/finite-number? value)
                                (within-lower? value lower)
                                (within-upper? value upper))
                   {:code :solver-result-inequality-violation
                    :constraint-code (:code inequality)
                    :lower lower
                    :upper upper
                    :value value
                    ;; `value` is nil whenever the row's coefficients do not
                    ;; line up with the returned weights, so this message has
                    ;; the same missing-value case the L1 rows do.
                    :message (comparison-message
                              (constraint-label inequality "inequality")
                              (range-phrase lower upper)
                              value)}))))
       vec))

(defn- l1-constraint-value
  [weights constraint]
  (case (:code constraint)
    :gross-exposure (abs-sum weights)
    :turnover (let [current-weights (:current-weights constraint)]
                (when (and (finite-weights? current-weights)
                           (= (count weights) (count current-weights)))
                  (abs-sum (map - weights current-weights))))
    nil))

(defn- l1-constraint-violation-code
  [constraint]
  (case (:code constraint)
    :gross-exposure :solver-result-gross-exposure-violation
    :turnover :solver-result-turnover-violation
    :solver-result-l1-constraint-violation))

(defn- l1-violations
  [problem weights]
  (->> (or (:l1-constraints problem) [])
       (keep (fn [constraint]
               (let [value (l1-constraint-value weights constraint)
                     max-value (:max constraint)]
                 (when-not (and (math/finite-number? value)
                                (within-upper? value max-value))
                   {:code (l1-constraint-violation-code constraint)
                    :constraint-code (:code constraint)
                    :max max-value
                    :value value
                    ;; `l1-constraint-value` returns nil for a :turnover row
                    ;; whose :current-weights are missing or the wrong length
                    ;; (and for any code it cannot evaluate), which is exactly
                    ;; the case that used to print "limit 2.0000 but solver
                    ;; returned N/A.".
                    :message (if (math/finite-number? max-value)
                               (comparison-message
                                (constraint-label constraint "L1 constraint")
                                (str "limit " (format-number max-value))
                                value)
                               (str (constraint-label constraint "L1 constraint")
                                    " limit is not a finite number."))}))))
       vec))

(defn- solver-result-violations
  [result]
  (let [problem (:problem result)
        weights (:weights result)]
    (cond
      (not= :solved (:status result)) []
      (not (map? problem)) [{:code :solver-result-missing-problem
                             :message "Solver result did not include the optimization problem metadata."}]
      (not (finite-weights? weights)) [{:code :solver-result-invalid-weights
                                        :message "Solver result did not include a finite weight vector."}]
      (not (expected-weight-count? problem weights))
      [{:code :solver-result-weight-count-mismatch
        :expected (count (:instrument-ids problem))
        :actual (count weights)
        :message (str "Solver returned "
                      (count weights)
                      " weights for "
                      (count (:instrument-ids problem))
                      " instruments.")}]
      :else
      (vec (concat (bounds-violations problem weights)
                   (equality-violations problem weights)
                   (inequality-violations problem weights)
                   (l1-violations problem weights))))))

(defn- solved?
  [result]
  (and (= :solved (:status result))
       (empty? (solver-result-violations result))))

(defn- portfolio-point
  [expected-returns covariance risk-free-rate idx result]
  (let [weights (:weights result)
        expected-return (math/portfolio-return weights expected-returns)
        variance (math/portfolio-variance weights covariance)
        volatility (sqrt variance)]
    (cond-> {:id idx
             :return-tilt (get-in result [:problem :return-tilt])
             :weights weights
             :expected-return expected-return
             :volatility volatility
             :sharpe (when (pos? volatility)
                       (/ (- expected-return (or risk-free-rate 0))
                          volatility))
             :solver-status (:status result)
             :solver (:solver result)
             :iterations (:iterations result)
             :elapsed-ms (:elapsed-ms result)}
      (:equal-risk result)
      (assoc :equal-risk (:equal-risk result)))))

(def ^:private solver-declined-arms
  ;; infrastructure.osqp classifies the raw primal vector BEFORE decoding it and,
  ;; when the solve produced no usable point, returns {:status :infeasible
  ;; :reason <a :solver-reason below>} carrying NO :weights. Such a result gives
  ;; `solver-result-violations` nothing to re-derive, so the solver's own
  ;; diagnosis is what the run repeats -- the generic "did not return any
  ;; feasible solution" copy would throw it away.
  ;;
  ;; The two outcomes are NOT the same fact and must not collapse onto one
  ;; run-level reason:
  ;;
  ;; - :solver-primal-infeasible is the OSQP_NAN sentinel, which OSQP writes
  ;;   into every element of solution->x exactly when it declined to produce a
  ;;   point because it diagnosed the problem as infeasible. That is the
  ;;   solver's certificate about the REQUEST, so it earns
  ;;   :constraints-unsatisfiable, which the banner headlines as "no portfolio
  ;;   satisfies every constraint".
  ;; - :solver-solution-outside-constraints is OUR residual check rejecting a
  ;;   point OSQP DID return -- an unconverged iterate, in the terms of
  ;;   `worst-row-violation` in infrastructure.osqp. It carries no certificate
  ;;   and proves nothing about the request: the constraint set may well be
  ;;   satisfiable and the solver simply failed to land inside it. Headlining
  ;;   that as unsatisfiable asserts something we never established, and sends
  ;;   the user editing constraints that may never have been the problem, so it
  ;;   gets a run-level reason of its own.
  ;;
  ;; ORDER IS PRECEDENCE, and it decides which declined result speaks for a run
  ;; that produced both kinds -- a certificate outranks a bad point rather than
  ;; plan order deciding. Within one sweep every point is handed the SAME A/l/u
  ;; (the sweep varies only the linear term, see `structural-key` in
  ;; infrastructure.osqp), so a primal infeasibility diagnosed at any point is a
  ;; statement about the constraint set the whole sweep shares, while a
  ;; row-check rejection is local to the one point that returned badly. Within
  ;; an arm the first rejection in plan order wins, so the choice is
  ;; deterministic either way.
  ;;
  ;; One entry per arm keeps the run reason and its fallback copy from drifting
  ;; apart, and a solver reason that is absent here is simply not treated as
  ;; declined -- it falls through to the generic arms below rather than
  ;; inheriting a headline that was written for a different diagnosis. Which
  ;; reason the solver actually reported stays visible per rejection under
  ;; [:details :rejected-results] and in the solver's own :message.
  [{:solver-reason :solver-primal-infeasible
    :run-reason :constraints-unsatisfiable
    :fallback-message
    "No portfolio can satisfy every constraint in this request at the same time."}
   {:solver-reason :solver-solution-outside-constraints
    :run-reason :solver-solution-failed-boundary-check
    :fallback-message
    "The solver returned a point that does not satisfy the constraints it was given."}])

(defn- non-blank
  [value]
  (when (and (string? value) (not (str/blank? value)))
    value))

(defn- self-reported-violations
  "Violations the solver attached to its OWN result.

  `solver-result-violations` re-derives violations from the returned weights, so
  it is blind to a result that carries none -- exactly the shape
  infrastructure.osqp returns when OSQP declines the problem. Only violations
  that can speak for themselves are merged: the run banner renders `:message`
  and chips the `:code`, so a message-less internal violation (equal-risk's seed
  `weight-violations`, which carry a raw index/bound and no copy) would add a
  chip no user can read while contributing no bullet -- and would flip the
  run-level classification below away from that strategy's own diagnosis."
  [result]
  (let [violations (get-in result [:details :violations])]
    (if (sequential? violations)
      (filterv #(and (map? %) (non-blank (:message %))) violations)
      [])))

(defn- rejected-results
  [solver-results]
  (->> solver-results
       (keep-indexed (fn [idx result]
                       (when-not (solved? result)
                         (cond-> {:index idx
                                  :status (:status result)
                                  :solver (:solver result)
                                  :objective-value (:objective-value result)
                                  :violations (vec (concat
                                                    (self-reported-violations result)
                                                    (solver-result-violations result)))}
                           ;; Iterative strategies attach a specific failure
                           ;; reason/message (e.g. :equal-risk-no-feasible-start);
                           ;; carry them so the run banner can say why.
                           (:reason result) (assoc :reason (:reason result))
                           (:message result) (assoc :message (:message result))))))
       vec))

(defn- declined-arm
  "The `solver-declined-arms` entry that speaks for this run, paired with the
  rejection carrying it, or nil. Deterministic: arms are tried in precedence
  order and, within an arm, the rejections are walked in plan order."
  [rejections]
  (some (fn [arm]
          (when-let [rejection (first (filter #(= (:solver-reason arm) (:reason %))
                                              rejections))]
            [arm rejection]))
        solver-declined-arms))

(defn- declined-explanation
  "Reason/message for a rejection the solver classified itself. Both come from
  the ARM, so a solver-quality failure can never be headlined as a proof that
  the request is impossible."
  [arm declined]
  {:reason (:run-reason arm)
   :message (or (non-blank (:message declined))
                (:fallback-message arm))})

(defn- failure-explanation
  "Reason/message for a run where no solver result survived validation.

  Four cases, most specific first:

  - the solver declined the problem with an infeasibility certificate -- it read
    the request correctly and reported that no point satisfies it, so repeat ITS
    explanation rather than the generic no-solution copy, which reads as a
    solver malfunction (:constraints-unsatisfiable);
  - the solver returned a point that missed the very rows it was handed, which
    is a solver-quality failure and NOT evidence about the request
    (:solver-solution-failed-boundary-check);
  - a returned point failed the post-solve re-check
    (:solver-returned-invalid-solution);
  - anything else -- a solver error, an unsupported problem, an iterative
    strategy that never found a feasible start (:solver-returned-no-solution).

  The first two are the `solver-declined-arms` entries, which carry their own
  run reason and copy, so a new solver-declined outcome cannot silently inherit
  another arm's headline."
  [rejections violations]
  (if-let [[arm declined] (declined-arm rejections)]
    (declined-explanation arm declined)
    (if (seq violations)
      {:reason :solver-returned-invalid-solution
       :message "The solver reported a solution, but it violated optimizer constraints."}
      {:reason :solver-returned-no-solution
       :message "The solver did not return any feasible solution."})))

(defn- solver-failure
  [solver-plan solver-results]
  (let [rejections (rejected-results solver-results)
        violations (vec (mapcat :violations rejections))
        {:keys [reason message]} (failure-explanation rejections violations)]
    {:status :infeasible
     :reason reason
     :message message
     :solver {:strategy (:strategy solver-plan)}
     :details {:solver-result-count (count solver-results)
               :rejected-result-count (count rejections)
               :violations violations
               :rejected-results rejections}
     :solver-results solver-results}))

(defn solved-points
  [request solver-results expected-returns covariance]
  (->> solver-results
       (keep-indexed (fn [idx result]
                       (when (solved? result)
                         (portfolio-point expected-returns
                                          covariance
                                          (:risk-free-rate request)
                                          idx
                                          result))))
       vec))

(defn target-selection
  [request solver-plan solver-results expected-returns covariance]
  (let [points (solved-points request solver-results expected-returns covariance)]
    (if (empty? points)
      (solver-failure solver-plan solver-results)
      (let [frontier-points (frontier/efficient-frontier points)
            selected (or (when (= :frontier-sweep (:strategy solver-plan))
                           (frontier/select-frontier-point frontier-points (:objective request)))
                         (first frontier-points)
                         (first points))]
        {:status :solved
         :selected selected
         :target-frontier frontier-points}))))
