(ns hyperopen.views.l2-orderbook-view
  (:require [hyperopen.trading-settings :as trading-settings]
            [hyperopen.views.l2-orderbook.depth :as depth]
            [hyperopen.views.l2-orderbook.dropdowns :as dropdowns]
            [hyperopen.views.l2-orderbook.model :as model]
            [hyperopen.views.l2-orderbook.panel :as panel]
            [hyperopen.views.l2-orderbook.tabs :as tabs]
            [hyperopen.views.l2-orderbook.trades :as trades]))

(def parse-number model/parse-number)
(def orderbook-tabs model/orderbook-tabs)
(def normalize-orderbook-tab model/normalize-orderbook-tab)
(def format-price model/format-price)
(def format-percent model/format-percent)
(def format-total model/format-total)
(def calculate-spread model/calculate-spread)
(def calculate-cumulative-totals model/calculate-cumulative-totals)
(def normalize-size-unit model/normalize-size-unit)
(def base-symbol-from-coin model/base-symbol-from-coin)
(def quote-symbol-from-coin model/quote-symbol-from-coin)
(def resolve-base-symbol model/resolve-base-symbol)
(def resolve-quote-symbol model/resolve-quote-symbol)
(def infer-market-type model/infer-market-type)
(def midpoint-price model/midpoint-price)
(def resolve-reference-price model/resolve-reference-price)
(def trade-time->ms model/trade-time->ms)
(def format-trade-time model/format-trade-time)
(def trade-side->price-class model/trade-side->price-class)
(def trade-matches-coin? model/trade-matches-coin?)
(def normalize-trade model/normalize-trade)
(def format-trade-size model/format-trade-size)
(def recent-trades-for-coin model/recent-trades-for-coin)
(def order-size-for-unit model/order-size-for-unit)
(def order-total-for-unit model/order-total-for-unit)
(def get-max-cumulative-total model/get-max-cumulative-total)
(def format-order-size model/format-order-size)
(def format-order-total model/format-order-total)
(def cumulative-bar-width model/cumulative-bar-width)
(def precision-dropdown dropdowns/precision-dropdown)
(def size-unit-dropdown dropdowns/size-unit-dropdown)
(def orderbook-header tabs/orderbook-header)
(def orderbook-tab-button tabs/orderbook-tab-button)
(def orderbook-tabs-row tabs/orderbook-tabs-row)
(def tab-content-viewport tabs/tab-content-viewport)
(def trades-column-headers trades/trades-column-headers)
(def trades-row trades/trades-row)
(def empty-trades trades/empty-trades)
(def trades-panel trades/trades-panel)
(def order-row depth/order-row)
(def spread-row depth/spread-row)
(def column-headers depth/column-headers)
(def l2-orderbook-panel panel/l2-orderbook-panel)
(def empty-orderbook panel/empty-orderbook)
(def loading-orderbook panel/loading-orderbook)

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
       (tabs/orderbook-tabs-row active-tab))
     [:div {:class ["flex-1" "h-full" "min-h-0" "overflow-hidden" "bg-base-100"]}
      (if (= active-tab :trades)
        (tabs/tab-content-viewport
         (trades/trades-panel coin base-symbol))
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
                                                               animate-orderbook?)
           :else (panel/empty-orderbook))))]]))
