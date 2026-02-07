(ns hyperopen.core
  (:require [clojure.string :as str]
            [replicant.dom :as r]
            [nexus.registry :as nxr]
            [hyperopen.views.app-view :as app-view]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.orderbook :as orderbook]
            [hyperopen.websocket.trades :as trades]
            [hyperopen.websocket.webdata2 :as webdata2]
            [hyperopen.websocket.user :as user-ws]
            [hyperopen.api :as api]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.asset-selector.settings :as asset-selector-settings]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.orderbook.price-aggregation :as price-agg]
            [hyperopen.wallet.core :as wallet]
            [hyperopen.wallet.address-watcher :as address-watcher]
            [hyperopen.router :as router]
            [hyperopen.state.trading :as trading]))

;; App state
(defonce store (atom {:websocket {:status :disconnected
                                  :attempt 0
                                  :next-retry-at-ms nil
                                  :last-close nil
                                  :last-activity-at-ms nil
                                  :queue-size 0}
                      :active-assets {:contexts {}
                                     :loading false}
                      :active-asset nil
                      :active-market nil
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
                     				  :sort-direction :desc
                                      :markets []
                                      :market-by-key {}
                                      :loading? false
                                      :phase :bootstrap
                                      :loaded-at-ms nil
                                      :favorites #{}
                                      :missing-icons #{}
                                      :favorites-only? false
                                      :strict? false
                                      :active-tab :all}
                      :chart-options {:timeframes-dropdown-visible false
                                      :selected-timeframe :1d
                                      :chart-type-dropdown-visible false
                                      :selected-chart-type :candlestick}
                      :orderbook-ui {:size-unit :base
                                     :size-unit-dropdown-visible? false
                                     :price-aggregation-dropdown-visible? false
                                     :price-aggregation-by-coin {}
                                     :active-tab :orderbook}
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

(defn save-many [_ store path-values]
  (swap! store
         (fn [state]
           (reduce (fn [acc [path value]]
                     (assoc-in acc path value))
                   state
                   path-values))))

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
  (let [market-by-key (get-in @store [:asset-selector :market-by-key] {})
        market (markets/resolve-market-by-coin market-by-key coin)
        canonical-coin (or (:coin market) coin)]
    (when (string? canonical-coin)
      (js/localStorage.setItem "active-asset" canonical-coin))
  (swap! store
         (fn [state]
           (let [market (or market
                            (markets/resolve-market-by-coin
                             (get-in state [:asset-selector :market-by-key] {})
                             canonical-coin))]
             (-> state
                 (assoc-in [:active-assets :loading] true)
                 (assoc-in [:active-asset] canonical-coin)
                 (assoc-in [:selected-asset] canonical-coin)
                 (assoc :active-market (or market (:active-market state)))))))
  (active-ctx/subscribe-active-asset-ctx! canonical-coin)
  (fetch-candle-snapshot _ store :interval (get-in @store [:chart-options :selected-timeframe] :1d))))

(defn unsubscribe-active-asset [_ store coin]
  (println "Unsubscribing from active asset context for:" coin)
  (active-ctx/unsubscribe-active-asset-ctx! coin)
  (swap! store update-in [:active-assets :contexts] dissoc coin))

(defn subscribe-orderbook [_ store coin]
  (println "Subscribing to orderbook for:" coin)
  (let [selected-mode (get-in @store [:orderbook-ui :price-aggregation-by-coin coin] :full)
        mode (price-agg/normalize-mode selected-mode)
        aggregation-config (price-agg/mode->subscription-config mode)]
    (orderbook/subscribe-orderbook! coin aggregation-config)))

(defn subscribe-trades [_ store coin]
  (println "Subscribing to trades for:" coin)
  (trades/subscribe-trades! coin))

(defn unsubscribe-orderbook [_ store coin]
  (println "Unsubscribing from orderbook for:" coin)
  (orderbook/unsubscribe-orderbook! coin)
  (swap! store update-in [:orderbooks] dissoc coin))

(defn unsubscribe-trades [_ store coin]
  (println "Unsubscribing from trades for:" coin)
  (trades/unsubscribe-trades! coin))

(defn subscribe-webdata2 [_ store address]
  (println "Subscribing to WebData2 for address:" address)
  (webdata2/subscribe-webdata2! address))

(defn unsubscribe-webdata2 [_ store address]
  (println "Unsubscribing from WebData2 for address:" address)
  (webdata2/unsubscribe-webdata2! address))

(defn connect-wallet [_ store]
  (println "Connecting wallet...")
  (wallet/request-connection! store))

(defn reconnect-websocket [_ _]
  (println "Forcing WebSocket reconnect...")
  (ws-client/force-reconnect!))

(defn init-websockets [state]
  [[:effects/init-websocket]])

(defn subscribe-to-asset [state coin]
  [[:effects/subscribe-active-asset coin]
   [:effects/subscribe-orderbook coin]
   [:effects/subscribe-trades coin]])

(defn subscribe-to-webdata2 [state address]
  [[:effects/subscribe-webdata2 address]])

(defn connect-wallet-action [state]
  [[:effects/connect-wallet]])

(defn reconnect-websocket-action [state]
  [[:effects/reconnect-websocket]])

(defn toggle-asset-dropdown [state coin]
  (let [current-dropdown (get-in state [:asset-selector :visible-dropdown])]
    (let [next-dropdown (if (= current-dropdown coin) nil coin)
          should-refresh? (and (= coin :asset-selector)
                               (some? next-dropdown)
                               (or (empty? (get-in state [:asset-selector :markets]))
                                   (not= :full (get-in state [:asset-selector :phase]))))
          effects [[:effects/save [:asset-selector :visible-dropdown] next-dropdown]]]
      (cond-> effects
        should-refresh?
        (conj [:effects/fetch-asset-selector-markets])))))

(defn close-asset-dropdown [state]
  [[:effects/save [:asset-selector :visible-dropdown] nil]])

(defn select-asset [state market-or-coin]
  (let [market-by-key (get-in state [:asset-selector :market-by-key] {})
        input-coin (cond
                     (map? market-or-coin) (:coin market-or-coin)
                     (string? market-or-coin) market-or-coin
                     :else nil)
        market (cond
                 (map? market-or-coin)
                 (or (markets/resolve-market-by-coin market-by-key input-coin)
                     market-or-coin)

                 (string? market-or-coin)
                 (markets/resolve-market-by-coin market-by-key market-or-coin)

                 :else nil)
        coin (or (:coin market) input-coin)
        resolved-market (or market
                            (when (string? coin)
                              (markets/resolve-market-by-coin market-by-key coin)))
        canonical-coin (or (:coin resolved-market) coin)
        current-asset (get-in state [:active-asset])
        immediate-ui-effects [[:effects/save-many [[[:asset-selector :visible-dropdown] nil]
                                                   [[:orderbook-ui :price-aggregation-dropdown-visible?] false]
                                                   [[:orderbook-ui :size-unit-dropdown-visible?] false]
                                                   [[:active-market] resolved-market]]]]
        unsubscribe-effects (if current-asset
                             [[:effects/unsubscribe-active-asset current-asset]
                              [:effects/unsubscribe-orderbook current-asset]
                              [:effects/unsubscribe-trades current-asset]]
                             [])
        subscribe-effects [[:effects/subscribe-active-asset canonical-coin]
                           [:effects/subscribe-orderbook canonical-coin]
                           [:effects/subscribe-trades canonical-coin]]]
    (into immediate-ui-effects
          (into unsubscribe-effects subscribe-effects))))

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

(defn toggle-asset-selector-strict [state]
  (let [new-value (not (get-in state [:asset-selector :strict?] false))]
    (js/localStorage.setItem "asset-selector-strict" (str new-value))
    [[:effects/save [:asset-selector :strict?] new-value]]))

(defn toggle-asset-favorite [state market-key]
  (let [favorites (get-in state [:asset-selector :favorites] #{})
        new-favorites (if (contains? favorites market-key)
                        (disj favorites market-key)
                        (conj favorites market-key))]
    (js/localStorage.setItem "asset-selector-favorites"
                             (js/JSON.stringify (clj->js (vec new-favorites))))
    [[:effects/save [:asset-selector :favorites] new-favorites]]))

(defn set-asset-selector-favorites-only [state enabled?]
  [[:effects/save [:asset-selector :favorites-only?] (boolean enabled?)]])

(defn set-asset-selector-tab [state tab]
  (js/localStorage.setItem "asset-selector-active-tab" (name tab))
  [[:effects/save [:asset-selector :active-tab] tab]])

(defn refresh-asset-markets [state]
  [[:effects/fetch-asset-selector-markets]])

(defn mark-missing-asset-icon [state market-key]
  (if (seq market-key)
    (let [missing (get-in state [:asset-selector :missing-icons] #{})
          updated (conj missing market-key)]
      [[:effects/save [:asset-selector :missing-icons] updated]])
    []))

(def open-orders-sortable-columns
  #{"Time" "Type" "Coin" "Direction" "Size" "Original Size" "Order Value" "Price"})

(def open-orders-sort-directions
  #{:asc :desc})

(def chart-timeframes
  #{:1m :3m :5m :15m :30m :1h :2h :4h :8h :12h :1d :3d :1w :1M})

(def chart-types
  #{:area :bar :baseline :candlestick :histogram :line})

(def orderbook-size-units
  #{:base :quote})

(def orderbook-tabs
  #{:orderbook :trades})

(defn- load-chart-option
  [ls-key default valid-set]
  (let [v (keyword (or (js/localStorage.getItem ls-key) (name default)))]
    (if (contains? valid-set v) v default)))

(defn- load-orderbook-size-unit []
  (let [v (keyword (or (js/localStorage.getItem "orderbook-size-unit") "base"))]
    (if (contains? orderbook-size-units v) v :base)))

(defn- load-orderbook-active-tab []
  (let [v (keyword (or (js/localStorage.getItem "orderbook-active-tab") "orderbook"))]
    (if (contains? orderbook-tabs v) v :orderbook)))

(defn- normalize-price-aggregation-by-coin [raw-map]
  (if (map? raw-map)
    (into {}
          (keep (fn [[coin raw-mode]]
                  (let [mode (cond
                               (keyword? raw-mode) raw-mode
                               (string? raw-mode) (keyword raw-mode)
                               :else nil)]
                    (when (and (string? coin)
                               (seq coin)
                               (contains? price-agg/valid-modes mode))
                      [coin mode]))))
          raw-map)
    {}))

(defn- load-orderbook-price-aggregation-by-coin []
  (try
    (let [raw (js/localStorage.getItem "orderbook-price-aggregation-by-coin")]
      (if (seq raw)
        (normalize-price-aggregation-by-coin (js->clj (js/JSON.parse raw)))
        {}))
    (catch :default _
      {})))

(defn- persist-orderbook-price-aggregation-by-coin! [by-coin]
  (try
    (let [normalized (normalize-price-aggregation-by-coin by-coin)]
      (js/localStorage.setItem "orderbook-price-aggregation-by-coin"
                               (js/JSON.stringify (clj->js normalized))))
    (catch :default e
      (js/console.warn "Failed to persist orderbook price aggregation settings:" e))))

(defn- serialize-indicators [indicators]
  (into {}
        (map (fn [[k v]] [(name k) v]))
        (or indicators {})))

(defn- persist-indicators! [indicators]
  (try
    (js/localStorage.setItem "chart-active-indicators"
                             (js/JSON.stringify (clj->js (serialize-indicators indicators))))
    (catch :default e
      (js/console.warn "Failed to persist chart indicators:" e))))

(defn- load-indicators []
  (try
    (let [raw (js/localStorage.getItem "chart-active-indicators")]
      (if (seq raw)
        (let [parsed (js->clj (js/JSON.parse raw) :keywordize-keys true)]
          (if (map? parsed) parsed {}))
        {}))
    (catch :default _
      {})))

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

(defn restore-chart-options! [store]
  (let [timeframe (load-chart-option "chart-timeframe" :1d chart-timeframes)
        chart-type (load-chart-option "chart-type" :candlestick chart-types)
        indicators (load-indicators)]
    (swap! store update-in [:chart-options] merge
           {:selected-timeframe timeframe
            :selected-chart-type chart-type
            :active-indicators indicators})))

(defn restore-orderbook-ui! [store]
  (swap! store update :orderbook-ui merge
         {:size-unit (load-orderbook-size-unit)
          :price-aggregation-by-coin (load-orderbook-price-aggregation-by-coin)
          :active-tab (load-orderbook-active-tab)}))

(defn restore-active-asset! [store]
  (when (nil? (:active-asset @store))
    (let [stored-asset (js/localStorage.getItem "active-asset")
          asset (if (seq stored-asset) stored-asset "BTC")]
      (swap! store assoc :active-asset asset :selected-asset asset)
      (when-not (seq stored-asset)
        (js/localStorage.setItem "active-asset" asset))
      (when (ws-client/connected?)
        (nxr/dispatch store nil [[:actions/subscribe-to-asset asset]])))))

(defn toggle-timeframes-dropdown [state]
  (let [current-visible (get-in state [:chart-options :timeframes-dropdown-visible])]
    [[:effects/save [:chart-options :timeframes-dropdown-visible] (not current-visible)]]))

(defn select-chart-timeframe [state timeframe]
  (js/localStorage.setItem "chart-timeframe" (name timeframe))
  [[:effects/save [:chart-options :selected-timeframe] timeframe]
   [:effects/save [:chart-options :timeframes-dropdown-visible] false]
   [:effects/fetch-candle-snapshot :interval timeframe]])

(defn toggle-chart-type-dropdown [state]
  (let [current-visible (get-in state [:chart-options :chart-type-dropdown-visible])]
    [[:effects/save [:chart-options :chart-type-dropdown-visible] (not current-visible)]]))

(defn select-chart-type [state chart-type]
  (js/localStorage.setItem "chart-type" (name chart-type))
  [[:effects/save [:chart-options :selected-chart-type] chart-type]
   [:effects/save [:chart-options :chart-type-dropdown-visible] false]])

(defn toggle-indicators-dropdown [state]
  (let [current-visible (get-in state [:chart-options :indicators-dropdown-visible])]
    [[:effects/save [:chart-options :indicators-dropdown-visible] (not current-visible)]]))

(defn toggle-orderbook-size-unit-dropdown [state]
  (let [visible? (get-in state [:orderbook-ui :size-unit-dropdown-visible?] false)]
    [[:effects/save [:orderbook-ui :size-unit-dropdown-visible?] (not visible?)]]))

(defn select-orderbook-size-unit [state unit]
  (let [size-unit (if (= unit :quote) :quote :base)]
    (js/localStorage.setItem "orderbook-size-unit" (name size-unit))
    [[:effects/save [:orderbook-ui :size-unit] size-unit]
     [:effects/save [:orderbook-ui :size-unit-dropdown-visible?] false]]))

(defn toggle-orderbook-price-aggregation-dropdown [state]
  (let [visible? (get-in state [:orderbook-ui :price-aggregation-dropdown-visible?] false)]
    [[:effects/save [:orderbook-ui :price-aggregation-dropdown-visible?] (not visible?)]]))

(defn select-orderbook-price-aggregation [state mode]
  (let [coin (:active-asset state)
        mode* (price-agg/normalize-mode mode)
        current-by-coin (get-in state [:orderbook-ui :price-aggregation-by-coin] {})
        next-by-coin (if (seq coin)
                       (assoc current-by-coin coin mode*)
                       current-by-coin)]
    (persist-orderbook-price-aggregation-by-coin! next-by-coin)
    (cond-> [[:effects/save [:orderbook-ui :price-aggregation-by-coin] next-by-coin]
             [:effects/save [:orderbook-ui :price-aggregation-dropdown-visible?] false]]
      (seq coin)
      (conj [:effects/subscribe-orderbook coin]))))

(defn select-orderbook-tab [state tab]
  (let [tab* (cond
               (keyword? tab) tab
               (string? tab) (keyword tab)
               :else :orderbook)
        normalized-tab (if (contains? orderbook-tabs tab*) tab* :orderbook)]
    (js/localStorage.setItem "orderbook-active-tab" (name normalized-tab))
    [[:effects/save [:orderbook-ui :active-tab] normalized-tab]
     [:effects/save [:orderbook-ui :size-unit-dropdown-visible?] false]
     [:effects/save [:orderbook-ui :price-aggregation-dropdown-visible?] false]]))

(defn add-indicator [state indicator-type params]
  (let [current-indicators (get-in state [:chart-options :active-indicators] {})
        new-indicators (assoc current-indicators indicator-type params)]
    (persist-indicators! new-indicators)
    [[:effects/save [:chart-options :active-indicators] new-indicators]]))

(defn remove-indicator [state indicator-type]
  (let [current-indicators (get-in state [:chart-options :active-indicators] {})
        new-indicators (dissoc current-indicators indicator-type)]
    (persist-indicators! new-indicators)
    [[:effects/save [:chart-options :active-indicators] new-indicators]]))

(defn update-indicator-period [state indicator-type period-value]
  (let [current-indicators (get-in state [:chart-options :active-indicators] {})
        period (js/parseInt period-value)
        updated-indicators (assoc-in current-indicators [indicator-type :period] period)]
    (persist-indicators! updated-indicators)
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

(defn- normalize-order-entry-mode [mode]
  (let [candidate (cond
                    (keyword? mode) mode
                    (string? mode) (keyword mode)
                    :else :market)]
    (if (contains? #{:market :limit :pro} candidate)
      candidate
      :market)))

(defn select-order-entry-mode [state mode]
  (let [mode* (normalize-order-entry-mode mode)
        form (:order-form state)
        next-type (case mode*
                    :market :market
                    :limit :limit
                    (trading/normalize-pro-order-type (:type form)))
        next-form (-> form
                      (assoc :type next-type)
                      (trading/sync-size-from-percent state)
                      (assoc :error nil))]
    [[:effects/save-many [[[:order-form] next-form]]]]))

(defn select-pro-order-type [state order-type]
  (let [form (:order-form state)
        next-type (trading/normalize-pro-order-type order-type)
        next-form (-> form
                      (assoc :type next-type)
                      (trading/sync-size-from-percent state)
                      (assoc :error nil))]
    [[:effects/save-many [[[:order-form] next-form]]]]))

(defn set-order-ui-leverage [state leverage]
  (let [form (:order-form state)
        normalized (trading/normalize-ui-leverage state leverage)
        next-form (-> form
                      (assoc :ui-leverage normalized)
                      (trading/sync-size-from-percent state)
                      (assoc :error nil))]
    [[:effects/save-many [[[:order-form] next-form]]]]))

(defn set-order-size-percent [state percent]
  (let [form (:order-form state)
        next-form (-> (trading/apply-size-percent state form percent)
                      (assoc :error nil))]
    [[:effects/save-many [[[:order-form] next-form]]]]))

(defn set-order-size-display [state value]
  (let [raw-value (str (or value ""))
        form (:order-form state)
        normalized-form (trading/normalize-order-form state form)
        reference-price (trading/reference-price state normalized-form)
        parsed-display-size (trading/parse-num raw-value)
        canonical-size (when (and (number? parsed-display-size)
                                  (pos? parsed-display-size)
                                  (number? reference-price)
                                  (pos? reference-price))
                         (trading/base-size-string state (/ parsed-display-size reference-price)))
        updated (assoc form
                       :size-display raw-value
                       :size (or canonical-size ""))
        next-form (cond
                    (str/blank? raw-value)
                    (assoc updated :size "" :size-percent 0)

                    (seq canonical-size)
                    (trading/sync-size-percent-from-size state updated)

                    :else
                    (assoc updated :size-percent 0))
        next-form* (assoc next-form :error nil)]
    [[:effects/save-many [[[:order-form] next-form*]]]]))

(defn set-order-price-to-mid [state]
  (let [form (:order-form state)
        normalized-form (trading/normalize-order-form state form)
        mid-price-string (trading/mid-price-string state normalized-form)
        updated (if (seq mid-price-string)
                  (assoc form :price mid-price-string)
                  form)
        next-form (if (and (seq mid-price-string)
                           (pos? (or (trading/parse-num (:size-percent updated)) 0)))
                    (trading/sync-size-from-percent state updated)
                    updated)
        next-form* (assoc next-form :error nil)]
    [[:effects/save-many [[[:order-form] next-form*]]]]))

(defn toggle-order-tpsl-panel [state]
  (let [form (:order-form state)
        next-open? (not (boolean (:tpsl-panel-open? form)))
        next-form (cond-> (assoc form :tpsl-panel-open? next-open?)
                    (not next-open?) (assoc-in [:tp :enabled?] false)
                    (not next-open?) (assoc-in [:sl :enabled?] false))
        next-form* (assoc next-form :error nil)]
    [[:effects/save-many [[[:order-form] next-form*]]]]))

(defn update-order-form [state path value]
  (let [v (cond
            (= path [:type]) (keyword value)
            (= path [:side]) (keyword value)
            (= path [:tif]) (keyword value)
            :else value)
        form (:order-form state)
        updated (assoc-in form path v)
        next-form (cond
                    (= path [:type])
                    (-> updated
                        (update :type trading/normalize-order-type)
                        (trading/sync-size-from-percent state))

                    (= path [:size])
                    (trading/sync-size-percent-from-size state updated)

                    (or (= path [:price]) (= path [:side]))
                    (if (pos? (or (trading/parse-num (:size-percent updated)) 0))
                      (trading/sync-size-from-percent state updated)
                      updated)

                    :else
                    updated)
        next-form* (assoc next-form :error nil)]
    [[:effects/save-many [[[:order-form] next-form*]]]]))

(defn submit-order [state]
  (let [form (trading/normalize-order-form state (:order-form state))
        active-market (:active-market state)
        active-asset (:active-asset state)
        inferred-spot? (and (string? active-asset) (str/includes? active-asset "/"))
        inferred-hip3? (and (string? active-asset) (str/includes? active-asset ":") (not inferred-spot?))
        spot? (or (= :spot (:market-type active-market)) inferred-spot?)
        hip3? (or (:dex active-market) inferred-hip3?)
        market-form (when (= :market (:type form))
                      (trading/apply-market-price state form))
        form-with-market (if market-form market-form form)
        form* (if (and (trading/limit-like-type? (:type form-with-market))
                       (str/blank? (:price form-with-market)))
                (if-let [fallback-price (trading/effective-limit-price-string state form-with-market)]
                  (assoc form-with-market :price fallback-price)
                  form-with-market)
                form-with-market)
        errors (trading/validate-order-form form*)
        request (trading/build-order-request state form*)]
    (cond
      spot?
      [[:effects/save [:order-form :error] "Spot trading is not supported yet."]]

      hip3?
      [[:effects/save [:order-form :error] "HIP-3 trading is not supported yet."]]

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
(nxr/register-effect! :effects/save-many save-many)
(nxr/register-effect! :effects/push-state push-state)
(nxr/register-effect! :effects/replace-state replace-state)
(nxr/register-effect! :effects/init-websocket init-websocket)
(nxr/register-effect! :effects/subscribe-active-asset subscribe-active-asset)
(nxr/register-effect! :effects/subscribe-orderbook subscribe-orderbook)
(nxr/register-effect! :effects/subscribe-trades subscribe-trades)
(nxr/register-effect! :effects/subscribe-webdata2 subscribe-webdata2)
(nxr/register-effect! :effects/fetch-candle-snapshot fetch-candle-snapshot)
(nxr/register-effect! :effects/unsubscribe-active-asset unsubscribe-active-asset)
(nxr/register-effect! :effects/unsubscribe-orderbook unsubscribe-orderbook)
(nxr/register-effect! :effects/unsubscribe-trades unsubscribe-trades)
(nxr/register-effect! :effects/unsubscribe-webdata2 unsubscribe-webdata2)
(nxr/register-effect! :effects/connect-wallet connect-wallet)
(nxr/register-effect! :effects/reconnect-websocket reconnect-websocket)
(nxr/register-effect! :effects/fetch-asset-selector-markets
  (fn [_ store & [opts]]
    (api/fetch-asset-selector-markets! store (or opts {:phase :full}))))
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
(nxr/register-action! :actions/reconnect-websocket reconnect-websocket-action)
(nxr/register-action! :actions/toggle-asset-dropdown toggle-asset-dropdown)
(nxr/register-action! :actions/close-asset-dropdown close-asset-dropdown)
(nxr/register-action! :actions/select-asset select-asset)
(nxr/register-action! :actions/update-asset-search update-asset-search)
(nxr/register-action! :actions/update-asset-selector-sort update-asset-selector-sort)
(nxr/register-action! :actions/toggle-asset-selector-strict toggle-asset-selector-strict)
(nxr/register-action! :actions/toggle-asset-favorite toggle-asset-favorite)
(nxr/register-action! :actions/set-asset-selector-favorites-only set-asset-selector-favorites-only)
(nxr/register-action! :actions/set-asset-selector-tab set-asset-selector-tab)
(nxr/register-action! :actions/refresh-asset-markets refresh-asset-markets)
(nxr/register-action! :actions/mark-missing-asset-icon mark-missing-asset-icon)
(nxr/register-action! :actions/toggle-timeframes-dropdown toggle-timeframes-dropdown)
(nxr/register-action! :actions/select-chart-timeframe select-chart-timeframe)
(nxr/register-action! :actions/toggle-chart-type-dropdown toggle-chart-type-dropdown)
(nxr/register-action! :actions/select-chart-type select-chart-type)
(nxr/register-action! :actions/toggle-indicators-dropdown toggle-indicators-dropdown)
(nxr/register-action! :actions/toggle-orderbook-size-unit-dropdown toggle-orderbook-size-unit-dropdown)
(nxr/register-action! :actions/select-orderbook-size-unit select-orderbook-size-unit)
(nxr/register-action! :actions/toggle-orderbook-price-aggregation-dropdown toggle-orderbook-price-aggregation-dropdown)
(nxr/register-action! :actions/select-orderbook-price-aggregation select-orderbook-price-aggregation)
(nxr/register-action! :actions/select-orderbook-tab select-orderbook-tab)
(nxr/register-action! :actions/add-indicator add-indicator)
(nxr/register-action! :actions/remove-indicator remove-indicator)
(nxr/register-action! :actions/update-indicator-period update-indicator-period)
(nxr/register-action! :actions/select-account-info-tab select-account-info-tab)
(nxr/register-action! :actions/sort-positions sort-positions)
(nxr/register-action! :actions/sort-balances sort-balances)
(nxr/register-action! :actions/sort-open-orders sort-open-orders)
(nxr/register-action! :actions/set-hide-small-balances set-hide-small-balances)
(nxr/register-action! :actions/select-order-entry-mode select-order-entry-mode)
(nxr/register-action! :actions/select-pro-order-type select-pro-order-type)
(nxr/register-action! :actions/set-order-ui-leverage set-order-ui-leverage)
(nxr/register-action! :actions/set-order-size-percent set-order-size-percent)
(nxr/register-action! :actions/set-order-size-display set-order-size-display)
(nxr/register-action! :actions/set-order-price-to-mid set-order-price-to-mid)
(nxr/register-action! :actions/toggle-order-tpsl-panel toggle-order-tpsl-panel)
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
(when (exists? js/document)
  (add-watch store ::render #(r/render (.getElementById js/document "app") (app-view/app-view %4))))

;; Watch for WebSocket connection status changes
(add-watch ws-client/connection-state ::ws-status
  (fn [_ _ old-state new-state]
    (let [old-status (:status old-state)
          new-status (:status new-state)]
      ;; Defer store update to next tick to avoid nested renders.
      ;; Exclude :last-activity-at-ms so high-frequency message flow does not trigger
      ;; unnecessary app-wide renders.
      (js/queueMicrotask
        #(swap! store assoc :websocket
                (select-keys new-state [:status
                                        :attempt
                                        :next-retry-at-ms
                                        :last-close
                                        :queue-size])))
      ;; Notify address watcher only on status transitions.
      (when (not= old-status new-status)
        (if (= new-status :connected)
          (address-watcher/on-websocket-connected!)
          (address-watcher/on-websocket-disconnected!))))))

(defn reload []
  (println "Reloading Hyperopen...")
  (when (exists? js/document)
    (r/render (.getElementById js/document "app") (app-view/app-view @store))))

(def ^:private deferred-bootstrap-delay-ms 1200)
(def ^:private per-dex-stagger-ms 120)

(defonce ^:private startup-runtime
  (atom {:deferred-scheduled? false
         :bootstrapped-address nil
         :summary-logged? false}))

(defn- mark-performance!
  [mark-name]
  (when (and (exists? js/performance)
             (some? (.-mark js/performance)))
    (try
      (.mark js/performance mark-name)
      (catch :default _
        nil))))

(defn- schedule-idle-or-timeout!
  [f]
  (if (and (exists? js/window)
           (some? (.-requestIdleCallback js/window)))
    (.requestIdleCallback js/window
                          (fn [_]
                            (f))
                          #js {:timeout deferred-bootstrap-delay-ms})
    (js/setTimeout f deferred-bootstrap-delay-ms)))

(defn- schedule-startup-summary-log! []
  (when-not (:summary-logged? @startup-runtime)
    (swap! startup-runtime assoc :summary-logged? true)
    (js/setTimeout
      (fn []
        (let [stats (api/get-request-stats)
              ws-status (get-in @store [:websocket :status])
              selector (select-keys (get @store :asset-selector)
                                    [:loading? :phase :loaded-at-ms])]
          (println "Startup summary (+5s):"
                   (clj->js {:request-stats stats
                             :websocket-status ws-status
                             :asset-selector selector}))))
      5000)))

(defn- stage-b-account-bootstrap!
  [address dexs]
  (doseq [[idx dex] (map-indexed vector (or dexs []))]
    (js/setTimeout
      (fn []
        ;; Guard against stale async callbacks for an old address.
        (when (= address (get-in @store [:wallet :address]))
          (api/fetch-frontend-open-orders! store address dex {:priority :low})
          (api/fetch-clearinghouse-state! store address dex {:priority :low})))
      (* per-dex-stagger-ms (inc idx)))))

(defn- bootstrap-account-data!
  [address]
  (when address
    (when-not (= address (:bootstrapped-address @startup-runtime))
      (swap! startup-runtime assoc :bootstrapped-address address)
      (swap! store assoc-in [:orders :open-orders-snapshot-by-dex] {})
      (swap! store assoc-in [:perp-dex-clearinghouse] {})
      ;; Stage A: critical account data.
      (api/fetch-frontend-open-orders! store address {:priority :high})
      (api/fetch-user-fills! store address {:priority :high})
      (api/fetch-spot-clearinghouse-state! store address {:priority :high})
      ;; Stage B: low-priority, staggered per-dex data.
      (-> (api/ensure-perp-dexs! store {:priority :low})
          (.then (fn [dexs]
                   (stage-b-account-bootstrap! address dexs)))
          (.catch (fn [err]
                    (println "Error bootstrapping per-dex account data:" err)))))))

(defn- install-address-handlers! []
  ;; Note: WebData2 subscriptions are managed by address-watcher.
  (address-watcher/init-with-webdata2! store webdata2/subscribe-webdata2! webdata2/unsubscribe-webdata2!)
  (address-watcher/add-handler! (user-ws/create-user-handler user-ws/subscribe-user! user-ws/unsubscribe-user!))
  (address-watcher/add-handler!
    (reify address-watcher/IAddressChangeHandler
      (on-address-changed [_ _ new-address]
        (if new-address
          (bootstrap-account-data! new-address)
          (do
            (swap! startup-runtime assoc :bootstrapped-address nil)
            (swap! store assoc-in [:orders :open-orders-snapshot-by-dex] {})
            (swap! store assoc-in [:perp-dex-clearinghouse] {})
            (swap! store assoc-in [:spot :clearinghouse-state] nil))))
      (get-handler-name [_] "startup-account-bootstrap-handler")))
  ;; Ensure already-connected wallets are handled after handlers are in place.
  (address-watcher/sync-current-address! store))

(defn- start-critical-bootstrap! []
  (-> (js/Promise.all
        (clj->js [(api/fetch-asset-contexts! store {:priority :high})
                  (api/fetch-asset-selector-markets! store {:phase :bootstrap})]))
      (.finally
        (fn []
          (mark-performance! "app:critical-data:ready")))))

(defn- run-deferred-bootstrap! []
  (-> (api/fetch-asset-selector-markets! store {:phase :full})
      (.finally
        (fn []
          (mark-performance! "app:full-bootstrap:ready")))))

(defn- schedule-deferred-bootstrap! []
  (when-not (:deferred-scheduled? @startup-runtime)
    (swap! startup-runtime assoc :deferred-scheduled? true)
    (schedule-idle-or-timeout! run-deferred-bootstrap!)))

(defn initialize-remote-data-streams! []
  (println "Initializing remote data streams...")
  ;; Initialize websocket client.
  (ws-client/init-connection! "wss://api.hyperliquid.xyz/ws")
  ;; Initialize WebSocket modules.
  (active-ctx/init! store)
  (orderbook/init! store)
  (trades/init! store)
  (user-ws/init! store)
  (webdata2/init! store)
  ;; Ensure active-asset market streams are requested on startup.
  (when-let [asset (:active-asset @store)]
    (nxr/dispatch store nil [[:actions/subscribe-to-asset asset]]))
  (install-address-handlers!)
  (start-critical-bootstrap!)
  (schedule-deferred-bootstrap!))

(defn init []
  (println "Initializing Hyperopen...")
  (reset! startup-runtime {:deferred-scheduled? false
                           :bootstrapped-address nil
                           :summary-logged? false})
  (mark-performance! "app:init:start")
  (schedule-startup-summary-log!)
  ;; Restore asset selector sort settings from localStorage
  (asset-selector-settings/restore-asset-selector-sort-settings! store)
  ;; Restore chart options from localStorage
  (restore-chart-options! store)
  ;; Restore orderbook UI options from localStorage
  (restore-orderbook-ui! store)
  ;; Restore selected asset from localStorage (default to BTC)
  (restore-active-asset! store)
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
