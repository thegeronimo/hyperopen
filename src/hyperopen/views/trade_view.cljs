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
        top-row-height "600px"]
    [:div.flex-1.overflow-auto.flex.flex-col
     [:div {:class ["w-full" "px-0" "py-0" "space-y-0" "flex" "flex-col" "min-h-full"]}
      [:div {:class ["relative" "flex-1"]}
       [:div {:class ["hidden" "xl:block" "absolute" "top-0" "bottom-0" "right-[320px]" "w-px" "bg-base-300" "pointer-events-none" "z-10"]}]
       [:div {:class ["grid"
                      "grid-cols-1"
                      "gap-x-0" "gap-y-0"
                      "bg-base-100"
                      "items-stretch"
                      "lg:grid-cols-[minmax(0,1fr)_320px]"
                      "xl:grid-cols-[minmax(0,1fr)_320px_320px]"]}
        [:div {:class ["bg-base-100" "border-r" "border-base-300" "flex" "flex-col"]}
         (active-asset-view/active-asset-view state)
         [:div {:class ["overflow-hidden"]
                :style {:height top-row-height}}
          (trading-chart/trading-chart-view state)]]

        [:div {:class ["bg-base-100" "w-full" "h-full"]}
         (l2-orderbook-view/l2-orderbook-view
           {:coin (or active-asset "No Asset Selected")
            :orderbook orderbook-data
            :loading (and active-asset (nil? orderbook-data))})]

        [:div {:class ["bg-base-100" "lg:col-span-2" "xl:col-span-1" "xl:col-start-3" "h-full"]}
         (order-form-view/order-form-view state)]

        [:div {:class ["bg-base-100" "lg:col-span-2" "xl:col-span-2" "border-t" "border-base-300"]}
         (account-info-view/account-info-view state)]

        [:div {:class ["bg-base-100" "lg:col-span-2" "xl:col-start-3" "xl:row-start-2" "h-full" "border-t" "border-base-300"]}
         (account-equity-view/account-equity-view state)]]]]]))
