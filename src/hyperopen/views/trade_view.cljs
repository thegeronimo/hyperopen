(ns hyperopen.views.trade-view
  (:require [hyperopen.trade.layout-actions :as trade-layout-actions]
            [hyperopen.views.active-asset-view :as active-asset-view]
            [hyperopen.views.l2-orderbook-view :as l2-orderbook-view]
            [hyperopen.views.trading-chart.core :as trading-chart]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.account-equity-view :as account-equity-view]
            [hyperopen.views.trade.order-form-view :as order-form-view]))

(def ^:private trade-mobile-surfaces
  [[:chart "Chart"]
   [:orderbook "Order Book"]
   [:ticket "Trade"]])

(defn- mobile-surface-button
  [selected-surface [surface-id label]]
  [:button {:type "button"
            :class (into ["flex-1"
                          "border-b-2"
                          "px-2"
                          "py-2"
                          "text-sm"
                          "font-medium"
                          "transition-colors"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"]
                         (if (= selected-surface surface-id)
                           ["border-primary" "text-trading-text"]
                           ["border-transparent" "text-trading-text-secondary" "hover:text-trading-text"]))
            :on {:click [[:actions/select-trade-mobile-surface surface-id]]}}
   label])

(defn trade-view [state]
  (let [active-asset (:active-asset state)
        orderbook-data (when active-asset (get-in state [:orderbooks active-asset]))
        mobile-surface (trade-layout-actions/normalize-trade-mobile-surface
                         (get-in state [:trade-ui :mobile-surface]))
        mobile-market-surface? (contains? #{:chart :orderbook} mobile-surface)
        show-surface-freshness-cues?
        (boolean (get-in state [:websocket-ui :show-surface-freshness-cues?] false))
        websocket-health (get-in state [:websocket :health])
        state* (assoc state :websocket-health websocket-health)]
    [:div {:class ["flex-1" "flex" "flex-col" "min-h-0"]
           :data-parity-id "trade-root"}
     [:div {:class ["w-full" "h-full" "px-0" "py-0" "space-y-0" "flex" "flex-col" "min-h-0"]}
      [:div {:class ["lg:hidden" "border-b" "border-base-300" "bg-base-200"]}
       (active-asset-view/active-asset-view state*)]
      [:div {:class ["lg:hidden" "border-b" "border-base-300" "bg-base-200/70" "px-3"]}
       [:div {:class ["flex" "items-center" "gap-0"]}
        (for [[surface-id _label :as surface] trade-mobile-surfaces]
          ^{:key (str "trade-mobile-surface-" (name surface-id))}
          (mobile-surface-button mobile-surface surface))]]
      [:div {:class ["relative" "flex-1" "min-h-0"]}
       [:div {:class ["hidden" "xl:block" "absolute" "top-0" "bottom-0" "right-[320px]" "w-px" "bg-base-300" "pointer-events-none" "z-10"]}]
        [:div {:class ["grid"
                       "h-auto"
                       "min-h-0"
                       "grid-cols-1"
                       "gap-x-0" "gap-y-0"
                       "bg-base-100"
                       "items-stretch"
                       "lg:h-full"
                       "lg:grid-cols-[minmax(0,1fr)_320px]"
                       "lg:grid-rows-[minmax(520px,1fr)_minmax(300px,auto)]"
                       "xl:grid-cols-[minmax(0,1fr)_280px_320px]"
                        "xl:grid-rows-[minmax(580px,1fr)_auto]"]}
        [:div {:class (into [(if (= mobile-surface :chart) "flex" "hidden")
                             "bg-base-100"
                             "flex-col"
                             "min-h-0"]
                            ["lg:flex"
                             "lg:row-start-1"
                             "lg:col-start-1"
                             "lg:border-r"
                             "lg:border-base-300"])
               :data-parity-id "trade-chart-panel"}
         [:div {:class ["hidden" "lg:block"]}
          (active-asset-view/active-asset-view state*)]
         [:div {:class ["overflow-hidden" "flex-1" "min-h-0"]}
          (trading-chart/trading-chart-view state*)]]

        [:div {:class (into [(if (= mobile-surface :orderbook) "block" "hidden")
                             "bg-base-100"
                             "w-full"
                             "h-auto"
                             "min-h-[360px]"
                             "overflow-hidden"]
                            ["lg:block"
                             "lg:h-full"
                             "lg:min-h-0"
                             "lg:col-start-2"
                             "lg:row-start-2"
                             "lg:border-l"
                             "lg:border-t"
                             "lg:border-base-300"
                             "xl:col-start-2"
                             "xl:row-span-2"
                             "xl:border-t-0"])
               :data-parity-id "trade-orderbook-panel"}
         (l2-orderbook-view/l2-orderbook-view
           {:coin (or active-asset "No Asset Selected")
            :market (:active-market state)
            :orderbook orderbook-data
            :orderbook-ui (:orderbook-ui state)
            :show-surface-freshness-cues? show-surface-freshness-cues?
            :websocket-health websocket-health
            :loading (and active-asset (nil? orderbook-data))})]

        [:div {:class (into [(if (= mobile-surface :ticket) "flex" "hidden")
                             "bg-base-100"
                             "overflow-visible"
                             "flex-col"
                             "min-h-0"]
                            ["lg:flex"
                             "lg:col-start-2"
                             "lg:row-start-1"
                             "lg:border-l"
                             "lg:border-base-300"
                             "xl:col-start-3"
                             "xl:row-span-2"])
               :data-parity-id "trade-order-entry-panel"}
         (order-form-view/order-form-view state*)
         [:div {:class ["border-t" "border-base-300"]}
          (account-equity-view/account-equity-view state*)]]

        [:div {:class (into [(if (or (= mobile-surface :account)
                                     mobile-market-surface?)
                               "flex"
                               "hidden")
                             "bg-base-100"
                             "border-t"
                             "border-base-300"
                             "flex-col"
                             "min-h-0"
                             "overflow-hidden"]
                            ["lg:flex"
                             "lg:col-start-1"
                             "lg:row-start-2"
                             "xl:col-start-1"])
               :data-parity-id "trade-account-tables-panel"}
         (account-info-view/account-info-view state*)]]]]]))
