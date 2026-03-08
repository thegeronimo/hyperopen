(ns hyperopen.trade.layout-actions
  (:require [clojure.string :as str]))

(def default-mobile-surface
  :chart)

(def ^:private mobile-surface-options
  #{:chart :orderbook :ticket :account})

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
