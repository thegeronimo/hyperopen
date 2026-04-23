(ns hyperopen.portfolio.optimizer.domain.constraints)

(def default-max-asset-weight
  1)

(defn- instrument-id
  [instrument]
  (:instrument-id instrument))

(defn normalize-universe
  [universe constraints]
  (let [allowlist (:allowlist constraints)
        blocklist (or (:blocklist constraints) #{})]
    (->> universe
         (filter (fn [instrument]
                   (let [id (instrument-id instrument)]
                     (and (or (nil? allowlist)
                              (contains? allowlist id))
                          (not (contains? blocklist id))))))
         vec)))

(defn- max-weight-for
  [constraints instrument-id]
  (min (or (:max-asset-weight constraints) default-max-asset-weight)
       (or (get-in constraints [:per-asset-overrides instrument-id :max-weight])
           default-max-asset-weight)
       (or (get-in constraints [:per-perp-leverage-caps instrument-id :max-weight])
           default-max-asset-weight)))

(defn- locked?
  [constraints instrument-id]
  (contains? (or (:held-position-locks constraints) #{}) instrument-id))

(defn- current-weight
  [current-weights instrument-id]
  (or (get current-weights instrument-id) 0))

(defn- bounds-for
  [constraints current-weights instrument]
  (let [id (instrument-id instrument)
        max-weight (max-weight-for constraints id)]
    (if (locked? constraints id)
      (let [weight (current-weight current-weights id)]
        {:lower weight
         :upper weight
         :locked {:instrument-id id
                  :weight weight}})
      (if (:long-only? constraints)
        {:lower 0
         :upper max-weight}
        {:lower (- max-weight)
         :upper max-weight}))))

(defn- target-net
  [constraints]
  (if (:long-only? constraints)
    1
    nil))

(defn- violations
  [lower-bounds upper-bounds constraints]
  (let [target-net* (target-net constraints)
        sum-lower (reduce + 0 lower-bounds)
        sum-upper (reduce + 0 upper-bounds)]
    (vec (concat
          (when (and (number? target-net*)
                     (> sum-lower target-net*))
            [{:code :sum-lower-above-target
              :sum-lower sum-lower
              :target-net target-net*}])
          (when (and (number? target-net*)
                     (< sum-upper target-net*))
            [{:code :sum-upper-below-target
              :sum-upper sum-upper
              :target-net target-net*}])))))

(defn encode-constraints
  [{:keys [universe current-weights constraints]}]
  (let [constraints* (merge {:long-only? true}
                            (or constraints {}))
        universe* (normalize-universe (or universe []) constraints*)
        ids (mapv instrument-id universe*)
        bounds (mapv (partial bounds-for constraints* (or current-weights {}))
                     universe*)
        lower-bounds (mapv :lower bounds)
        upper-bounds (mapv :upper bounds)
        violations* (violations lower-bounds upper-bounds constraints*)]
    {:status (if (seq violations*) :infeasible :ok)
     :instrument-ids ids
     :lower-bounds lower-bounds
     :upper-bounds upper-bounds
     :locked-weights (vec (keep :locked bounds))
     :gross-exposure {:max (:gross-leverage constraints*)}
     :net-exposure (:net-exposure constraints*)
     :max-turnover (:max-turnover constraints*)
     :rebalance-tolerance (:rebalance-tolerance constraints*)
     :violations violations*}))
