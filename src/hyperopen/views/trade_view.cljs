(ns hyperopen.views.trade-view
  (:require [hyperopen.views.active-asset-view :as active-asset-view]
            [hyperopen.views.l2-orderbook-view :as l2-orderbook-view]
            [hyperopen.views.trading-chart.core :as trading-chart]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.trade.order-form-view :as order-form-view]))

(defn trade-view [state]
  (let [active-asset (:active-asset state)
        orderbook-data (when active-asset (get-in state [:orderbooks active-asset]))]
    [:div.flex-1.overflow-auto
     [:div.w-full
      (active-asset-view/active-asset-view state)]

     [:div.max-w-7xl.mx-auto.px-6.py-4.space-y-6
      [:div {:class ["grid" "grid-cols-1" "lg:grid-cols-3" "gap-6"]}
       [:div {:class "lg:col-span-2"}
        (trading-chart/trading-chart-view state)]
       [:div.space-y-6
        (order-form-view/order-form-view state)
        (l2-orderbook-view/l2-orderbook-view
          {:coin (or active-asset "No Asset Selected")
           :orderbook orderbook-data
           :loading (and active-asset (nil? orderbook-data))})]]

      (account-info-view/account-info-view state)]]))
