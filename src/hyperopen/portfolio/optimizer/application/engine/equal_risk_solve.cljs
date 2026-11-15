(ns hyperopen.portfolio.optimizer.application.engine.equal-risk-solve
  "Deterministic sequential solver for the :equal-risk objective.

  Each of the deterministic initializers (domain.equal-risk/seed-kinds) is
  projected onto the FULL feasible region by a `min ||y - seed||^2` QP, then
  refined by sequential quadratic programming: at iterate w solve the convex
  QP `min 0.5 y'By + (g - Bw)'y` over the gross equality + bounds + turnover
  region, Armijo-backtrack along the (always feasible, by convexity) segment
  toward the QP solution, and update B with a damped BFGS step. The best
  feasible final iterate across initializers wins; classification of the
  realized contribution error is truthful (:exact / :approximate /
  :not-converged) and computed by the payload from the PUBLISHED weights.

  Every subproblem goes through the injected `solve-problem` (the existing
  quadprog/OSQP adapter). quadprog returns plain maps, OSQP returns Promises;
  `chain` below threads both, so the synchronous engine path stays
  synchronous and the worker path becomes a single Promise. All numeric
  tolerances live in domain.equal-risk/tolerances."
  (:require [hyperopen.portfolio.optimizer.domain.equal-risk :as equal-risk]
            [hyperopen.portfolio.optimizer.domain.equal-risk-plan :as equal-risk-plan]
            [hyperopen.portfolio.optimizer.domain.risk-contributions :as risk-contributions]))

(defn- thenable?
  [value]
  (and (some? value)
       (not (map? value))
       (fn? (unchecked-get value "then"))))

(defn- chain
  "Applies f to value, through .then when value is a promise. Keeps the whole
  solve synchronous when the injected solver is synchronous."
  [value f]
  (if (thenable? value)
    (.then value f)
    (f value)))

(defn- report!
  [on-progress payload]
  (when (fn? on-progress)
    (on-progress payload)))

(def ^:private solve-percent-floor 5)
(def ^:private solve-percent-span 90)

(defn- iteration-percent
  [init-index iteration]
  (let [max-iterations (:max-iterations equal-risk/tolerances)
        planned (count equal-risk/seed-kinds)
        fraction (/ (+ init-index (min 1 (/ iteration max-iterations)))
                    planned)]
    (min 95 (+ solve-percent-floor (* solve-percent-span fraction)))))

