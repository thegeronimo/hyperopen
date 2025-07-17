(ns hyperopen.core
  (:require [replicant.dom :as r]
            [nexus.registry :as nxr]
            [hyperopen.views.active-asset-view :as active-asset-view]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.client :as ws-client]))

;; App state
(defonce store (atom {:title "Hyperopen"
                      :message "Welcome to Hyperopen - A ClojureScript app with Replicant"
                      :count 0
                      :websocket {:status :disconnected}
                      :active-assets {:contexts {}
                                     :loading false}}))

;; Effects - handle side effects
(defn save [_ store path value]
  (swap! store assoc-in path value))

(defn init-websocket [_ store]
  (println "Initializing WebSocket connection...")
  (ws-client/init-connection! "wss://api.hyperliquid.xyz/ws")
  (swap! store assoc-in [:websocket :status] :connecting))

(defn subscribe-active-asset [_ store coin]
  (println "Subscribing to active asset context for:" coin)
  (active-ctx/subscribe-active-asset-ctx! coin)
  (swap! store assoc-in [:active-assets :loading] true))

;; Actions - pure functions that return effects
(defn increment-count [state]
  [[:effects/save [:count] (inc (:count state))]])

(defn init-websockets [state]
  [[:effects/init-websocket]])

(defn subscribe-to-asset [state coin]
  [[:effects/subscribe-active-asset coin]])



;; Pure component - uses actions directly in event handlers
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
    
    ;; Demo Counter Card
    [:div.card.bg-base-200.shadow-xl.p-6.max-w-md.mx-auto
     [:p.text-xl.mb-4 "You clicked " (:count state) " times"]
     [:button.btn.btn-primary.btn-lg
      {:on {:click [[:actions/increment-count]]}}
      "Click me!"]]]])

;; Register effects and actions
(nxr/register-effect! :effects/save save)
(nxr/register-effect! :effects/init-websocket init-websocket)
(nxr/register-effect! :effects/subscribe-active-asset subscribe-active-asset)
(nxr/register-action! :actions/increment-count increment-count)
(nxr/register-action! :actions/init-websockets init-websockets)
(nxr/register-action! :actions/subscribe-to-asset subscribe-to-asset)
(nxr/register-system->state! deref)

;; Wire up the render loop
(r/set-dispatch! #(nxr/dispatch store %1 %2))
(add-watch store ::render #(r/render (.getElementById js/document "app") (app-view %4)))

;; Watch for WebSocket connection status changes
(add-watch ws-client/connection-state ::ws-status
  (fn [_ _ _ new-state]
    (swap! store assoc-in [:websocket :status] (:status new-state))))

(defn reload []
  (println "Reloading Hyperopen...")
  (r/render (.getElementById js/document "app") (app-view @store)))

(defn init []
  (println "Initializing Hyperopen...")
  ;; Initialize active asset context with store access
  (active-ctx/init! store)
  ;; Trigger initial render by updating the store
  (swap! store identity)) 