(ns hyperopen.core
  (:require [replicant.dom :as r]
            [nexus.registry :as nxr]
            [hyperopen.views.app-view :as app-view]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.orderbook :as orderbook]
            [hyperopen.websocket.webdata2 :as webdata2]
            [hyperopen.api :as api]))

;; App state
(defonce store (atom {:title "Hyperopen"
                      :message "Welcome to Hyperopen - A ClojureScript app with Replicant"
                      :count 0
                      :websocket {:status :disconnected}
                      :active-assets {:contexts {}
                                     :loading false}
                      :orderbooks {}
                      :webdata2 {}
                      :asset-selector {:visible-dropdown nil
                                      :search-term ""
                      				  :sort-by :volume
                      				  :sort-direction :desc}}))

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
  (println "Subscribing to orderbook for:" coin)
  (orderbook/subscribe-orderbook! coin))

(defn subscribe-webdata2 [_ store address]
  (println "Subscribing to WebData2 for address:" address)
  (webdata2/subscribe-webdata2! address))

;; Actions - pure functions that return effects
(defn increment-count [state]
  [[:effects/save [:count] (inc (:count state))]])

(defn init-websockets [state]
  [[:effects/init-websocket]])

(defn subscribe-to-asset [state coin]
  [[:effects/subscribe-active-asset coin]
   [:effects/subscribe-webdata2 "0x0000000000000000000000000000000000000000"]
   [:effects/subscribe-orderbook coin]])

(defn subscribe-to-webdata2 [state address]
  [[:effects/subscribe-webdata2 address]])

(defn toggle-asset-dropdown [state coin]
  (let [current-dropdown (get-in state [:asset-selector :visible-dropdown])]
    [[:effects/save [:asset-selector :visible-dropdown] 
      (if (= current-dropdown coin) nil coin)]]))

(defn close-asset-dropdown [state]
  [[:effects/save [:asset-selector :visible-dropdown] nil]])

(defn select-asset [state coin]
  [[:effects/save [:selected-asset] coin]
   [:effects/save [:asset-selector :visible-dropdown] nil]])

(defn update-asset-search [state value]
  [[:effects/save [:asset-selector :search-term] (str value)]])

(defn update-asset-sort [state sort-field]
  (let [current-sort (get-in state [:asset-selector :sort-by])
        current-direction (get-in state [:asset-selector :sort-direction] :asc)
        new-direction (if (= current-sort sort-field)
                       (if (= current-direction :asc) :desc :asc)
                       :desc)]
    [[:effects/save [:asset-selector :sort-by] sort-field]
     [:effects/save [:asset-selector :sort-direction] new-direction]]))





;; Register effects and actions
(nxr/register-effect! :effects/save save)
(nxr/register-effect! :effects/init-websocket init-websocket)
(nxr/register-effect! :effects/subscribe-active-asset subscribe-active-asset)
(nxr/register-effect! :effects/subscribe-orderbook subscribe-orderbook)
(nxr/register-effect! :effects/subscribe-webdata2 subscribe-webdata2)
(nxr/register-action! :actions/increment-count increment-count)
(nxr/register-action! :actions/init-websockets init-websockets)
(nxr/register-action! :actions/subscribe-to-asset subscribe-to-asset)
(nxr/register-action! :actions/subscribe-to-webdata2 subscribe-to-webdata2)
(nxr/register-action! :actions/toggle-asset-dropdown toggle-asset-dropdown)
(nxr/register-action! :actions/close-asset-dropdown close-asset-dropdown)
(nxr/register-action! :actions/select-asset select-asset)
(nxr/register-action! :actions/update-asset-search update-asset-search)
(nxr/register-action! :actions/update-asset-sort update-asset-sort)
(nxr/register-system->state! deref)

;; Register placeholder for DOM event values
(nxr/register-placeholder! :event.target/value
  (fn [{:replicant/keys [dom-event]}]
    (some-> dom-event .-target .-value)))

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
  ;; initalize websocket client
  (ws-client/init-connection! "wss://api.hyperliquid.xyz/ws")
  ;; Initialize active asset context with store access
  (active-ctx/init! store)
  ;; Initialize orderbook module
  (orderbook/init! store)
  ;; Initialize WebData2 module
  (webdata2/init! store)
  ;; Fetch initial market data
  (api/fetch-asset-contexts! store)
  ;; Trigger initial render by updating the store
  (swap! store identity)) 