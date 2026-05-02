(ns hyperopen.views.trade-view
  (:require [hyperopen.surface-modules :as surface-modules]
            [hyperopen.trade-modules :as trade-modules]
            [hyperopen.views.active-asset.vm :as active-asset-vm]
            [hyperopen.views.active-asset-view :as active-asset-view]
            [hyperopen.views.asset-selector-view :as asset-selector-view]
            [hyperopen.views.l2-orderbook-view :as l2-orderbook-view]
            [hyperopen.views.trade.order-form-view :as order-form-view]
            [hyperopen.views.trade-view.layout-state :as layout-state]
            [hyperopen.views.trade-view.loading-shell :as loading-shell]
            [hyperopen.views.trade-view.shell :as shell]))

(def ^:private trade-chart-view-base-state-keys
  [:active-asset
   :active-market
   :account
   :asset-contexts
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
   :orders
   :perp-dex-clearinghouse
   :positions-ui
   :spot
   :webdata2])

(def ^:private account-equity-view-state-keys
  [:account
   :perp-dex-clearinghouse
   :spot
   :webdata2])

(def ^:private order-form-view-state-keys
  [:account
   :active-asset
   :active-assets
   :active-market
   :asset-contexts
   :order-form
   :order-form-runtime
   :order-form-ui
   :orderbooks
   :perp-dex-clearinghouse
   :spot
   :wallet
   :webdata2])

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

(defn- asset-selector-market-lookup-state
  [state]
  {:asset-selector {:market-by-key (get-in state [:asset-selector :market-by-key] {})}})

(defn- trade-chart-view-state
  [state]
  (cond-> (merge (select-view-state state trade-chart-view-base-state-keys)
                 (asset-selector-market-lookup-state state))
    (surface-freshness-cues-enabled? state)
    (assoc :websocket (:websocket state)
           :websocket-ui (:websocket-ui state))))

(defn- account-info-view-state
  [state]
  (cond-> (merge (select-view-state state account-info-view-base-state-keys)
                 (asset-selector-market-lookup-state state))
    (surface-freshness-cues-enabled? state)
    (assoc :websocket (:websocket state)
           :websocket-ui (:websocket-ui state))))

(defn- account-equity-view-state
  [state]
  (merge (select-view-state state account-equity-view-state-keys)
         (asset-selector-market-lookup-state state)))

(defn- order-form-view-state
  [state]
  (select-view-state state order-form-view-state-keys))

(defn- account-surface-export
  [export-id]
  (surface-modules/resolved-surface-export :account-surfaces export-id))

(def ^:private memoized-active-asset-view
  (memoize-last (fn [render-fn state]
                  (render-fn state))))

(def ^:private memoized-trade-chart-panel-content
  (memoize-last (fn [render-fn state]
                  (or (render-fn state)
                      (loading-shell/trade-chart-loading-shell state)))))

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

(defonce ^:private frozen-trade-chart-view-state* (atom nil))

(defonce ^:private frozen-active-asset-view-state* (atom nil))

(defonce ^:private frozen-account-info-view-state* (atom nil))

(defonce ^:private frozen-account-equity-view-state* (atom nil))

(defonce ^:private frozen-orderbook-view-state* (atom nil))

(defonce ^:private frozen-order-form-view-state* (atom nil))

(defn- viewport-width-px []
  (some-> js/globalThis .-innerWidth))

(defn- mobile-orderbook-view-state
  [orderbook-view-state mobile-surface]
  (assoc orderbook-view-state
         :show-tabs? false
         :active-tab-override (if (= mobile-surface :trades)
                                :trades
                                :orderbook)))

(declare render-account-equity-metrics-state
         render-account-equity-panel-state
         render-account-info-panel-state
         render-active-asset-panel-state
         trade-chart-panel-content-state)

(defn- render-active-asset-panel
  [state]
  (render-active-asset-panel-state (active-asset-view-state state)))

(defn- render-active-asset-panel-state
  [view-state]
  (memoized-active-asset-view active-asset-view/active-asset-view
                              view-state))

