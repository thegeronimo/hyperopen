(ns hyperopen.workbench.scenes.markets.orderbook-scenes
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.workbench.support.fixtures :as fixtures]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.workbench.support.state :as ws]
            [hyperopen.views.l2-orderbook-view :as orderbook-view]))

(portfolio/configure-scenes
  {:title "Order Book"
   :collection :markets})

(defn- orderbook-state
  [overrides]
  (merge {:coin "BTC"
          :market (fixtures/market)
          :orderbook (fixtures/orderbook)
          :orderbook-ui {:size-unit :base
                         :size-unit-dropdown-visible? false
                         :price-aggregation-dropdown-visible? false
                         :price-aggregation-by-coin {"BTC" :full}
                         :active-tab :orderbook}
          :show-surface-freshness-cues? true
          :websocket-health (fixtures/websocket-health)
          :loading false}
         overrides))

(defn- orderbook-reducers
  []
  {:actions/select-orderbook-tab
   (fn [state _dispatch-data tab]
     (assoc-in state [:orderbook-ui :active-tab] tab))

   :actions/toggle-orderbook-size-unit-dropdown
   (fn [state _dispatch-data]
     (update-in state [:orderbook-ui :size-unit-dropdown-visible?] not))

   :actions/select-orderbook-size-unit
   (fn [state _dispatch-data size-unit]
     (-> state
         (assoc-in [:orderbook-ui :size-unit] size-unit)
         (assoc-in [:orderbook-ui :size-unit-dropdown-visible?] false)))

   :actions/toggle-orderbook-price-aggregation-dropdown
   (fn [state _dispatch-data]
     (update-in state [:orderbook-ui :price-aggregation-dropdown-visible?] not))

   :actions/select-orderbook-price-aggregation
   (fn [state _dispatch-data mode]
     (-> state
         (assoc-in [:orderbook-ui :price-aggregation-by-coin "BTC"] mode)
         (assoc-in [:orderbook-ui :price-aggregation-dropdown-visible?] false)))})

(defonce default-orderbook-store
  (ws/create-store ::default-orderbook (orderbook-state {})))

(defonce trades-orderbook-store
  (ws/create-store ::trades-orderbook (orderbook-state {:orderbook-ui {:active-tab :trades}})))

(defonce delayed-orderbook-store
  (ws/create-store ::delayed-orderbook
                   (orderbook-state {:orderbook-ui {:size-unit :quote
                                                    :price-aggregation-dropdown-visible? true}
                                     :websocket-health {:transport {:freshness :delayed
                                                                    :last-recv-at-ms 1762790400000}
                                                        :topics {"l2Book|BTC" {:freshness :delayed
                                                                               :last-payload-at-ms 1762789800000
                                                                               :stale-threshold-ms 4000}}}})))

(portfolio/defscene depth-default
  :params default-orderbook-store
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (orderbook-reducers)
    [:div {:class ["h-[620px]"]}
     (orderbook-view/l2-orderbook-view @store)])))

(portfolio/defscene trades-tab
  :params trades-orderbook-store
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (orderbook-reducers)
    [:div {:class ["h-[620px]"]}
     (orderbook-view/l2-orderbook-view @store)])))

(portfolio/defscene quote-units-delayed
  :params delayed-orderbook-store
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (orderbook-reducers)
    (layout/mobile-shell
     [:div {:class ["h-[620px]"]}
      (orderbook-view/l2-orderbook-view @store)]))))
