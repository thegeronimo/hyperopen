(ns hyperopen.views.trade-view
  (:require [hyperopen.trade.layout-actions :as trade-layout-actions]
            [hyperopen.trade-modules :as trade-modules]
            [hyperopen.views.active-asset-view :as active-asset-view]
            [hyperopen.views.ui.funding-modal-positioning :as funding-modal-positioning]
            [hyperopen.views.l2-orderbook-view :as l2-orderbook-view]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.account-equity-view :as account-equity-view]
            [hyperopen.views.trade.order-form-view :as order-form-view]))

(def ^:private trade-mobile-surfaces
  [[:chart "Chart"]
   [:orderbook "Order Book"]
   [:trades "Trades"]])

(defn- mobile-surface-button
  [selected-surface [surface-id label]]
  [:button {:type "button"
            :data-role (str "trade-mobile-surface-button-" (name surface-id))
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

(defn- mobile-orderbook-view-state
  [orderbook-view-state mobile-surface]
  (assoc orderbook-view-state
         :show-tabs? false
         :active-tab-override (if (= mobile-surface :trades)
                                :trades
                                :orderbook)))

(defn- mobile-account-surface [state equity-metrics]
  [:div {:class ["flex" "h-full" "min-h-0" "flex-col" "bg-base-100"]
         :data-parity-id "trade-mobile-account-surface"}
  (account-equity-view/account-equity-view state {:fill-height? false
                                                   :show-funding-actions? false
                                                   :metrics equity-metrics})
   (account-equity-view/funding-actions-view
    state
    {:container-classes ["mt-auto"
                         "border-t"
                         "border-base-300"
                         "bg-base-100"
                         "px-3"
                         "pt-2"
                         "pb-1.5"
                         "space-y-2"]
     :data-parity-id "trade-mobile-account-actions"})])

(defn- trade-chart-loading-shell
  [state]
  (let [error-message (trade-modules/trade-chart-error state)
        route (get-in state [:router :path] "/trade")]
    [:div {:class ["flex"
                   "h-full"
                   "min-h-0"
                   "items-center"
                   "justify-center"
                   "bg-base-100"
                   "px-6"
                   "py-10"]
           :data-parity-id "trade-chart-module-shell"}
     [:div {:class ["flex"
                    "max-w-md"
                    "flex-col"
                    "items-center"
                    "gap-3"
                    "text-center"]}
      [:div {:class ["text-sm"
                     "font-semibold"
                     "uppercase"
                     "tracking-[0.12em]"
                     "text-trading-text-secondary"]}
       (if error-message
         "Chart Load Failed"
         "Loading Chart")]
      [:p {:class ["text-sm" "text-trading-text-secondary"]}
       (or error-message
           "Loading the trade chart on demand to keep the initial trade bundle smaller.")]
      (when error-message
        [:button {:type "button"
                  :class ["rounded-lg"
                          "border"
                          "border-base-300"
                          "px-3"
                          "py-2"
                          "text-sm"
                          "font-medium"
                          "text-trading-text"
                          "transition-colors"
                          "hover:border-primary"
                          "hover:text-primary"]
                  :on {:click [[:actions/navigate route {:replace? true}]]}}
         "Retry"])]]))

(defn- trade-chart-panel-content
  [state]
  (or (trade-modules/render-trade-chart-view state)
      (trade-chart-loading-shell state)))

(defn trade-view [state]
  (let [active-asset (:active-asset state)
        orderbook-data (when active-asset (get-in state [:orderbooks active-asset]))
        mobile-surface (trade-layout-actions/normalize-trade-mobile-surface
                         (get-in state [:trade-ui :mobile-surface]))
        mobile-market-surface? (contains? trade-layout-actions/market-mobile-surfaces
                                          mobile-surface)
        mobile-account-surface? (= mobile-surface :account)
        mobile-orderbook-surface? (contains? #{:orderbook :trades} mobile-surface)
        show-surface-freshness-cues?
        (boolean (get-in state [:websocket-ui :show-surface-freshness-cues?] false))
        websocket-health (get-in state [:websocket :health])
        state* (assoc state :websocket-health websocket-health)
        equity-metrics (account-equity-view/account-equity-metrics state)
        orderbook-view-state {:coin (or active-asset "No Asset Selected")
                              :market (:active-market state)
                              :orderbook orderbook-data
                              :orderbook-ui (:orderbook-ui state)
                              :show-surface-freshness-cues? show-surface-freshness-cues?
                              :websocket-health websocket-health
                              :loading (and active-asset (nil? orderbook-data))}]
    [:div {:class ["flex-1" "flex" "flex-col" "min-h-0" "scrollbar-hide" "xl:overflow-y-auto"]
           :data-parity-id "trade-root"}
     [:div {:class ["w-full" "h-full" "px-0" "py-0" "space-y-0" "flex" "flex-col" "min-h-0"]}
      [:div {:class (into ["lg:hidden" "border-b" "border-base-300" "bg-base-200"]
                          (when mobile-account-surface?
                            ["hidden"]))
             :data-parity-id "trade-mobile-active-asset-strip"}
       (active-asset-view/active-asset-view state*)]
      [:div {:class (into ["lg:hidden" "border-b" "border-base-300" "bg-base-200/70" "px-3"]
                          (when mobile-account-surface?
                            ["hidden"]))
             :data-parity-id "trade-mobile-surface-tabs"}
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
                      "xl:min-h-[964px]"
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
          (trade-chart-panel-content state*)]]

        [:div {:class (into [(if mobile-orderbook-surface? "block" "hidden")
                             "bg-base-100"
                             "w-full"
                             "h-[320px]"
                             "min-h-[320px]"
                             "overflow-hidden"]
                            ["sm:h-[360px]"
                             "sm:min-h-[360px]"
                             "lg:block"
                             "lg:h-full"
                             "lg:min-h-0"
                             "lg:col-start-2"
                             "lg:row-start-2"
                             "lg:border-l"
                             "lg:border-t"
                             "lg:border-base-300"
                             "xl:col-start-2"
                             "xl:row-start-1"
                             "xl:border-t-0"])
               :data-parity-id "trade-orderbook-panel"}
         [:div {:class ["h-full" "min-h-0" "lg:hidden"]}
          (l2-orderbook-view/l2-orderbook-view
           (mobile-orderbook-view-state orderbook-view-state mobile-surface))]
         [:div {:class ["hidden" "h-full" "min-h-0" "lg:block"]}
          (l2-orderbook-view/l2-orderbook-view orderbook-view-state)]]

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
               :data-parity-id funding-modal-positioning/trade-order-entry-panel-parity-id}
         (order-form-view/order-form-view state*)
         [:div {:class ["hidden" "border-t" "border-base-300" "lg:block"]
                :data-parity-id "trade-desktop-account-equity-panel"}
          (account-equity-view/account-equity-view state* {:metrics equity-metrics})]]

        [:div {:class (into [(if (= mobile-surface :account)
                               "hidden"
                               (if mobile-market-surface? "flex" "hidden"))
                             "bg-base-100"
                             "border-t"
                             "border-base-300"
                             "flex-col"
                             "min-h-0"
                             "overflow-hidden"]
                            ["lg:flex"
                             "lg:col-start-1"
                             "lg:row-start-2"
                             "xl:col-start-1"
                             "xl:col-span-2"])
               :data-parity-id "trade-account-tables-panel"}
         [:div {:class ["w-full" "lg:hidden"]
                :data-parity-id "trade-mobile-account-panel"}
          (account-info-view/account-info-view state*)]
         [:div {:class ["hidden" "w-full" "min-h-0" "lg:flex"]
                :data-parity-id "trade-desktop-account-panel"}
          (account-info-view/account-info-view state*)]]]

       (when mobile-account-surface?
         [:div {:class ["absolute"
                        "inset-0"
                        "z-20"
                        "bg-base-100"
                        "border-t"
                        "border-base-300"
                        "flex"
                        "flex-col"
                        "min-h-0"
                        "lg:hidden"]
                :data-parity-id "trade-mobile-account-summary-panel"}
          (mobile-account-surface state* equity-metrics)])]]]))
