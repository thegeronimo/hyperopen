(ns hyperopen.portfolio.optimizer.application.display-frontier
  (:require [hyperopen.portfolio.optimizer.domain.constraints :as constraints]
            [hyperopen.portfolio.optimizer.domain.frontier :as frontier]
            [hyperopen.portfolio.optimizer.domain.objectives :as objectives]))

(defn- universe-by-id
  [universe]
  (into {}
        (map (fn [instrument]
               [(:instrument-id instrument) instrument]))
        universe))

(defn- ordered-universe
  [universe instrument-ids]
  (let [by-id (universe-by-id universe)]
    (mapv by-id instrument-ids)))

(defn- current-weight
  [request instrument-id]
  (or (get-in request [:current-portfolio :by-instrument instrument-id :weight])
      0))

(defn- encoded-constraints
  [request instrument-ids]
  (constraints/encode-constraints
   {:universe (ordered-universe (:universe request) instrument-ids)
    :current-weights (into {}
                           (map (fn [instrument-id]
                                  [instrument-id (current-weight request instrument-id)]))
                           instrument-ids)
    :constraints (:constraints request)}))

(defn- unconstrained-frontier-constraints
  [constraints]
  (-> (or constraints {})
      (dissoc :gross-leverage
              :max-asset-weight
              :max-turnover
              :net-exposure
              :per-asset-overrides
              :per-perp-leverage-caps
              :rebalance-tolerance
              :held-position-locks)
      (assoc :long-only? true)))

(defn- display-encoded-constraints
  [request instrument-ids constraint-mode]
  (let [request* (case constraint-mode
                   :unconstrained
                   (assoc request
                          :constraints
                          (unconstrained-frontier-constraints (:constraints request)))

                   request)]
    (encoded-constraints request* instrument-ids)))

(def ^:private frontier-constraint-comparison-keys
  [:gross-exposure
   :locked-weights
   :long-only?
   :lower-bounds
   :max-turnover
   :net-exposure
   :net-target
   :upper-bounds])

(defn- equivalent-frontier-constraints?
  [left right]
  (= (select-keys left frontier-constraint-comparison-keys)
     (select-keys right frontier-constraint-comparison-keys)))

(defn- display-frontier-point-count
  [instrument-count]
  (cond
    (> instrument-count 50) 16
    (> instrument-count 30) 24
    :else nil))

(defn- display-frontier-objective
  [objective instrument-count]
  (if (some? (:frontier-points objective))
    objective
    (if-let [point-count (display-frontier-point-count instrument-count)]
      (assoc objective :frontier-points point-count)
      objective)))

(defn build-plans
  [{:keys [request instrument-ids expected-returns covariance solver-plan return-tilts]}]
  (let [display-objective (display-frontier-objective (:objective request)
                                                      (count instrument-ids))
        unconstrained-display-encoded (display-encoded-constraints request
                                                                   instrument-ids
                                                                   :unconstrained)
        constrained-display-encoded (display-encoded-constraints request
                                                                 instrument-ids
                                                                 :constrained)
        constrained-frontier-alias? (equivalent-frontier-constraints?
                                     unconstrained-display-encoded
                                     constrained-display-encoded)]
    {:plans
     (when (= :ok (:status solver-plan))
       (into {}
             (map (fn [constraint-mode]
                    (let [aliased? (and (= :constrained constraint-mode)
                                        constrained-frontier-alias?)]
                      [constraint-mode
                       (when-not aliased?
                         (objectives/build-display-frontier-plan
                          {:objective display-objective
                           :instrument-ids instrument-ids
                           :expected-returns expected-returns
                           :covariance covariance
                           :encoded-constraints (case constraint-mode
                                                  :unconstrained
                                                  unconstrained-display-encoded

                                                  constrained-display-encoded)
                           :return-tilts return-tilts}))])))
             [:unconstrained :constrained]))
     :aliases (cond-> {}
                constrained-frontier-alias?
                (assoc :constrained :unconstrained))}))

(defn- display-frontier-warning
  [display-frontier-plan solved-point-count]
  (when (and display-frontier-plan
             (< solved-point-count (count (:problems display-frontier-plan))))
    {:code :display-frontier-unavailable
     :requested-points (count (:problems display-frontier-plan))
     :available-points solved-point-count
     :message "Display frontier sweep did not produce a complete chart frontier."}))

(defn- selection
  [{:keys [request
           expected-returns
           covariance
           target-frontier
           display-frontier-plan
           display-frontier-results
           constraint-mode
           solved-points-fn]}]
  (let [points (solved-points-fn request
                                 (or display-frontier-results [])
                                 expected-returns
                                 covariance)
        frontier-points (frontier/efficient-frontier points)
        warning (display-frontier-warning display-frontier-plan (count points))]
    (if (seq frontier-points)
      {:frontier frontier-points
       :frontier-summary {:source :display-sweep
                          :constraint-mode constraint-mode
                          :point-count (count frontier-points)}
       :warnings (cond-> []
                   warning (conj warning))}
      {:frontier target-frontier
       :frontier-summary {:source :target-solve
                          :constraint-mode constraint-mode
                          :point-count (count target-frontier)}
       :warnings (cond-> []
                   display-frontier-plan
                   (conj (or warning
                             {:code :display-frontier-unavailable
                              :requested-points (count (:problems display-frontier-plan))
                              :available-points 0
                              :message "Display frontier sweep was unavailable."})))})))

(defn selections
  [{:keys [request
           risk-result
           expected-returns
           display-frontier-plans
           display-frontier-aliases
           target-frontier
           display-frontier-results
           solved-points-fn]}]
  (let [selections-by-mode
        (into {}
              (map (fn [constraint-mode]
                     [constraint-mode
                      (selection
                       {:request request
                        :expected-returns expected-returns
                        :covariance (:covariance risk-result)
                        :target-frontier target-frontier
                        :display-frontier-plan (get display-frontier-plans constraint-mode)
                        :display-frontier-results (get display-frontier-results constraint-mode)
                        :constraint-mode constraint-mode
                        :solved-points-fn solved-points-fn})]))
              [:unconstrained :constrained])]
    (reduce (fn [acc [constraint-mode source-mode]]
              (assoc acc constraint-mode
                     (assoc (get acc source-mode)
                            :frontier-summary
                            (assoc (:frontier-summary (get acc source-mode))
                                   :constraint-mode constraint-mode))))
            selections-by-mode
            display-frontier-aliases)))
