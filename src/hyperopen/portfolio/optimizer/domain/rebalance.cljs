(ns hyperopen.portfolio.optimizer.domain.rebalance)

(def default-fallback-slippage-bps
  25)

(defn- abs-num
  [value]
  (js/Math.abs value))

(defn- side-for
  [delta-notional]
  (cond
    (pos? delta-notional) :buy
    (neg? delta-notional) :sell
    :else :none))

(defn- finite-positive?
  [value]
  (and (number? value)
       (pos? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- cost-context
  [opts instrument-id]
  (let [fallback (or (:fallback-slippage-bps opts)
                     default-fallback-slippage-bps)
        context (get-in opts [:cost-contexts-by-id instrument-id])]
    {:source (or (:source context) :fallback-bps)
     :slippage-bps (or (:slippage-bps context) fallback)}))

(defn- cost-estimate
  [opts instrument-id delta-notional-usd]
  (let [{:keys [source slippage-bps]} (cost-context opts instrument-id)
        fee-bps (or (get-in opts [:fee-bps-by-id instrument-id]) 0)
        notional (abs-num delta-notional-usd)]
    {:source source
     :slippage-bps slippage-bps
     :estimated-slippage-usd (* notional (/ slippage-bps 10000))
     :fee-bps fee-bps
     :estimated-fee-usd (* notional (/ fee-bps 10000))}))

(defn- row-status
  [{:keys [rebalance-tolerance]} instrument price delta-weight]
  (cond
    (<= (abs-num delta-weight) (or rebalance-tolerance 0))
    {:status :within-tolerance}

    (= :spot (:instrument-type instrument))
    {:status :blocked
     :reason :spot-read-only}

    (not (finite-positive? price))
    {:status :blocked
     :reason :missing-price}

    :else
    {:status :ready}))

(defn- rebalance-row
  [opts instrument-id current-weight target-weight]
  (let [instrument (get-in opts [:instruments-by-id instrument-id])
        price (get-in opts [:prices-by-id instrument-id])
        capital-usd (or (:capital-usd opts) 0)
        delta-weight (- target-weight current-weight)
        delta-notional-usd (* capital-usd delta-weight)
        status (row-status opts instrument price delta-weight)
        quantity (when (and (= :ready (:status status))
                            (finite-positive? price))
                   (/ (abs-num delta-notional-usd) price))]
    (merge {:instrument-id instrument-id
            :instrument-type (:instrument-type instrument)
            :coin (:coin instrument)
            :current-weight current-weight
            :target-weight target-weight
            :delta-weight delta-weight
            :delta-notional-usd delta-notional-usd
            :side (side-for delta-notional-usd)
            :price price
            :quantity quantity}
           status
           (when (= :ready (:status status))
             {:cost (cost-estimate opts instrument-id delta-notional-usd)}))))

(defn- preview-status
  [rows]
  (let [ready? (boolean (some #(= :ready (:status %)) rows))
        blocked? (boolean (some #(= :blocked (:status %)) rows))]
    (cond
      (and ready? blocked?) :partially-blocked
      blocked? :blocked
      ready? :ready
      :else :no-op)))

(defn build-rebalance-preview
  [{:keys [instrument-ids current-weights target-weights] :as opts}]
  (let [rows (mapv (fn [instrument-id current-weight target-weight]
                     (rebalance-row opts instrument-id current-weight target-weight))
                   instrument-ids
                   current-weights
                   target-weights)
        trade-rows (remove #(= :within-tolerance (:status %)) rows)]
    {:status (preview-status rows)
     :capital-usd (:capital-usd opts)
     :rows rows
     :summary {:ready-count (count (filter #(= :ready (:status %)) rows))
               :blocked-count (count (filter #(= :blocked (:status %)) rows))
               :within-tolerance-count (count (filter #(= :within-tolerance (:status %)) rows))
               :gross-trade-notional-usd (reduce + 0 (map #(abs-num (:delta-notional-usd %)) trade-rows))
               :estimated-fees-usd (reduce + 0 (map #(or (get-in % [:cost :estimated-fee-usd]) 0) trade-rows))
               :estimated-slippage-usd (reduce + 0 (map #(or (get-in % [:cost :estimated-slippage-usd]) 0) trade-rows))}}))
