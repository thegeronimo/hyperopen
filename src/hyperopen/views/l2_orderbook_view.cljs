(ns hyperopen.views.l2-orderbook-view
  (:require [hyperopen.outcomes.domain.price-unit :as price-unit]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.ui.voice :as voice]
            [hyperopen.views.l2-orderbook.depth :as depth]
            [hyperopen.views.l2-orderbook.dropdowns :as dropdowns]
            [hyperopen.views.l2-orderbook.model :as model]
            [hyperopen.views.l2-orderbook.panel :as panel]
            [hyperopen.views.l2-orderbook.tabs :as tabs]
            [hyperopen.views.l2-orderbook.trades :as trades]))

(def normalize-orderbook-tab model/normalize-orderbook-tab)
(def calculate-cumulative-totals model/calculate-cumulative-totals)
(def resolve-base-symbol model/resolve-base-symbol)
(def resolve-quote-symbol model/resolve-quote-symbol)
(def trade-time->ms model/trade-time->ms)
(def format-trade-time model/format-trade-time)
(def trade-side->price-class model/trade-side->price-class)
(def normalize-trade model/normalize-trade)
(def recent-trades-for-coin model/recent-trades-for-coin)
(def order-size-for-unit model/order-size-for-unit)
(def order-total-for-unit model/order-total-for-unit)
(def format-order-size model/format-order-size)
(def format-order-total model/format-order-total)
(def trades-panel trades/trades-panel)
(def order-row depth/order-row)
(def l2-orderbook-panel panel/l2-orderbook-panel)

(defn l2-orderbook-view [state]
  (let [coin (:coin state)
        market (:market state)
        orderbook-data (:orderbook state)
        show-surface-freshness-cues?
        (boolean (:show-surface-freshness-cues? state))
        websocket-health (or (:websocket-health state)
                             (get-in state [:websocket :health]))
        active-tab-override (:active-tab-override state)
        show-tabs? (not= false (:show-tabs? state))
        orderbook-ui (merge {:size-unit :base
                             :size-unit-dropdown-visible? false
                             :price-aggregation-dropdown-visible? false
                             :price-aggregation-by-coin {}
                             :active-tab :orderbook}
                            (:orderbook-ui state))
        layout {:desktop-layout? (:desktop-layout? state)
                :viewport-width (:viewport-width state)}
        animate-orderbook? (trading-settings/animate-orderbook? state)
        loading? (:loading state)
        active-tab (model/normalize-orderbook-tab (or active-tab-override
                                                   (:active-tab orderbook-ui)))
        base-symbol (model/resolve-base-symbol coin market)]
    [:div {:class ["w-full" "h-full" "min-h-0" "overflow-hidden" "flex" "flex-col"]
           :data-parity-id "orderbook-panel"}
     (when show-tabs?
       (tabs/orderbook-tabs-row active-tab
                                {:orderbook-label (voice/label state :orderbook/book)
                                 :trades-label (voice/label state :orderbook/trades)}))
     [:div {:class ["flex-1" "h-full" "min-h-0" "overflow-hidden" "bg-base-100"]}
      (if (= active-tab :trades)
        (tabs/tab-content-viewport
         (trades/trades-panel coin base-symbol
                              {:cents? (price-unit/outcome-market? coin market)}))
        (tabs/tab-content-viewport
         (cond
           loading? (panel/loading-orderbook)
           (and coin orderbook-data) (panel/l2-orderbook-panel coin
                                                               market
                                                               orderbook-data
                                                               orderbook-ui
                                                               websocket-health
                                                               show-surface-freshness-cues?
                                                               layout
                                                               animate-orderbook?
                                                               {:open-orders (:own-orders state)
                                                                :impact (:impact state)})
           :else (panel/empty-orderbook))))]]))
