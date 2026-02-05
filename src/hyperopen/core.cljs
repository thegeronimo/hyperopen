(ns hyperopen.core
  (:require [replicant.dom :as r]
            [nexus.registry :as nxr]
            [hyperopen.views.app-view :as app-view]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.orderbook :as orderbook]
            [hyperopen.websocket.webdata2 :as webdata2]
            [hyperopen.websocket.user :as user-ws]
            [hyperopen.api :as api]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.asset-selector.settings :as asset-selector-settings]
            [hyperopen.wallet.core :as wallet]
            [hyperopen.wallet.address-watcher :as address-watcher]
            [hyperopen.router :as router]
            [hyperopen.state.trading :as trading]))

;; App state
(defonce store (atom {:websocket {:status :disconnected}
                      :active-assets {:contexts {}
                                     :loading false}
                      :active-asset nil
                      :orderbooks {}
                      :webdata2 {}
                      :perp-dexs []
                      :perp-dex-clearinghouse {}
                      :spot {:meta nil
                             :clearinghouse-state nil
                             :loading-meta? false
                             :loading-balances? false
                             :error nil}
                      :orders {:open-orders []
                               :open-orders-snapshot []
                               :open-orders-snapshot-by-dex {}
                               :fills []
                               :fundings []
                               :ledger []}
                      :wallet {:connected? false
                               :address    nil
                               :chain-id   nil
                               :connecting? false
                               :error      nil}
                      :router {:path "/trade"}
                      :order-form (trading/default-order-form)
                      :funding-ui {:modal nil}
                      :asset-selector {:visible-dropdown nil
                                      :search-term ""
                      				  :sort-by :volume
                      				  :sort-direction :desc}
                      :chart-options {:timeframes-dropdown-visible false
                                      :selected-timeframe :1d
                                      :chart-type-dropdown-visible false
                                      :selected-chart-type :candlestick}
                      :account-info {:selected-tab :balances
                                     :loading false
                                     :error nil
                                     :hide-small-balances? false
                                     :balances-sort {:column nil :direction :asc}
                                     :positions-sort {:column nil :direction :asc}
                                     :open-orders-sort {:column "Time" :direction :desc}}}))

;; Effects - handle side effects
(defn save [_ store path value]
  (swap! store assoc-in path value))

(defn push-state [_ _ path]
  (.pushState js/history nil "" path))

(defn replace-state [_ _ path]
  (.replaceState js/history nil "" path))

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

(defn unsubscribe-active-asset [_ store coin]
  (println "Unsubscribing from active asset context for:" coin)
  (active-ctx/unsubscribe-active-asset-ctx! coin)
  (swap! store update-in [:active-assets :contexts] dissoc coin))

(defn subscribe-orderbook [_ store coin]
  (println "Subscribing to orderbook for:" coin)
  (orderbook/subscribe-orderbook! coin))

(defn unsubscribe-orderbook [_ store coin]
  (println "Unsubscribing from orderbook for:" coin)
  (orderbook/unsubscribe-orderbook! coin)
  (swap! store update-in [:orderbooks] dissoc coin))

(defn subscribe-webdata2 [_ store address]
  (println "Subscribing to WebData2 for address:" address)
  (webdata2/subscribe-webdata2! address))

(defn unsubscribe-webdata2 [_ store address]
  (println "Unsubscribing from WebData2 for address:" address)
  (webdata2/unsubscribe-webdata2! address))

(defn connect-wallet [_ store]
  (println "Connecting wallet...")
  (wallet/request-connection! store))

(defn init-websockets [state]
  [[:effects/init-websocket]])

(defn subscribe-to-asset [state coin]
  [[:effects/subscribe-active-asset coin]
   [:effects/subscribe-orderbook coin]])

(defn subscribe-to-webdata2 [state address]
  [[:effects/subscribe-webdata2 address]])

(defn connect-wallet-action [state]
  [[:effects/connect-wallet]])

(defn toggle-asset-dropdown [state coin]
  (let [current-dropdown (get-in state [:asset-selector :visible-dropdown])]
    [[:effects/save [:asset-selector :visible-dropdown] 
      (if (= current-dropdown coin) nil coin)]]))

(defn close-asset-dropdown [state]
  [[:effects/save [:asset-selector :visible-dropdown] nil]])

