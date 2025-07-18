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
     (l2-orderbook-view/l2-orderbook-view 
       {:coin "PUMP"
        :orderbook {:bids [{:px 0.004615 :sz 50015137}
                           {:px 0.004614 :sz 1000000}
                           {:px 0.004613 :sz 2000000}
                           {:px 0.004612 :sz 1500000}
                           {:px 0.004611 :sz 3000000}
                           {:px 0.004610 :sz 2500000}
                           {:px 0.004609 :sz 1800000}
                           {:px 0.004608 :sz 2200000}
                           {:px 0.004607 :sz 1200000}
                           {:px 0.004606 :sz 800000}
                           {:px 0.004605 :sz 600000}
                           {:px 0.004604 :sz 400000}]
                  :asks [{:px 0.004616 :sz 3207398}
                         {:px 0.004617 :sz 1500000}
                         {:px 0.004618 :sz 2000000}
                         {:px 0.004619 :sz 1800000}
                         {:px 0.004620 :sz 2500000}
                         {:px 0.004621 :sz 1200000}
                         {:px 0.004622 :sz 3000000}
                         {:px 0.004623 :sz 2200000}
                         {:px 0.004624 :sz 1800000}
                         {:px 0.004625 :sz 1500000}
                         {:px 0.004626 :sz 1000000}]}
        :loading false})]
    
    ;; Demo Counter Card
    [:div.card.bg-base-200.shadow-xl.p-6.max-w-md.mx-auto
     [:p.text-xl.mb-4 "You clicked " (:count state) " times"]
     [:button.btn.btn-primary.btn-lg
      {:on {:click [[:actions/increment-count]]}}
      "Click me!"]]]]) 