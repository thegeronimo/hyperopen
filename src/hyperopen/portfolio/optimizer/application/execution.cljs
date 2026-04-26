(ns hyperopen.portfolio.optimizer.application.execution
  (:require [clojure.string :as str]
            [hyperopen.api.gateway.orders.commands :as order-commands]
            [hyperopen.asset-selector.markets :as markets]))

(defn- finite-positive?
  [value]
  (and (number? value)
       (pos? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- parse-int-value
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseInt value 10)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num)))
      (js/Math.floor num))))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- ready-perp-row?
  [row]
  (and (= :ready (:status row))
       (= :perp (:instrument-type row))
       (finite-positive? (:quantity row))
       (not= :none (:side row))
       (not (zero? (or (:delta-notional-usd row) 0)))))

(defn- intent-for-row
  [execution-assumptions row]
  {:kind :perp-order
   :instrument-id (:instrument-id row)
   :side (:side row)
   :quantity (:quantity row)
   :order-type (or (:default-order-type execution-assumptions) :market)
   :reduce-only? false})

(defn- execution-row
  [execution-assumptions row]
  (let [instrument-id (:instrument-id row)
        base-row (cond-> {:row-id instrument-id
                          :instrument-id instrument-id
                          :instrument-type (:instrument-type row)
                          :side (:side row)
                          :quantity (:quantity row)
                          :order-type (or (:default-order-type execution-assumptions) :market)
                          :delta-notional-usd (:delta-notional-usd row)
                          :cost (:cost row)}
                   (some? (:coin row)) (assoc :coin (:coin row))
                   (some? (:price row)) (assoc :price (:price row)))]
    (cond
      (ready-perp-row? row)
      (assoc base-row
             :status :ready
             :intent (intent-for-row execution-assumptions row))

      (and (= :ready (:status row))
           (not (finite-positive? (:quantity row))))
      (assoc base-row
             :status :blocked
             :reason :quantity-below-lot)

      (and (= :ready (:status row))
           (or (= :none (:side row))
               (zero? (or (:delta-notional-usd row) 0))))
      (assoc base-row
             :status :blocked
             :reason :zero-delta-notional)

      (= :within-tolerance (:status row))
      (assoc base-row
             :status :skipped
             :reason :within-tolerance)

      (= :blocked (:status row))
      (assoc base-row
             :status :blocked
             :reason (:reason row))

      (= :ready (:status row))
      (assoc base-row
             :status :blocked
             :reason :unsupported-market-type)

      :else
      (assoc base-row
             :status :blocked
             :reason :unsupported-row-status))))

(defn- plan-status
  [ready-count blocked-count]
  (cond
    (and (pos? ready-count)
         (pos? blocked-count)) :partially-blocked
    (pos? ready-count) :ready
    (pos? blocked-count) :blocked
    :else :no-op))