(defn- render-account-info-panel
  ([state]
   (render-account-info-panel state {}))
  ([state opts]
   (render-account-info-panel-state (account-info-view-state state) opts)))

(defn- render-account-info-panel-state
  [view-state opts]
  (when-let [render-fn (account-surface-export :account-info-view)]
    (memoized-account-info-view render-fn
                                view-state
                                opts)))

(defn- render-account-equity-panel
  [state equity-metrics opts]
  (render-account-equity-panel-state (account-equity-view-state state)
                                     equity-metrics
                                     opts))

(defn- render-account-equity-panel-state
  [view-state equity-metrics opts]
  (when-let [render-fn (account-surface-export :account-equity-view)]
    (memoized-account-equity-view render-fn
                                  view-state
                                  (assoc opts :metrics equity-metrics))))

(defn- render-account-equity-metrics
  [state]
  (render-account-equity-metrics-state (account-equity-view-state state)))

(defn- render-account-equity-metrics-state
  [view-state]
  (when-let [metrics-fn (account-surface-export :account-equity-metrics)]
    (memoized-account-equity-metrics metrics-fn
                                     view-state)))

(defn- trade-chart-panel-content
  [state]
  (trade-chart-panel-content-state (trade-chart-view-state state)))

(defn- trade-chart-panel-content-state
  [view-state]
  (memoized-trade-chart-panel-content trade-modules/render-trade-chart-view
                                      view-state))

(defn- render-orderbook-panel
  [state]
  (memoized-orderbook-view l2-orderbook-view/l2-orderbook-view
                           state))

(defn- render-order-form-panel
  [state]
  (memoized-order-form-view order-form-view/order-form-view
                            state))

(defn- freeze-heavy-trade-panels?
  [state desktop-layout?]
  (and desktop-layout?
       (= :asset-selector (get-in state [:asset-selector :visible-dropdown]))
       (asset-selector-view/asset-list-freeze-active?)))

(defn- selector-scroll-snapshot
  [snapshot* freeze? next-state-fn]
  (if freeze?
    (let [snapshot @snapshot*]
      (if (some? snapshot)
        snapshot
        (do
          (let [next-state (next-state-fn)]
            (reset! snapshot* next-state)
            next-state))))
    (do
      (let [next-state (next-state-fn)]
        (reset! snapshot* next-state)
        next-state))))

(defn- orderbook-view-state
  [state active-asset orderbook-data show-surface-freshness-cues? websocket-health]
  {:coin (or active-asset "No Asset Selected")
   :market (:active-market state)
   :orderbook orderbook-data
   :orderbook-ui (:orderbook-ui state)
   :trading-settings (:trading-settings state)
   :show-surface-freshness-cues? show-surface-freshness-cues?
   :websocket-health (when show-surface-freshness-cues?
                       websocket-health)
   :loading (and active-asset (nil? orderbook-data))})

(defn- mobile-account-surface [state equity-metrics]
  (let [account-equity-panel (render-account-equity-panel state
                                                          equity-metrics
                                                          {:fill-height? false
                                                           :show-funding-actions? false})
        funding-actions-view (account-surface-export :funding-actions-view)]
    (if (and account-equity-panel
             (fn? funding-actions-view))
      [:div {:class ["flex" "h-full" "min-h-0" "flex-col" "bg-base-100"]
             :data-parity-id "trade-mobile-account-surface"}
       account-equity-panel
       (funding-actions-view
        state
        {:container-classes ["mt-auto"
                             "border-t"
                             "border-base-300"
                             "bg-base-100"
                             "px-3"
                             "pt-2"
                             "pb-1.5"
                             "space-y-2"]
         :data-parity-id "trade-mobile-account-actions"})]
      (shell/desktop-secondary-panel-placeholder "Account"
                                                 "trade-mobile-account-surface-placeholder"
                                                 :fill-height? true))))

