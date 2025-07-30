(ns hyperopen.views.app-view
  (:require [hyperopen.views.active-asset-view :as active-asset-view]
            [hyperopen.views.l2-orderbook-view :as l2-orderbook-view]
            [hyperopen.views.trading-chart.core :as trading-chart]
            [hyperopen.views.footer-view :as footer-view]
            [hyperopen.views.header-view :as header-view]))

(defn app-view [state]
  [:div.h-screen.bg-base-100.flex.flex-col
   ;; Header - Pinned to top
   (header-view/header-view state)
   ;; Main Content
   [:div.flex-1.overflow-auto
    ;; Active Assets Panel - Full width like header
    [:div.w-full
     (active-asset-view/active-asset-view state)]
    
     ;; Trading Chart Panel
     [:div
      (trading-chart/trading-chart-view state)]
    
    ;; Other content with max width constraint
    [:div.max-w-7xl.mx-auto.px-8.space-y-8
     
     ;; L2 Order Book Panel
     [:div.flex.justify-center
      (let [active-asset (:active-asset state)
            orderbook-data (when active-asset 
                            (get-in state [:orderbooks active-asset]))]
        (l2-orderbook-view/l2-orderbook-view 
          {:coin (or active-asset "No Asset Selected")
           :orderbook orderbook-data
           :loading (and active-asset (nil? orderbook-data))}))]
           
     ;; Trading Chart Panel
     [:div
      (trading-chart/trading-chart-view state)]]]
   
   ;; Controls - Above footer
   [:div.p-4.bg-base-200.border-t.border-base-300
    [:div.max-w-7xl.mx-auto
     [:div.flex.justify-center.space-x-4
      [:button.btn.btn-primary
       {:on {:click [[:actions/init-websockets]]}}
       "Connect WebSocket"]
      [:button.btn.btn-secondary
       {:on {:click [[:actions/subscribe-to-asset "BTC"]]}}
       "Subscribe to BTC"]
      [:button.btn.btn-secondary
       {:on {:click [[:actions/subscribe-to-asset "ETH"]]}}
       "Subscribe to ETH"]]]]
   
   ;; Footer - Pinned to bottom
   (footer-view/footer-view state)]) 