(defn build-execution-plan
  [{:keys [scenario-id
           rebalance-preview
           execution-assumptions
           mutations-blocked-message]}]
  (let [rows (mapv #(execution-row execution-assumptions %)
                   (:rows rebalance-preview))
        ready-rows (filter #(= :ready (:status %)) rows)
        blocked-rows (filter #(= :blocked (:status %)) rows)
        skipped-rows (filter #(= :skipped (:status %)) rows)
        disabled-message (non-blank-text mutations-blocked-message)]
    {:scenario-id scenario-id
     :status (plan-status (count ready-rows) (count blocked-rows))
     :execution-disabled? (boolean disabled-message)
     :disabled-reason (when disabled-message :read-only)
     :disabled-message disabled-message
     :summary {:ready-count (count ready-rows)
               :blocked-count (count blocked-rows)
               :skipped-count (count skipped-rows)
               :gross-ready-notional-usd
               (reduce + 0 (map #(js/Math.abs (:delta-notional-usd %))
                                ready-rows))
               :estimated-fees-usd (get-in rebalance-preview
                                            [:summary :estimated-fees-usd])
               :estimated-slippage-usd (get-in rebalance-preview
                                                [:summary :estimated-slippage-usd])
               :margin (get-in rebalance-preview [:summary :margin])}
     :rows rows}))

(defn- coin-for-row
  [row]
  (or (non-blank-text (:coin row))
      (let [instrument-id (non-blank-text (:instrument-id row))]
        (cond
          (str/starts-with? instrument-id "perp:")
          (subs instrument-id 5)

          (str/starts-with? instrument-id "spot:")
          (subs instrument-id 5)

          :else instrument-id))))

(defn- row-market
  [market-by-key row]
  (let [instrument-id (:instrument-id row)
        coin (coin-for-row row)]
    (or (get market-by-key instrument-id)
        (markets/resolve-or-infer-market-by-coin market-by-key coin))))

(defn- market-asset-idx
  [market]
  (some parse-int-value [(:asset-id market)
                         (:assetId market)
                         (:idx market)]))

(defn- order-form-for-row
  [row]
  (let [intent (:intent row)]
    {:type (or (:order-type intent) :market)
     :side (:side intent)
     :size (:quantity intent)
     :price (:price row)
     :reduce-only (boolean (:reduce-only? intent))
     :margin-mode :cross}))

(defn- order-request-for-row
  [{:keys [market-by-key orderbooks]} row]
  (let [market (row-market market-by-key row)
        coin (coin-for-row row)
        asset-idx (market-asset-idx market)]
    (cond
      (nil? market)
      {:blocked-reason :market-metadata-missing}

      (nil? asset-idx)
      {:blocked-reason :market-metadata-missing}

      (not (finite-positive? (:price row)))
      {:blocked-reason :missing-price}

      :else
      (let [command-context {:active-asset coin
                             :asset-idx asset-idx
                             :market market
                             :orderbook (get orderbooks coin)}
            request (order-commands/build-order-request command-context
                                                        (order-form-for-row row))]
        (if (map? request)
          {:request request}
          {:blocked-reason :request-unavailable})))))

(defn- attempt-row
  [opts row]
  (if (= :ready (:status row))
    (let [{:keys [request blocked-reason]} (order-request-for-row opts row)]
      (if (map? request)
        (assoc row :request request)
        (-> row
            (assoc :status :blocked
                   :reason blocked-reason)
            (dissoc :intent))))
    row))

(defn build-execution-attempt
  [{:keys [plan market-by-key orderbooks]}]
  (let [rows (mapv #(attempt-row {:market-by-key (or market-by-key {})
                                  :orderbooks (or orderbooks {})}
                                 %)
                   (:rows plan))
        ready-count (count (filter #(= :ready (:status %)) rows))
        blocked-count (count (filter #(= :blocked (:status %)) rows))
        skipped-count (count (filter #(= :skipped (:status %)) rows))]
    (assoc plan
           :status (plan-status ready-count blocked-count)
           :summary (assoc (:summary plan)
                           :ready-count ready-count
                           :blocked-count blocked-count
                           :skipped-count skipped-count)
           :rows rows)))

(defn response-ok?
  [resp]
  (let [top-level-ok? (= "ok" (:status resp))
        statuses (let [statuses (get-in resp [:response :data :statuses])
                       status (get-in resp [:response :data :status])]
                   (cond
                     (sequential? statuses) statuses
                     (some? status) [status]
                     :else []))]
    (and top-level-ok?
         (not-any? #(and (map? %)
                         (contains? % :error))
                   statuses))))

(defn final-ledger-status
  [rows]
  (let [submitted-count (count (filter #(= :submitted (:status %)) rows))
        failed-count (count (filter #(= :failed (:status %)) rows))
        blocked-count (count (filter #(= :blocked (:status %)) rows))]
    (cond
      (and (pos? submitted-count)
           (zero? failed-count)
           (zero? blocked-count)) :executed
      (pos? submitted-count) :partially-executed
      (pos? failed-count) :failed
      (pos? blocked-count) :blocked
      :else :no-op)))