(defn- trade-view-layout-context
  [state]
  (let [funding-tooltip-open? (true? (active-asset-vm/active-asset-funding-tooltip-open? state))
        layout (layout-state/trade-layout-state state
                                               (viewport-width-px)
                                               funding-tooltip-open?)]
    {:desktop-layout? (:desktop-layout? layout)
     :funding-tooltip-open? funding-tooltip-open?
     :mobile-surface (:mobile-surface layout)
     :layout layout}))

(defn- desktop-secondary-panels-ready?
  [state desktop-layout?]
  (or (not desktop-layout?)
      (not= false (get-in state [:trade-ui :desktop-secondary-panels-ready?]))))

(defn- trade-view-panel-context
  [state {:keys [desktop-layout? layout mobile-surface]}]
  (let [active-asset (:active-asset state)
        orderbook-data (when active-asset (get-in state [:orderbooks active-asset]))
        freeze-heavy-panels? (freeze-heavy-trade-panels? state desktop-layout?)
        desktop-secondary-panels-ready?* (desktop-secondary-panels-ready? state desktop-layout?)
        active-asset-panel-state (selector-scroll-snapshot
                                  frozen-active-asset-view-state*
                                  freeze-heavy-panels?
                                  #(active-asset-view-state state))
        trade-chart-panel-state (selector-scroll-snapshot
                                 frozen-trade-chart-view-state*
                                 freeze-heavy-panels?
                                 #(trade-chart-view-state state))
        account-info-panel-state (when desktop-secondary-panels-ready?*
                                  (selector-scroll-snapshot
                                   frozen-account-info-view-state*
                                   freeze-heavy-panels?
                                   #(account-info-view-state state)))
        account-equity-panel-state (when (and (:show-equity-surface? layout)
                                              desktop-secondary-panels-ready?*)
                                    (selector-scroll-snapshot
                                     frozen-account-equity-view-state*
                                     freeze-heavy-panels?
                                     #(account-equity-view-state state)))
        show-surface-freshness-cues? (surface-freshness-cues-enabled? state)
        websocket-health (get-in state [:websocket :health])
        equity-metrics (when (and (:show-equity-surface? layout)
                                  desktop-secondary-panels-ready?*)
                         (render-account-equity-metrics-state account-equity-panel-state))
        orderbook-panel-state (selector-scroll-snapshot
                               frozen-orderbook-view-state*
                               freeze-heavy-panels?
                               #(orderbook-view-state state
                                                     active-asset
                                                     orderbook-data
                                                     show-surface-freshness-cues?
                                                     websocket-health))
        order-form-panel-state (selector-scroll-snapshot
                                frozen-order-form-view-state*
                                freeze-heavy-panels?
                                #(order-form-view-state state))]
    {:active-asset-panel-state active-asset-panel-state
     :trade-chart-panel-state trade-chart-panel-state
     :desktop-secondary-panels-ready? desktop-secondary-panels-ready?*
     :account-info-panel-state account-info-panel-state
     :account-equity-panel-state account-equity-panel-state
     :equity-metrics equity-metrics
     :orderbook-panel-state orderbook-panel-state
     :order-form-panel-state order-form-panel-state
     :mobile-orderbook-panel-state (when-not desktop-layout?
                                    (mobile-orderbook-view-state orderbook-panel-state mobile-surface))}))

(defn trade-view [state]
  (let [{:keys [mobile-surface] :as layout-context} (trade-view-layout-context state)
        panel-context (trade-view-panel-context state layout-context)
        renderers {:mobile-account-surface mobile-account-surface
                   :render-account-equity-panel-state render-account-equity-panel-state
                   :render-account-info-panel render-account-info-panel
                   :render-account-info-panel-state render-account-info-panel-state
                   :render-active-asset-panel render-active-asset-panel
                   :render-active-asset-panel-state render-active-asset-panel-state
                   :render-orderbook-panel render-orderbook-panel
                   :render-order-form-panel render-order-form-panel
                   :trade-chart-panel-content-state trade-chart-panel-content-state}]
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
      (shell/render-mobile-active-asset-strip state layout-context renderers)
      (shell/render-mobile-surface-tabs mobile-surface layout-context)
      (shell/render-trade-grid state layout-context panel-context renderers)]]))
