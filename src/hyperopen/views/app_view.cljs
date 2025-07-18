(ns hyperopen.views.app-view
  (:require [hyperopen.views.active-asset-view :as active-asset-view]
            [hyperopen.views.l2-orderbook-view :as l2-orderbook-view]))

(defn app-view [state]
  [:div.min-h-screen.bg-base-100.p-8
   [:div.max-w-7xl.mx-auto.space-y-8
    ;; Header
    [:div.text-center.space-y-4
     [:h1.text-4xl.font-bold.text-primary (:title state)]
     [:p.text-lg.text-base-content.opacity-80 (:message state)]]
    
    ;; Controls
    [:div.flex.justify-center.space-x-4
     [:button.btn.btn-primary
      {:on {:click [[:actions/init-websockets]]}}
      "Connect WebSocket"]
     [:button.btn.btn-secondary
      {:on {:click [[:actions/subscribe-to-asset "BTC"]]}}
      "Subscribe to BTC"]
     [:button.btn.btn-secondary
      {:on {:click [[:actions/subscribe-to-asset "ETH"]]}}
      "Subscribe to ETH"]]
    
    ;; WebSocket Status
    [:div.text-center
     [:p.text-sm "WebSocket Status: " 
      [:span.badge.badge-info (name (get-in state [:websocket :status] :disconnected))]]]
    
    ;; Active Assets Panel
    [:div
     (active-asset-view/active-asset-view (:active-assets state))]
    
    ;; L2 Order Book Panel
    [:div.flex.justify-center
     (let [active-coins (keys (:contexts (:active-assets state)))
           first-active-coin (first active-coins)
           orderbook-data (when first-active-coin 
                           (get-in state [:orderbooks first-active-coin]))]
       (l2-orderbook-view/l2-orderbook-view 
         {:coin (or first-active-coin "No Asset Selected")
          :orderbook orderbook-data
          :loading (and first-active-coin (nil? orderbook-data))}))]
    
    ;; Demo Counter Card
    [:div.card.bg-base-200.shadow-xl.p-6.max-w-md.mx-auto
     [:p.text-xl.mb-4 "You clicked " (:count state) " times"]
     [:button.btn.btn-primary.btn-lg
      {:on {:click [[:actions/increment-count]]}}
      "Click me!"]]]]) 