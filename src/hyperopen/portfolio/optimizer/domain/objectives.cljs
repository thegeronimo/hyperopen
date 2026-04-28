(ns hyperopen.portfolio.optimizer.domain.objectives)

(def default-return-tilts
  [0 0.025 0.05 0.1 0.2 0.4 0.8 1.6 3.2 6.4 12.8])

(defn- ones
  [n]
  (vec (repeat n 1)))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- linear-vector
  [expected-returns return-tilt]
  (mapv #(- (* (or return-tilt 0) %)) expected-returns))

(defn- equality-constraints
  [encoded-constraints n]
  (let [net-exposure (:net-exposure encoded-constraints)
        net-target (:net-target encoded-constraints)]
    (cond
      (finite-number? net-target)
      [{:code :net-exposure
        :coefficients (ones n)
        :target net-target}]

      (and (map? net-exposure)
           (= (:min net-exposure) (:max net-exposure))
           (finite-number? (:min net-exposure)))
      [{:code :net-exposure
        :coefficients (ones n)
        :target (:min net-exposure)}]

      :else [])))

(defn- net-inequalities
  [encoded-constraints n]
  (let [net-exposure (:net-exposure encoded-constraints)]
    (if (and (map? net-exposure)
             (not= (:min net-exposure) (:max net-exposure)))
      (vec (concat
            (when (finite-number? (:min net-exposure))
              [{:code :net-exposure
                :coefficients (ones n)
                :lower (:min net-exposure)}])
            (when (finite-number? (:max net-exposure))
              [{:code :net-exposure
                :coefficients (ones n)
                :upper (:max net-exposure)}])))
      [])))

(defn- target-return-inequality
  [expected-returns objective]
  (when (and (= :target-return (:kind objective))
             (finite-number? (:target-return objective)))
    {:code :target-return
     :coefficients expected-returns
     :lower (:target-return objective)}))

(defn- l1-constraints
  [encoded-constraints]
  (let [max-gross (get-in encoded-constraints [:gross-exposure :max])
        max-turnover (:max-turnover encoded-constraints)]
    (cond-> []
      (finite-number? max-gross)
      (conj {:code :gross-exposure
             :max max-gross
             :requires-split-variables? true})

      (finite-number? max-turnover)
      (conj {:code :turnover
             :max (* 2 max-turnover)
             :current-weights (:current-weights encoded-constraints)
             :requires-split-variables? true}))))

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

(defn- direct-problem
  [{:keys [objective
           instrument-ids
           expected-returns
           covariance
           encoded-constraints
           return-tilt]}]
  (let [n (count instrument-ids)
        target-return (target-return-inequality expected-returns objective)]
    {:kind :quadratic-program
     :objective-kind (:kind objective)
     :instrument-ids instrument-ids
     :quadratic covariance
     :linear (linear-vector expected-returns return-tilt)
     :return-tilt (or return-tilt 0)
     :equalities (equality-constraints encoded-constraints n)
     :inequalities (vec (concat (net-inequalities encoded-constraints n)
                                (when target-return [target-return])))
     :l1-constraints (l1-constraints encoded-constraints)
     :lower-bounds (:lower-bounds encoded-constraints)
     :upper-bounds (:upper-bounds encoded-constraints)
     :locked-weights (:locked-weights encoded-constraints)
     :max-turnover (:max-turnover encoded-constraints)
     :rebalance-tolerance (:rebalance-tolerance encoded-constraints)}))

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

(defn- frontier-plan
  [{:keys [objective return-tilts] :as opts}]
  {:status :ok
   :strategy :frontier-sweep
   :selection-objective objective
   :problems (mapv (fn [return-tilt]
                     (direct-problem (assoc opts
                                            :objective {:kind :return-tilted}
                                            :return-tilt return-tilt)))
                   (or return-tilts default-return-tilts))})

(defn build-display-frontier-plan
  [{:keys [objective] :as opts}]
  (case (:kind objective)
    :minimum-variance
    (frontier-plan opts)

    :target-return
    (frontier-plan opts)

    nil))

(defn build-solver-plan
  [{:keys [objective encoded-constraints] :as opts}]
  (let [target-return-failure (target-return-infeasible opts)]
    (cond
      (= :infeasible (:status encoded-constraints))
      {:status :infeasible
       :reason :constraint-presolve
       :details {:violations (:violations encoded-constraints)}}

      target-return-failure
      target-return-failure

      :else
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

        {:status :infeasible
         :reason :unknown-objective
         :details {:objective (:kind objective)}}))))
