(ns hyperopen.orderbook.actions
  (:require [hyperopen.orderbook.settings :as orderbook-settings]))

(defn toggle-orderbook-size-unit-dropdown
  [state]
  (let [visible? (get-in state [:orderbook-ui :size-unit-dropdown-visible?] false)]
    [[:effects/save [:orderbook-ui :size-unit-dropdown-visible?] (not visible?)]]))

(def select-orderbook-size-unit
  orderbook-settings/select-orderbook-size-unit)

(defn toggle-orderbook-price-aggregation-dropdown
  [state]
  (let [visible? (get-in state [:orderbook-ui :price-aggregation-dropdown-visible?] false)]
    [[:effects/save [:orderbook-ui :price-aggregation-dropdown-visible?] (not visible?)]]))

(def select-orderbook-price-aggregation
  orderbook-settings/select-orderbook-price-aggregation)

(def select-orderbook-tab
  orderbook-settings/select-orderbook-tab)
