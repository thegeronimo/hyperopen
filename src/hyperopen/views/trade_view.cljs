(ns hyperopen.views.trade-view
  (:require [hyperopen.trade.layout-actions :as trade-layout-actions]
            [hyperopen.trade-modules :as trade-modules]
            [hyperopen.views.active-asset.vm :as active-asset-vm]
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

(def ^:private desktop-breakpoint-px
  1024)

(def ^:private desktop-account-panel-height
  "29rem")

(def ^:private trade-chart-view-base-state-keys
  [:active-asset
   :active-market
   :account
   :asset-contexts
   :asset-selector
   :candles
   :chart-options
   :orders
   :perp-dex-clearinghouse
   :positions-ui
   :router
   :spot
   :trading-settings
   :trade-modules
   :webdata2])

(def ^:private account-info-view-base-state-keys
  [:account
   :account-info
   :asset-selector
   :orders
   :perp-dex-clearinghouse
   :positions-ui
   :spot
   :webdata2])

(def ^:private account-equity-view-state-keys
  [:account
   :asset-selector
   :perp-dex-clearinghouse
   :spot
   :webdata2])

(def ^:private order-form-view-state-keys
  [:account
   :active-asset
   :active-assets
   :active-market
   :asset-contexts
   :asset-selector
   :order-form
   :order-form-runtime
   :order-form-ui
   :orderbooks
   :perp-dex-clearinghouse
   :spot
   :wallet
   :webdata2])

(declare trade-chart-loading-shell)

(defn- memoize-last
  [f]
  (let [cache (atom nil)]
    (fn [& args]
      (let [cached @cache]
        (if (and (map? cached)
                 (= args (:args cached)))
          (:result cached)
          (let [result (apply f args)]
            (reset! cache {:args args
                           :result result})
            result))))))

(defn- select-view-state
  [state ks]
  (select-keys (or state {}) ks))

(defn- surface-freshness-cues-enabled?
  [state]
  (boolean (get-in state [:websocket-ui :show-surface-freshness-cues?] false)))

(defn- active-asset-view-state
  [state]
  (active-asset-vm/panel-dependency-state state))

(defn- trade-chart-view-state
  [state]
  (cond-> (select-view-state state trade-chart-view-base-state-keys)
    (surface-freshness-cues-enabled? state)
    (assoc :websocket (:websocket state)
           :websocket-ui (:websocket-ui state))))

(defn- account-info-view-state
  [state]
  (cond-> (select-view-state state account-info-view-base-state-keys)
    (surface-freshness-cues-enabled? state)
    (assoc :websocket (:websocket state)
           :websocket-ui (:websocket-ui state))))

(defn- account-equity-view-state
  [state]
  (select-view-state state account-equity-view-state-keys))

(defn- order-form-view-state
  [state]
  (select-view-state state order-form-view-state-keys))

(def ^:private memoized-active-asset-view
  (memoize-last (fn [render-fn state]
                  (render-fn state))))

(def ^:private memoized-trade-chart-panel-content
  (memoize-last (fn [render-fn state]
                  (or (render-fn state)
                      (trade-chart-loading-shell state)))))

(def ^:private memoized-account-info-view
  (memoize-last (fn [render-fn state opts]
                  (render-fn state opts))))

(def ^:private memoized-account-equity-view
  (memoize-last (fn [render-fn state opts]
                  (render-fn state opts))))

(def ^:private memoized-orderbook-view
  (memoize-last (fn [render-fn state]
                  (render-fn state))))

(def ^:private memoized-order-form-view
  (memoize-last (fn [render-fn state]
                  (render-fn state))))

(def ^:private memoized-account-equity-metrics
  (memoize-last (fn [metrics-fn state]
                  (metrics-fn state))))

(defn- viewport-width-px []
  (let [width (some-> js/globalThis .-innerWidth)]
    (if (number? width)
      width
      desktop-breakpoint-px)))

(defn- desktop-trade-layout? []
  (>= (viewport-width-px) desktop-breakpoint-px))

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

(defn- render-active-asset-panel
  [state]
  (memoized-active-asset-view active-asset-view/active-asset-view
                              (active-asset-view-state state)))

(defn- render-account-info-panel
  ([state]
   (render-account-info-panel state {}))
  ([state opts]
   (memoized-account-info-view account-info-view/account-info-view
                               (account-info-view-state state)
                               opts)))

(defn- render-account-equity-panel
  [state equity-metrics opts]
  (memoized-account-equity-view account-equity-view/account-equity-view
                                (account-equity-view-state state)
                                (assoc opts :metrics equity-metrics)))

(defn- render-account-equity-metrics
  [state]
  (memoized-account-equity-metrics account-equity-view/account-equity-metrics
                                   (account-equity-view-state state)))

(defn- render-orderbook-panel
  [state]
  (memoized-orderbook-view l2-orderbook-view/l2-orderbook-view
                           state))

(defn- render-order-form-panel
  [state]
  (memoized-order-form-view order-form-view/order-form-view
                            (order-form-view-state state)))

(defn- mobile-account-surface [state equity-metrics]
  [:div {:class ["flex" "h-full" "min-h-0" "flex-col" "bg-base-100"]
         :data-parity-id "trade-mobile-account-surface"}
  (render-account-equity-panel state equity-metrics {:fill-height? false
                                                     :show-funding-actions? false})
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
    [:div {:class ["w-full" "h-full" "min-h-0" "min-w-0" "overflow-hidden"]
           :data-parity-id "trade-chart-module-shell"}
     [:div {:class ["w-full" "h-full" "flex" "flex-col" "min-h-0" "min-w-0" "overflow-hidden"]}
      [:div {:class ["flex"
                     "items-center"
                     "justify-between"
                     "border-b"
                     "border-gray-700"
                     "px-4"
                     "pt-2"
                     "pb-1"
                     "w-full"
                     "space-x-4"
                     "bg-base-100"]}
       [:div {:class ["flex" "items-center" "space-x-1"]}
        (for [label ["5m" "1h" "1d"]]
          ^{:key (str "trade-chart-shell-timeframe-" label)}
          [:div {:class ["px-3"
                         "py-1"
                         "text-sm"
                         "font-medium"
                         "rounded"
                         "text-trading-text-secondary"
                         "bg-base-200/70"]}
           label])]
       [:div {:class ["ml-auto" "flex" "items-center" "gap-2"]}
        [:div {:class ["h-7" "w-28" "rounded" "bg-base-200/70"]}]
        [:div {:class ["h-7" "w-24" "rounded" "bg-base-200/70"]}]]]
      [:div {:class ["w-full"
                     "relative"
                     "flex-1"
                     "min-h-[360px]"
                     "min-w-0"
                     "bg-base-100"
                     "trading-chart-host"]}
       [:div {:class ["absolute"
                      "inset-0"
                      "flex"
                      "items-center"
                      "justify-center"
                      "px-6"
                      "py-10"]}
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
            "Retry"])]]]]]))

