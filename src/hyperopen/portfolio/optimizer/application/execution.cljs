(ns hyperopen.portfolio.optimizer.application.execution
  (:require [clojure.string :as str]))

(defn- finite-positive?
  [value]
  (and (number? value)
       (pos? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- ready-perp-row?
  [row]
  (and (= :ready (:status row))
       (= :perp (:instrument-type row))
       (finite-positive? (:quantity row))))

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
        base-row {:row-id instrument-id
                  :instrument-id instrument-id
                  :instrument-type (:instrument-type row)
                  :side (:side row)
                  :quantity (:quantity row)
                  :delta-notional-usd (:delta-notional-usd row)
                  :cost (:cost row)}]
    (cond
      (ready-perp-row? row)
      (assoc base-row
             :status :ready
             :intent (intent-for-row execution-assumptions row))

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
                                ready-rows))}
     :rows rows}))
