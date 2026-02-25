(ns hyperopen.views.trade-view
  (:require [hyperopen.views.active-asset-view :as active-asset-view]
            [hyperopen.views.l2-orderbook-view :as l2-orderbook-view]
            [hyperopen.views.trading-chart.core :as trading-chart]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.account-equity-view :as account-equity-view]
            [hyperopen.views.trade.order-form-view :as order-form-view]))

(defn trade-view [state]
  (let [active-asset (:active-asset state)
        orderbook-data (when active-asset (get-in state [:orderbooks active-asset]))
        show-surface-freshness-cues?
        (boolean (get-in state [:websocket-ui :show-surface-freshness-cues?] false))
        websocket-health (get-in state [:websocket :health])
        state* (assoc state :websocket-health websocket-health)]
    [:div {:class ["flex-1" "flex" "flex-col" "min-h-0"]
           :data-parity-id "trade-root"}
     [:div {:class ["w-full" "h-full" "px-0" "py-0" "space-y-0" "flex" "flex-col" "min-h-0"]}
      [:div {:class ["relative" "flex-1" "min-h-0"]}
       [:div {:class ["hidden" "xl:block" "absolute" "top-0" "bottom-0" "right-[280px]" "w-px" "bg-base-300" "pointer-events-none" "z-10"]}]
        [:div {:class ["grid"
                       "h-full"
                       "min-h-0"
                       "grid-cols-1"
                       "gap-x-0" "gap-y-0"
                       "bg-base-100"
                        "items-stretch"
                        "lg:grid-cols-[minmax(0,1fr)_280px]"
                        "lg:grid-rows-[minmax(580px,1fr)_auto_auto]"
                        "xl:grid-cols-[minmax(0,1fr)_280px_280px]"
                        "xl:grid-rows-[minmax(580px,1fr)_auto]"]}
        [:div {:class ["bg-base-100" "border-r" "border-base-300" "flex" "flex-col" "min-h-0"]
               :data-parity-id "trade-chart-panel"}
         (active-asset-view/active-asset-view state*)
         [:div {:class ["overflow-hidden" "flex-1" "min-h-0"]}
          (trading-chart/trading-chart-view state*)]]

        [:div {:class ["bg-base-100" "w-full" "h-full" "min-h-0" "overflow-hidden"]
               :data-parity-id "trade-orderbook-panel"}
         (l2-orderbook-view/l2-orderbook-view
           {:coin (or active-asset "No Asset Selected")
           :market (:active-market state)
            :orderbook orderbook-data
            :orderbook-ui (:orderbook-ui state)
            :show-surface-freshness-cues? show-surface-freshness-cues?
            :websocket-health websocket-health
            :loading (and active-asset (nil? orderbook-data))})]

        [:div {:class ["bg-base-100" "lg:col-span-2" "xl:col-span-1" "xl:col-start-3" "xl:row-span-2" "overflow-visible" "flex" "flex-col"]
               :data-parity-id "trade-order-entry-panel"}
         (order-form-view/order-form-view state*)
         [:div {:class ["border-t" "border-base-300"]}
          (account-equity-view/account-equity-view state*)]]

        [:div {:class ["bg-base-100" "lg:col-span-2" "xl:col-span-2" "border-t" "border-base-300" "flex" "flex-col" "min-h-0" "overflow-hidden"]
               :data-parity-id "trade-account-tables-panel"}
         (account-info-view/account-info-view state*)]]]]]))