(defn- trade-chart-panel-content
  [state]
  (memoized-trade-chart-panel-content trade-modules/render-trade-chart-view
                                      (trade-chart-view-state state)))

(defn trade-view [state]
  (let [active-asset (:active-asset state)
        orderbook-data (when active-asset (get-in state [:orderbooks active-asset]))
        mobile-surface (trade-layout-actions/normalize-trade-mobile-surface
                         (get-in state [:trade-ui :mobile-surface]))
        desktop-layout? (desktop-trade-layout?)
        mobile-market-surface? (contains? trade-layout-actions/market-mobile-surfaces
                                          mobile-surface)
        mobile-account-surface? (= mobile-surface :account)
        mobile-orderbook-surface? (contains? #{:orderbook :trades} mobile-surface)
        chart-panel-visible? (or desktop-layout? (= mobile-surface :chart))
        orderbook-panel-visible? (or desktop-layout? mobile-orderbook-surface?)
        order-entry-panel-visible? (or desktop-layout? (= mobile-surface :ticket))
        account-panel-visible? (or desktop-layout? mobile-market-surface?)
        mobile-account-summary-visible? (and (not desktop-layout?)
                                             mobile-account-surface?)
        show-mobile-active-asset? (and (not desktop-layout?)
                                       (not mobile-account-surface?))
        show-equity-surface? (or desktop-layout?
                                 mobile-account-summary-visible?)
        show-surface-freshness-cues? (surface-freshness-cues-enabled? state)
        websocket-health (get-in state [:websocket :health])
        equity-metrics (when show-equity-surface?
                         (render-account-equity-metrics state))
        orderbook-view-state {:coin (or active-asset "No Asset Selected")
                              :market (:active-market state)
                              :orderbook orderbook-data
                              :orderbook-ui (:orderbook-ui state)
                              :trading-settings (:trading-settings state)
                              :show-surface-freshness-cues? show-surface-freshness-cues?
                              :websocket-health (when show-surface-freshness-cues?
                                                  websocket-health)
                              :loading (and active-asset (nil? orderbook-data))}]
    [:div {:class ["flex-1" "flex" "flex-col" "min-h-0" "overflow-hidden"]
           :data-parity-id "trade-root"}
     [:div {:class ["w-full"
                    "h-full"
                    "px-0"
                    "py-0"
                    "space-y-0"
                    "flex"
                    "flex-col"
                    "min-h-0"
                    "scrollbar-hide"
                    "overflow-y-auto"]
            :data-role "trade-scroll-shell"}
      [:div {:class (into ["lg:hidden" "border-b" "border-base-300" "bg-base-200"]
                          (when mobile-account-surface?
                            ["hidden"]))
             :data-parity-id "trade-mobile-active-asset-strip"}
       (when show-mobile-active-asset?
         (render-active-asset-panel state))]
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
                      "h-full"
                      "min-h-0"
                      "grid-cols-1"
                      "gap-x-0" "gap-y-0"
                      "bg-base-100"
                      "items-stretch"
                      "lg:h-full"
                      "lg:grid-cols-[minmax(0,1fr)_320px]"
                      (str "lg:grid-rows-[minmax(520px,1fr)_" desktop-account-panel-height "]")
                      "xl:grid-cols-[minmax(0,1fr)_280px_320px]"
                      (str "xl:grid-rows-[minmax(580px,1fr)_" desktop-account-panel-height "]")]}
        [:div {:class (into [(if (= mobile-surface :chart) "flex" "hidden")
                             "bg-base-100"
                             "flex-col"
                             "min-h-0"
                             "min-w-0"
                             "overflow-hidden"]
                            ["lg:flex"
                             "lg:row-start-1"
                             "lg:col-start-1"
                             "lg:border-r"
                             "lg:border-base-300"])
               :data-parity-id "trade-chart-panel"}
         [:div {:class ["hidden" "lg:block"]}
         (when desktop-layout?
            (render-active-asset-panel state))]
         (when chart-panel-visible?
           [:div {:class ["overflow-hidden" "flex-1" "min-h-0" "min-w-0"]}
            (trade-chart-panel-content state)])]

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
          (when (and orderbook-panel-visible?
                     (not desktop-layout?))
            (render-orderbook-panel
             (mobile-orderbook-view-state orderbook-view-state mobile-surface)))]
         [:div {:class ["hidden" "h-full" "min-h-0" "lg:block"]}
          (when (and orderbook-panel-visible?
                     desktop-layout?)
            (render-orderbook-panel orderbook-view-state))]]

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
         (when order-entry-panel-visible?
           (render-order-form-panel state))
         [:div {:class ["hidden" "border-t" "border-base-300" "lg:block"]
                :data-parity-id "trade-desktop-account-equity-panel"}
          (when (and desktop-layout?
                     order-entry-panel-visible?)
            (render-account-equity-panel state equity-metrics {}))]]

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
                             "lg:h-full"
                             "xl:col-start-1"
                             "xl:col-span-2"])
               :data-parity-id "trade-account-tables-panel"}
         [:div {:class ["w-full" "lg:hidden"]
                :data-parity-id "trade-mobile-account-panel"}
          (when (and account-panel-visible?
                     (not desktop-layout?))
            (render-account-info-panel state))]
         [:div {:class ["hidden" "w-full" "min-h-0" "lg:flex"]
                :data-parity-id "trade-desktop-account-panel"}
          (when (and account-panel-visible?
                     desktop-layout?)
            (render-account-info-panel state {:default-panel-classes ["h-full"]}))]]]

       (when mobile-account-summary-visible?
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
          (mobile-account-surface state equity-metrics)])]]]))