(defn- error-stats
  [relative-contributions targets]
  (let [errors (mapv - relative-contributions targets)]
    {:max-absolute-error (reduce max 0 (map js/Math.abs errors))
     :rms-error (js/Math.sqrt (/ (reduce + 0 (map #(* % %) errors))
                                 (max 1 (count errors))))}))

(defn- rms-detail
  [evaluation targets]
  (let [{:keys [rms-error]} (error-stats (:relative-contributions evaluation) targets)]
    (str (.toFixed (* 100 rms-error) 2) "% rms")))

(defn- finish
  [seed-kind state termination-reason converged?]
  {:status :completed
   :seed-kind seed-kind
   :weights (:w state)
   :objective (:objective state)
   :evaluation (:evaluation state)
   :iterations (:iteration state)
   :termination-reason termination-reason
   :converged? converged?
   :step-residual (:step-residual state)})

(defn- line-search
  "Deterministic Armijo backtracking from alpha = 1. A candidate with
  degenerate variance is rejected like an insufficient decrease (the segment
  endpoints are feasible; an interior degenerate point just forces a shorter
  step). Returns {:w :alpha :evaluation} or nil when exhausted."
  [covariance targets w d objective directional]
  (let [{:keys [armijo-c1 min-line-search-alpha max-line-search-steps]}
        equal-risk/tolerances]
    (loop [alpha 1
           step 0]
      (when (and (< step max-line-search-steps)
                 (>= alpha min-line-search-alpha))
        (let [candidate (mapv (fn [w-i d-i] (+ w-i (* alpha d-i))) w d)
              evaluation (risk-contributions/evaluate covariance candidate targets)
              sufficient (+ objective (* armijo-c1 alpha directional))]
          (if (and (= :ok (:status evaluation))
                   (<= (:objective evaluation) sufficient))
            {:w candidate
             :alpha alpha
             :evaluation evaluation}
            (recur (* 0.5 alpha) (inc step))))))))

(defn- iterate-init
  "One SQP iteration; recurses (through `chain`) until a termination rule
  fires. Every accepted iterate is feasible: the projection/QP results are
  validated against the template and Armijo steps stay on the segment between
  two feasible points of a convex region."
  [{:keys [template covariance targets solve-problem on-progress
           seed-kind init-index]
    :as ctx}
   state]
  (let [{:keys [max-iterations step-tolerance-scale objective-improvement]}
        equal-risk/tolerances
        gross (get-in ctx [:book-targets :gross])
        step-tolerance (* step-tolerance-scale (max 1 (or gross 1)))]
    (if (>= (:iteration state) max-iterations)
      (finish seed-kind state :max-iterations false)
      (chain
       (solve-problem (equal-risk-plan/iteration-problem template
                                                    (:b state)
                                                    (:g state)
                                                    (:w state)))
       (fn [qp-result]
         (if (not= :solved (:status qp-result))
           (finish seed-kind state :subproblem-failed false)
           (let [y (vec (:weights qp-result))
                 violations (equal-risk-plan/weight-violations template y)]
             (if (seq violations)
               (finish seed-kind state :subproblem-infeasible false)
               (let [w (:w state)
                     d (mapv - y w)
                     step-residual (reduce max 0 (map js/Math.abs d))
                     state (assoc state :step-residual step-residual)
                     directional (reduce + 0 (map * (:g state) d))]
                 (cond
                   (<= step-residual step-tolerance)
                   (finish seed-kind state :step-tolerance true)

                   (>= directional (- 1e-18))
                   (finish seed-kind state :step-tolerance true)

                   :else
                   (if-let [accepted (line-search covariance targets w d
                                                  (:objective state) directional)]
                     (let [evaluation (:evaluation accepted)
                           w+ (:w accepted)
                           g+ (:gradient evaluation)
                           s (mapv - w+ w)
                           y-vec (mapv - g+ (:g state))
                           b (if (:rescaled? state)
                               (:b state)
                               (equal-risk/rescale-hessian (:b state) s y-vec))
                           b+ (equal-risk/bfgs-update b s y-vec)
                           improvement (- (:objective state) (:objective evaluation))
                           small-improvements (if (< improvement objective-improvement)
                                                (inc (:small-improvements state))
                                                0)
                           state+ (assoc state
                                         :w w+
                                         :b b+
                                         :g g+
                                         :objective (:objective evaluation)
                                         :evaluation evaluation
                                         :iteration (inc (:iteration state))
                                         :small-improvements small-improvements
                                         :rescaled? true)]
                       (report! on-progress
                                {:step :solve
                                 :status :running
                                 :percent (iteration-percent init-index
                                                             (:iteration state+))
                                 :detail (str "init " (inc init-index) "/"
                                              (count equal-risk/seed-kinds)
                                              " · iter " (:iteration state+)
                                              " · " (rms-detail evaluation targets))})
                       (if (>= small-improvements 2)
                         (finish seed-kind state+ :objective-improvement true)
                         (iterate-init ctx state+)))
                     (finish seed-kind state :line-search-exhausted false))))))))))))

(defn- run-initializer
  [{:keys [problem template covariance targets solve-problem] :as ctx}
   seed-kind
   init-index]
  (let [seed (equal-risk/seed-weights seed-kind
                                      problem
                                      (:books problem)
                                      (:book-targets ctx)
                                      covariance)]
    (if-not seed
      {:status :seed-unavailable :seed-kind seed-kind}
      (chain
       (solve-problem (equal-risk-plan/projection-problem template seed))
       (fn [projection]
         (if (not= :solved (:status projection))
           {:status :no-feasible-start
            :seed-kind seed-kind
            :details (select-keys projection [:status :reason :message])}
           (let [w0 (vec (:weights projection))
                 violations (equal-risk-plan/weight-violations template w0)]
             (if (seq violations)
               {:status :no-feasible-start
                :seed-kind seed-kind
                :violations violations}
               (let [evaluation (risk-contributions/evaluate covariance w0 targets)]
                 (if (not= :ok (:status evaluation))
                   {:status :degenerate-variance
                    :seed-kind seed-kind
                    :details (:details evaluation)}
                   (iterate-init (assoc ctx
                                        :seed-kind seed-kind
                                        :init-index init-index)
                                 {:w w0
                                  :b (equal-risk/identity-hessian (count w0))
                                  :g (:gradient evaluation)
                                  :objective (:objective evaluation)
                                  :evaluation evaluation
                                  :iteration 0
                                  :small-improvements 0
                                  :rescaled? false
                                  :step-residual nil})))))))))))

(defn- exact-outcome?
  [{:keys [status converged? evaluation]} targets n]
  (and (= :completed status)
       converged?
       (= :exact
          (equal-risk/classify-quality
           (error-stats (:relative-contributions evaluation) targets)
           n
           converged?))))

(defn- run-initializers
  [ctx kinds outcomes]
  (if (empty? kinds)
    outcomes
    (chain
     (run-initializer ctx (first kinds) (count outcomes))
     (fn [outcome]
       (let [outcomes* (conj outcomes outcome)]
         (if (exact-outcome? outcome (:targets ctx) (:n ctx))
           outcomes*
           (run-initializers ctx (rest kinds) outcomes*)))))))

(defn- initialization-summaries
  [outcomes]
  (mapv (fn [outcome]
          (-> (select-keys outcome
                           [:seed-kind :status :objective :iterations
                            :termination-reason :converged?])
              (update :objective #(or % nil))))
        outcomes))

(defn- failure-result
  [outcomes]
  (let [
        relevant (remove #(= :seed-unavailable (:status %)) outcomes)
        degenerate-only? (and (seq relevant)
                              (every? #(= :degenerate-variance (:status %))
                                      relevant))]
    {:status :infeasible
     :solver :sequential-equal-risk
     :reason (if degenerate-only?
               :equal-risk-degenerate-variance
               :equal-risk-no-feasible-start)
     :message (if degenerate-only?
                "Portfolio variance is numerically degenerate for every feasible start; risk contributions cannot be normalized."
                "No feasible starting portfolio satisfies the exposure targets together with bounds, locks, and turnover. A tight turnover limit is the most common cause.")
     :details {:initializations (initialization-summaries outcomes)
               :violations (vec (mapcat :violations outcomes))}}))

(defn- best-outcome
  [outcomes]
  (reduce (fn [best outcome]
            (if (and (= :completed (:status outcome))
                     (or (nil? best)
                         (< (:objective outcome) (:objective best))))
              outcome
              best))
          nil
          outcomes))

(defn solve
  "Solves one :sequential-equal-risk plan problem through the injected QP
  solver. Returns a solver-result map (or a Promise of one when the injected
  solver is asynchronous)."
  ([problem solve-problem]
   (solve problem solve-problem nil))
  ([problem solve-problem on-progress]
   (let [covariance (:covariance problem)
         targets (get-in problem [:targets :relative-contributions])
         book-targets (select-keys (:targets problem) [:gross])
         template (equal-risk-plan/subproblem-template
                   {:instrument-ids (:instrument-ids problem)
                    :lower-bounds (:lower-bounds problem)
                    :upper-bounds (:upper-bounds problem)
                    :locked-weights (:locked-weights problem)
                    :max-turnover (:max-turnover problem)
                    :current-weights (:current-weights problem)}
                   (:books problem)
                   (:targets problem))
         n (count (:instrument-ids problem))
         ctx {:problem problem
              :template template
              :covariance covariance
              :targets targets
              :book-targets book-targets
              :n n
              :solve-problem solve-problem
              :on-progress on-progress}]
     (report! on-progress
              {:step :solve
               :status :running
               :percent solve-percent-floor
               :detail (str "equal risk · " (count equal-risk/seed-kinds)
                            " deterministic starts")})
     (chain
      (run-initializers ctx equal-risk/seed-kinds [])
      (fn [outcomes]
        (if-let [best (best-outcome outcomes)]
          {:status :solved
           :solver :sequential-equal-risk
           :weights (:weights best)
           :objective-value (:objective best)
           :iterations (:iterations best)
           :equal-risk {:strategy :sequential-equal-risk
                        :converged? (boolean (:converged? best))
                        :termination-reason (:termination-reason best)
                        :iterations (:iterations best)
                        :total-iterations (reduce + 0 (keep :iterations outcomes))
                        :initialization-count (count outcomes)
                        :selected-initialization (:seed-kind best)
                        :objective-value (:objective best)
                        :step-residual (:step-residual best)
                        :exactness-tolerance (equal-risk/exactness-tolerance n)
                        :initializations (initialization-summaries outcomes)}}
          (failure-result outcomes)))))))
