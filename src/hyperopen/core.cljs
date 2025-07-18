(ns hyperopen.core
  (:require [replicant.dom :as r]
            [nexus.registry :as nxr]
            [hyperopen.views.app-view :as app-view]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.orderbook :as orderbook]))

;; App state
(defonce store (atom {:title "Hyperopen"
                      :message "Welcome to Hyperopen - A ClojureScript app with Replicant"
                      :count 0
                      :websocket {:status :disconnected}
                      :active-assets {:contexts {}
                                     :loading false}
                      :orderbooks {}}))

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

(defn subscribe-orderbook [_ store coin]
  (orderbook/subscribe-orderbook! coin))

;; Actions - pure functions that return effects
(defn increment-count [state]
  [[:effects/save [:count] (inc (:count state))]])

(defn init-websockets [state]
  [[:effects/init-websocket]])

(defn subscribe-to-asset [state coin]
  [[:effects/subscribe-active-asset coin]
   [:effects/subscribe-orderbook coin]])





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
(add-watch store ::render #(r/render (.getElementById js/document "app") (app-view/app-view %4)))

;; Watch for WebSocket connection status changes
(add-watch ws-client/connection-state ::ws-status
  (fn [_ _ _ new-state]
    (swap! store assoc-in [:websocket :status] (:status new-state))))

(defn reload []
  (println "Reloading Hyperopen...")
  (r/render (.getElementById js/document "app") (app-view/app-view @store)))

(defn init []
  (println "Initializing Hyperopen...")
  ;; Initialize active asset context with store access
  (active-ctx/init! store)
  ;; Initialize orderbook module
  (orderbook/init! store)
  ;; Trigger initial render by updating the store
  (swap! store identity)) 