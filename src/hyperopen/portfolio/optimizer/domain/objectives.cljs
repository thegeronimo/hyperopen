(ns hyperopen.portfolio.optimizer.domain.objectives)

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