(defn select-asset [state coin]
  (let [current-asset (get-in state [:active-asset])
        unsubscribe-effects (if current-asset
                             [[:effects/unsubscribe-active-asset current-asset]
                              [:effects/unsubscribe-orderbook current-asset]]
                             [])
        subscribe-effects [[:effects/subscribe-active-asset coin]
                          [:effects/subscribe-orderbook coin]
                          [:effects/save [:selected-asset] coin]
                          [:effects/save [:active-asset] coin]
                          [:effects/save [:asset-selector :visible-dropdown] nil]
                          [:effects/fetch-candle-snapshot]]]
    (into unsubscribe-effects subscribe-effects)))

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

(def open-orders-sortable-columns
  #{"Time" "Type" "Coin" "Direction" "Size" "Original Size" "Order Value" "Price"})

(def open-orders-sort-directions
  #{:asc :desc})

(defn restore-open-orders-sort-settings! [store]
  (let [stored-column (or (js/localStorage.getItem "open-orders-sort-by") "Time")
        stored-direction (keyword (or (js/localStorage.getItem "open-orders-sort-direction") "desc"))
        column (if (contains? open-orders-sortable-columns stored-column)
                 stored-column
                 "Time")
        direction (if (contains? open-orders-sort-directions stored-direction)
                    stored-direction
                    :desc)]
    (swap! store update-in [:account-info] merge {:open-orders-sort {:column column
                                                                     :direction direction}})))

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

(defn select-account-info-tab [state tab]
  [[:effects/save [:account-info :selected-tab] tab]])

(defn sort-positions [state column]
  (let [current-sort (get-in state [:account-info :positions-sort])
        current-column (:column current-sort)
        current-direction (:direction current-sort)
        new-direction (if (and (= current-column column) (= current-direction :asc))
                       :desc
                       :asc)]
    [[:effects/save [:account-info :positions-sort] {:column column :direction new-direction}]]))

(defn sort-balances [state column]
  (let [current-sort (get-in state [:account-info :balances-sort])
        current-column (:column current-sort)
        current-direction (:direction current-sort)
        new-direction (if (and (= current-column column) (= current-direction :asc))
                        :desc
                        :asc)]
    [[:effects/save [:account-info :balances-sort] {:column column :direction new-direction}]]))

(defn sort-open-orders [state column]
  (let [current-sort (get-in state [:account-info :open-orders-sort])
        current-column (:column current-sort)
        current-direction (:direction current-sort)
        new-direction (if (= current-column column)
                        (if (= current-direction :asc) :desc :asc)
                        (if (contains? #{"Time" "Size" "Original Size" "Order Value" "Price"} column)
                          :desc
                          :asc))]
    (js/localStorage.setItem "open-orders-sort-by" column)
    (js/localStorage.setItem "open-orders-sort-direction" (name new-direction))
    [[:effects/save [:account-info :open-orders-sort] {:column column
                                                       :direction new-direction}]]))

(defn set-hide-small-balances [state checked]
  [[:effects/save [:account-info :hide-small-balances?] checked]])

(defn update-order-form [state path value]
  (let [v (cond
            (= path [:type]) (keyword value)
            (= path [:side]) (keyword value)
            (= path [:tif]) (keyword value)
            :else value)]
    [[:effects/save (into [:order-form] path) v]
     [:effects/save [:order-form :error] nil]]))

(defn submit-order [state]
  (let [form (:order-form state)
        market-form (when (= :market (:type form))
                      (trading/apply-market-price state form))
        form* (if market-form market-form form)
        errors (trading/validate-order-form form*)
        request (trading/build-order-request state form*)]
    (cond
      (and (= :market (:type form)) (nil? market-form))
      [[:effects/save [:order-form :error] "Market price unavailable. Load order book first."]]
      (seq errors) [[:effects/save [:order-form :error] (first errors)]]
      (nil? request) [[:effects/save [:order-form :error] "Select an asset and ensure market data is loaded."]]
      :else [[:effects/save [:order-form :error] nil]
             [:effects/save [:order-form] form*]
             [:effects/api-submit-order request]])))

(defn cancel-order [state order]
  (let [coin (:coin order)
        oid (:oid order)
        asset-idx (get-in state [:asset-contexts (keyword coin) :idx])]
    (if (and asset-idx oid)
      [[:effects/api-cancel-order {:action {:type "cancel"
                                            :cancels [{:a asset-idx :o oid}]}}]]
      [[:effects/save [:orders :cancel-error] "Missing asset or order id."]])))

(defn load-user-data [state address]
  [[:effects/api-load-user-data address]])

(defn set-funding-modal [state modal]
  [[:effects/save [:funding-ui :modal] modal]])


;; Register effects and actions
(nxr/register-effect! :effects/save save)
(nxr/register-effect! :effects/push-state push-state)
(nxr/register-effect! :effects/replace-state replace-state)
(nxr/register-effect! :effects/init-websocket init-websocket)
(nxr/register-effect! :effects/subscribe-active-asset subscribe-active-asset)
(nxr/register-effect! :effects/subscribe-orderbook subscribe-orderbook)
(nxr/register-effect! :effects/subscribe-webdata2 subscribe-webdata2)
(nxr/register-effect! :effects/fetch-candle-snapshot fetch-candle-snapshot)
(nxr/register-effect! :effects/unsubscribe-active-asset unsubscribe-active-asset)
(nxr/register-effect! :effects/unsubscribe-orderbook unsubscribe-orderbook)
(nxr/register-effect! :effects/unsubscribe-webdata2 unsubscribe-webdata2)
(nxr/register-effect! :effects/connect-wallet connect-wallet)
(nxr/register-effect! :effects/api-submit-order
  (fn [_ store request]
    (let [address (get-in @store [:wallet :address])]
      (cond
        (nil? address)
        (swap! store assoc-in [:order-form :error] "Connect your wallet before submitting.")

        (nil? (.-ethereum js/window))
        (swap! store assoc-in [:order-form :error] "No EVM wallet provider found.")

        :else
        (do
          (swap! store assoc-in [:order-form :submitting?] true)
          (-> (trading-api/submit-order! store address (:action request))
              (.then #(.json %))
              (.then (fn [resp]
                       (swap! store assoc-in [:order-form :submitting?] false)
                       (let [data (js->clj resp :keywordize-keys true)]
                         (if (= "ok" (:status data))
                           (swap! store assoc-in [:order-form :error] nil)
                           (swap! store assoc-in [:order-form :error]
                                  (str (or (:error data) (:response data) data)))))))
              (.catch (fn [err]
                        (swap! store assoc-in [:order-form :submitting?] false)
                        (swap! store assoc-in [:order-form :error] (str err))))))))))

(nxr/register-effect! :effects/api-cancel-order
  (fn [_ store request]
    (let [address (get-in @store [:wallet :address])]
      (cond
        (nil? address)
        (swap! store assoc-in [:orders :cancel-error] "Connect your wallet before cancelling.")

        (nil? (.-ethereum js/window))
        (swap! store assoc-in [:orders :cancel-error] "No EVM wallet provider found.")

        :else
        (-> (trading-api/cancel-order! store address (:action request))
            (.then #(.json %))
            (.then (fn [resp]
                     (swap! store assoc-in [:orders :cancel-error] nil)
                     (swap! store assoc-in [:orders :cancel-response] resp)))
            (.catch (fn [err]
                      (swap! store assoc-in [:orders :cancel-error] (str err)))))))))

(nxr/register-effect! :effects/api-load-user-data
  (fn [_ store address]
    (when address
      (api/fetch-frontend-open-orders! store address)
      (api/fetch-user-fills! store address))))
(nxr/register-action! :actions/init-websockets init-websockets)
(nxr/register-action! :actions/subscribe-to-asset subscribe-to-asset)
(nxr/register-action! :actions/subscribe-to-webdata2 subscribe-to-webdata2)
(nxr/register-action! :actions/connect-wallet connect-wallet-action)
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
(nxr/register-action! :actions/select-account-info-tab select-account-info-tab)
(nxr/register-action! :actions/sort-positions sort-positions)
(nxr/register-action! :actions/sort-balances sort-balances)
(nxr/register-action! :actions/sort-open-orders sort-open-orders)
(nxr/register-action! :actions/set-hide-small-balances set-hide-small-balances)
(nxr/register-action! :actions/update-order-form update-order-form)
(nxr/register-action! :actions/submit-order submit-order)
(nxr/register-action! :actions/cancel-order cancel-order)
(nxr/register-action! :actions/load-user-data load-user-data)
(nxr/register-action! :actions/set-funding-modal set-funding-modal)
(nxr/register-action! :actions/navigate
  (fn [state path & [opts]]
    (let [p (router/normalize-path path)
          replace? (boolean (:replace? opts))]
      (cond-> [[:effects/save [:router :path] p]]
        replace? (conj [:effects/replace-state p])
        (not replace?) (conj [:effects/push-state p])))))
(nxr/register-system->state! deref)

;; Register placeholder for DOM event values
(nxr/register-placeholder! :event.target/value
  (fn [{:replicant/keys [dom-event]}]
    (some-> dom-event .-target .-value)))

(nxr/register-placeholder! :event.target/checked
  (fn [{:replicant/keys [dom-event]}]
    (some-> dom-event .-target .-checked)))

;; Wire up the render loop
(r/set-dispatch! #(nxr/dispatch store %1 %2))
(add-watch store ::render #(r/render (.getElementById js/document "app") (app-view/app-view %4)))

;; Watch for WebSocket connection status changes
(add-watch ws-client/connection-state ::ws-status
  (fn [_ _ _ new-state]
    ;; Defer store update to next tick to avoid nested renders
    (js/queueMicrotask #(swap! store assoc-in [:websocket :status] (:status new-state)))
    ;; Notify address watcher of connection status changes
    (if (= (:status new-state) :connected)
      (address-watcher/on-websocket-connected!)
      (address-watcher/on-websocket-disconnected!))))

(defn reload []
  (println "Reloading Hyperopen...")
  (r/render (.getElementById js/document "app") (app-view/app-view @store)))

(defn initialize-remote-data-streams! []
  (println "Initializing remote data streams...")
  ;; initalize websocket client
  (ws-client/init-connection! "wss://api.hyperliquid.xyz/ws")
  ;; Initialize active asset context with store access
  (active-ctx/init! store)
  ;; Initialize orderbook module
  (orderbook/init! store)
  ;; Initialize user websocket handlers
  (user-ws/init! store)
  ;; Initialize WebData2 module
  (webdata2/init! store)
  ;; Note: WebData2 subscriptions are now managed by address-watcher
  (address-watcher/init-with-webdata2! store webdata2/subscribe-webdata2! webdata2/unsubscribe-webdata2!)
  ;; Subscribe user streams when address changes
  (address-watcher/add-handler! (user-ws/create-user-handler user-ws/subscribe-user! user-ws/unsubscribe-user!))
  ;; Fetch initial user data on address change
  (address-watcher/add-handler!
    (reify address-watcher/IAddressChangeHandler
      (on-address-changed [_ _ new-address]
        (when new-address
          (swap! store assoc-in [:orders :open-orders-snapshot-by-dex] {})
          (let [dexs (:perp-dexs @store)]
            (if (seq dexs)
              (do
                (api/fetch-frontend-open-orders! store new-address)
                (doseq [dex dexs]
                  (api/fetch-frontend-open-orders! store new-address dex)))
              (-> (api/fetch-perp-dexs! store)
                  (.then (fn [loaded-dexs]
                           (api/fetch-frontend-open-orders! store new-address)
                           (doseq [dex loaded-dexs]
                             (api/fetch-frontend-open-orders! store new-address dex)))))))
          (api/fetch-user-fills! store new-address)))
      (get-handler-name [_] "user-initial-data-handler")))
  ;; Fetch spot balances on address change
  (address-watcher/add-handler!
    (reify address-watcher/IAddressChangeHandler
      (on-address-changed [_ _ new-address]
        (if new-address
          (api/fetch-spot-clearinghouse-state! store new-address)
          (swap! store assoc-in [:spot :clearinghouse-state] nil)))
      (get-handler-name [_] "spot-clearinghouse-handler")))
  ;; Fetch clearinghouse states for all perp DEXes on address change
  (address-watcher/add-handler!
    (reify address-watcher/IAddressChangeHandler
      (on-address-changed [_ _ new-address]
        (if new-address
          (do
            (swap! store assoc-in [:perp-dex-clearinghouse] {})
            (let [dexs (:perp-dexs @store)]
              (if (seq dexs)
                (api/fetch-perp-dex-clearinghouse-states! store new-address dexs)
                (-> (api/fetch-perp-dexs! store)
                    (.then (fn [loaded-dexs]
                             (api/fetch-perp-dex-clearinghouse-states! store new-address loaded-dexs)))))))
          (swap! store assoc-in [:perp-dex-clearinghouse] {})))
      (get-handler-name [_] "perp-dex-clearinghouse-handler")))
  ;; Fetch initial market data
  (api/fetch-asset-contexts! store)
  (api/fetch-perp-dexs! store)
  (api/fetch-spot-meta! store))

(defn init []
  (println "Initializing Hyperopen...")
  ;; Restore asset selector sort settings from localStorage
  (asset-selector-settings/restore-asset-selector-sort-settings! store)
  ;; Restore open orders sort settings from localStorage
  (restore-open-orders-sort-settings! store)
  ;; Initialize wallet system
  (wallet/init-wallet! store)
  ;; Initialize router
  (router/init! store)
  ;; Initialize remote data streams
  (initialize-remote-data-streams!)
  ;; Trigger initial render by updating the store
  (swap! store identity))
