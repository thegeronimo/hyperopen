(ns hyperopen.portfolio.optimizer.domain.rebalance)

(def default-fallback-slippage-bps
  25)

(defn- abs-num
  [value]
  (js/Math.abs value))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- side-for
  [delta-value]
  (cond
    (pos? delta-value) :buy
    (neg? delta-value) :sell
    :else :none))

(defn- finite-positive?
  [value]
  (and (finite-number? value)
       (pos? value)
       true))

(defn- finite-nonzero?
  [value]
  (and (finite-number? value)
       (not (zero? value))))

(defn- size-decimals
  [instrument]
  (some (fn [k]
          (let [value (get instrument k)]
            (cond
              (and (integer? value) (not (neg? value))) value
              (and (number? value) (not (neg? value))) (js/Math.floor value)
              :else nil)))
        [:szDecimals :sz-decimals :size-decimals :quantity-decimals]))

(defn- min-quantity
  [instrument]
  (some (fn [k]
          (let [value (get instrument k)]
            (when (finite-positive? value)
              value)))
        [:min-size :minSize :minSz :min-quantity]))

(defn- round-down-quantity
  [quantity decimals]
  (if (and (finite-positive? quantity)
           (integer? decimals))
    (let [scale (js/Math.pow 10 decimals)]
      (/ (js/Math.floor (* quantity scale)) scale))
    quantity))

(defn- executable-quantity
  [instrument price delta-notional-usd]
  (when (and (finite-positive? price)
             (finite-nonzero? delta-notional-usd))
    (let [raw-quantity (/ (abs-num delta-notional-usd) price)
          rounded (round-down-quantity raw-quantity (size-decimals instrument))
          min-size (min-quantity instrument)]
      (if (and (finite-positive? min-size)
               (< rounded min-size))
        0
        rounded))))

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
  [{:keys [rebalance-tolerance]} instrument price capital-usd delta-weight delta-notional-usd quantity]
  (cond
    (<= (abs-num delta-weight) (or rebalance-tolerance 0))
    {:status :within-tolerance}

    (nil? instrument)
    {:status :blocked
     :reason :market-metadata-missing}

    (not (finite-positive? capital-usd))
    {:status :blocked
     :reason :missing-capital-base}

    (= :spot (:instrument-type instrument))
    {:status :blocked
     :reason :spot-submit-unsupported}

    (not (finite-positive? price))
    {:status :blocked
     :reason :missing-price}

    (not (finite-nonzero? delta-notional-usd))
    {:status :blocked
     :reason :zero-delta-notional}

    (not (finite-positive? quantity))
    {:status :blocked
     :reason :quantity-below-lot}

    :else
    {:status :ready}))

(defn- rebalance-row
  [opts instrument-id current-weight target-weight]
  (let [instrument (get-in opts [:instruments-by-id instrument-id])
        price (get-in opts [:prices-by-id instrument-id])
        capital-usd (or (:capital-usd opts) 0)
        delta-weight (- target-weight current-weight)
        delta-notional-usd (* capital-usd delta-weight)
        quantity (executable-quantity instrument price delta-notional-usd)
        status (row-status opts instrument price capital-usd delta-weight delta-notional-usd quantity)
        side (side-for (if (finite-nonzero? delta-notional-usd)
                         delta-notional-usd
                         delta-weight))]
    (merge {:instrument-id instrument-id
            :instrument-type (:instrument-type instrument)
            :coin (:coin instrument)
            :current-weight current-weight
            :target-weight target-weight
            :delta-weight delta-weight
            :delta-notional-usd delta-notional-usd
            :side side
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
        ready-rows (filter #(= :ready (:status %)) rows)]
    {:status (preview-status rows)
     :capital-usd (:capital-usd opts)
     :rows rows
     :summary {:ready-count (count (filter #(= :ready (:status %)) rows))
               :blocked-count (count (filter #(= :blocked (:status %)) rows))
               :within-tolerance-count (count (filter #(= :within-tolerance (:status %)) rows))
               :gross-trade-notional-usd (reduce + 0 (map #(abs-num (:delta-notional-usd %)) ready-rows))
               :estimated-fees-usd (reduce + 0 (map #(or (get-in % [:cost :estimated-fee-usd]) 0) ready-rows))
               :estimated-slippage-usd (reduce + 0 (map #(or (get-in % [:cost :estimated-slippage-usd]) 0) ready-rows))}}))
