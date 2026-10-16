(ns hyperopen.trade.layout-actions
  (:require [clojure.string :as str]))

(def default-mobile-surface
  :chart)

(def market-mobile-surfaces
  #{:chart :orderbook :trades})

(def ^:private mobile-surface-options
  (into market-mobile-surfaces
        #{:ticket :account}))

(defn normalize-trade-mobile-surface
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (some-> value str/trim str/lower-case keyword)
                :else nil)]
    (if (contains? mobile-surface-options token)
      token
      default-mobile-surface)))

(defn select-trade-mobile-surface
  [_state surface]
  [[:effects/save [:trade-ui :mobile-surface]
    (normalize-trade-mobile-surface surface)]])

(defn toggle-market-info-panel
  [state]
  [[:effects/save [:trade-ui :market-info-open?]
    (not (true? (get-in state [:trade-ui :market-info-open?])))]])

(defn close-market-info-panel
  [_state]
  [[:effects/save [:trade-ui :market-info-open?] false]])

(defn toggle-trade-mobile-asset-details
  [state]
  [[:effects/save [:trade-ui :mobile-asset-details-open?]
    (not (true? (get-in state [:trade-ui :mobile-asset-details-open?])))]])
