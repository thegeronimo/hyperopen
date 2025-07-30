(ns hyperopen.core
  (:require [replicant.dom :as r]
            [nexus.registry :as nxr]
            [hyperopen.views.app-view :as app-view]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.orderbook :as orderbook]
            [hyperopen.websocket.webdata2 :as webdata2]
            [hyperopen.api :as api]
            [hyperopen.asset-selector.settings :as asset-selector-settings]))

;; App state
(defonce store (atom {:websocket {:status :disconnected}
                      :active-assets {:contexts {}
                                     :loading false}
                      :active-asset nil
                      :orderbooks {}
                      :webdata2 {}
                      :asset-selector {:visible-dropdown nil
                                      :search-term ""
                      				  :sort-by :volume
                      				  :sort-direction :desc}
                      :chart-options {:timeframes-dropdown-visible false
                                      :selected-timeframe :1d
                                      :chart-type-dropdown-visible false
                                      :selected-chart-type :candlestick}}))

;; Effects - handle side effects
(defn save [_ store path value]
  (swap! store assoc-in path value))

(defn fetch-candle-snapshot [_ store & {:keys [interval bars] :or {interval :1d bars 330}}]
  (println "Fetching candle snapshot for active asset...")
  (api/fetch-candle-snapshot! store :interval interval :bars bars))

(defn init-websocket [_ store]
  (println "Initializing WebSocket connection...")
  (ws-client/init-connection! "wss://api.hyperliquid.xyz/ws")
  (swap! store assoc-in [:websocket :status] :connecting))

(defn subscribe-active-asset [_ store coin]
  (println "Subscribing to active asset context for:" coin)
  (swap! store #(-> %
                    (assoc-in [:active-assets :loading] true)
                    (assoc-in [:active-asset] coin)))
  (active-ctx/subscribe-active-asset-ctx! coin)
  (fetch-candle-snapshot _ store))

(defn subscribe-orderbook [_ store coin]
  (println "Subscribing to orderbook for:" coin)
  (orderbook/subscribe-orderbook! coin))

(defn subscribe-webdata2 [_ store address]
  (println "Subscribing to WebData2 for address:" address)
  (webdata2/subscribe-webdata2! address))

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
   [:effects/save [:active-asset] coin]
   [:effects/save [:asset-selector :visible-dropdown] nil]
   [:effects/fetch-candle-snapshot]])

(defn update-asset-search [state value]
  [[:effects/save [:asset-selector :search-term] (str value)]])

;; --- asset selector sort settings logic moved to asset_selector/settings.cljs ---

(defn update-asset-selector-sort [state sort-field]
  (let [current-sort (get-in state [:asset-selector :sort-by])
        current-direction (get-in state [:asset-selector :sort-direction] :asc)
        new-direction (if (= current-sort sort-field)
                       (if (= current-direction :asc) :desc :asc)
                       :desc)]
    ;; Persist to localStorage
    (js/localStorage.setItem "asset-selector-sort-by" (name sort-field))
    (js/localStorage.setItem "asset-selector-sort-direction" (name new-direction))
    [[:effects/save [:asset-selector :sort-by] sort-field]
     [:effects/save [:asset-selector :sort-direction] new-direction]]))

(defn toggle-timeframes-dropdown [state]
  (let [current-visible (get-in state [:chart-options :timeframes-dropdown-visible])]
    [[:effects/save [:chart-options :timeframes-dropdown-visible] (not current-visible)]]))

(defn select-chart-timeframe [state timeframe]
  [[:effects/save [:chart-options :selected-timeframe] timeframe]
   [:effects/save [:chart-options :timeframes-dropdown-visible] false]
   [:effects/fetch-candle-snapshot :interval timeframe]])

(defn toggle-chart-type-dropdown [state]
  (let [current-visible (get-in state [:chart-options :chart-type-dropdown-visible])]
    [[:effects/save [:chart-options :chart-type-dropdown-visible] (not current-visible)]]))

(defn select-chart-type [state chart-type]
  [[:effects/save [:chart-options :selected-chart-type] chart-type]
   [:effects/save [:chart-options :chart-type-dropdown-visible] false]])

(defn toggle-indicators-dropdown [state]
  (let [current-visible (get-in state [:chart-options :indicators-dropdown-visible])]
    [[:effects/save [:chart-options :indicators-dropdown-visible] (not current-visible)]]))

(defn add-indicator [state indicator-type params]
  (let [current-indicators (get-in state [:chart-options :active-indicators] {})
        new-indicators (assoc current-indicators indicator-type params)]
    [[:effects/save [:chart-options :active-indicators] new-indicators]]))

(defn remove-indicator [state indicator-type]
  (let [current-indicators (get-in state [:chart-options :active-indicators] {})
        new-indicators (dissoc current-indicators indicator-type)]
    [[:effects/save [:chart-options :active-indicators] new-indicators]]))

(defn update-indicator-period [state indicator-type period-value]
  (let [current-indicators (get-in state [:chart-options :active-indicators] {})
        period (js/parseInt period-value)
        updated-indicators (assoc-in current-indicators [indicator-type :period] period)]
    [[:effects/save [:chart-options :active-indicators] updated-indicators]]))


;; Register effects and actions
(nxr/register-effect! :effects/save save)
(nxr/register-effect! :effects/init-websocket init-websocket)
(nxr/register-effect! :effects/subscribe-active-asset subscribe-active-asset)
(nxr/register-effect! :effects/subscribe-orderbook subscribe-orderbook)
(nxr/register-effect! :effects/subscribe-webdata2 subscribe-webdata2)
(nxr/register-effect! :effects/fetch-candle-snapshot fetch-candle-snapshot)
(nxr/register-action! :actions/init-websockets init-websockets)
(nxr/register-action! :actions/subscribe-to-asset subscribe-to-asset)
(nxr/register-action! :actions/subscribe-to-webdata2 subscribe-to-webdata2)
(nxr/register-action! :actions/toggle-asset-dropdown toggle-asset-dropdown)
(nxr/register-action! :actions/close-asset-dropdown close-asset-dropdown)
(nxr/register-action! :actions/select-asset select-asset)
(nxr/register-action! :actions/update-asset-search update-asset-search)
(nxr/register-action! :actions/update-asset-selector-sort update-asset-selector-sort)
(nxr/register-action! :actions/toggle-timeframes-dropdown toggle-timeframes-dropdown)
(nxr/register-action! :actions/select-chart-timeframe select-chart-timeframe)
(nxr/register-action! :actions/toggle-chart-type-dropdown toggle-chart-type-dropdown)
(nxr/register-action! :actions/select-chart-type select-chart-type)
(nxr/register-action! :actions/toggle-indicators-dropdown toggle-indicators-dropdown)
(nxr/register-action! :actions/add-indicator add-indicator)
(nxr/register-action! :actions/remove-indicator remove-indicator)
(nxr/register-action! :actions/update-indicator-period update-indicator-period)
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
  ;; Restore asset selector sort settings from localStorage
  (asset-selector-settings/restore-asset-selector-sort-settings! store)
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