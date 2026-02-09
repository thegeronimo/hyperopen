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
            [hyperopen.websocket.diagnostics-sanitize :as diagnostics-sanitize]
            [hyperopen.api :as api]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.asset-selector.settings :as asset-selector-settings]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.orderbook.price-aggregation :as price-agg]
            [hyperopen.wallet.core :as wallet]
            [hyperopen.wallet.address-watcher :as address-watcher]
            [hyperopen.router :as router]
            [hyperopen.state.trading :as trading]))

(defn- default-funding-history-filters []
  (api/normalize-funding-history-filters {}))

(def ^:private order-history-page-size-options
  #{25 50 100})

(def ^:private default-order-history-page-size
  50)

(defn- default-funding-history-state []
  (let [filters (default-funding-history-filters)]
    {:filters filters
     :draft-filters filters
     :sort {:column "Time" :direction :desc}
     :filter-open? false
     :page-size default-order-history-page-size
     :page 1
     :page-input "1"
     :loading? false
     :error nil
     :request-id 0}))

(defn- parse-int-value
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseInt value 10)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num)))
      (js/Math.floor num))))

(defn- normalize-order-history-page-size
  [value]
  (let [candidate (parse-int-value value)]
    (if (contains? order-history-page-size-options candidate)
      candidate
      default-order-history-page-size)))

(defn- normalize-order-history-page
  ([value]
   (normalize-order-history-page value nil))
  ([value max-page]
   (let [candidate (max 1 (or (parse-int-value value) 1))
         max-page* (when (some? max-page)
                     (max 1 (or (parse-int-value max-page) 1)))]
     (if max-page*
       (min candidate max-page*)
       candidate))))

(defn- default-order-history-state []
  {:sort {:column "Time" :direction :desc}
   :status-filter :all
   :filter-open? false
   :page-size default-order-history-page-size
   :page 1
   :page-input "1"
   :loading? false
   :error nil
   :request-id 0})

(defn- default-trade-history-state []
  {:sort {:column "Time" :direction :desc}
   :page-size default-order-history-page-size
   :page 1
   :page-input "1"})

;; App state
(defonce store (atom {:websocket {:status :disconnected
                                  :attempt 0
                                  :next-retry-at-ms nil
                                  :last-close nil
                                  :last-activity-at-ms nil
                                  :queue-size 0
                                  :health (ws-client/get-health-snapshot)}
                      :websocket-ui {:diagnostics-open? false
                                     :show-market-offline-banner? false
                                     :reveal-sensitive? false
                                     :copy-status nil
                                     :reconnect-cooldown-until-ms nil}
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
                               :fundings-raw []
                               :fundings []
                               :order-history []
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
                                     :open-orders-sort {:column "Time" :direction :desc}
                                     :trade-history (default-trade-history-state)
                                     :funding-history (default-funding-history-state)
                                     :order-history (default-order-history-state)}}))

(defonce ^:private websocket-health-projection-state
  (atom {:second-bucket nil
         :fingerprint nil}))

(defn- websocket-health-fingerprint [health]
  {:transport/state (get-in health [:transport :state])
   :transport/freshness (get-in health [:transport :freshness])
   :groups/orders_oms (get-in health [:groups :orders_oms :worst-status])
   :groups/market_data (get-in health [:groups :market_data :worst-status])
   :groups/account (get-in health [:groups :account :worst-status])})

(defn- sync-websocket-health!
  [store & {:keys [force?]}]
  (let [health (ws-client/get-health-snapshot)
        generated-at-ms (or (:generated-at-ms health) 0)
        second-bucket (quot generated-at-ms 1000)
        diagnostics-open? (boolean (get-in @store [:websocket-ui :diagnostics-open?] false))
        fingerprint (websocket-health-fingerprint health)
        prior @websocket-health-projection-state
        should-sync? (or force?
                         (and diagnostics-open?
                              (not= second-bucket (:second-bucket prior)))
                         (not= fingerprint (:fingerprint prior)))]
    (when should-sync?
      (reset! websocket-health-projection-state
              {:second-bucket second-bucket
               :fingerprint fingerprint})
      (js/queueMicrotask
        #(swap! store assoc-in [:websocket :health] health)))))

(def ^:private reconnect-cooldown-ms
  5000)

(defn- copy-status-at-ms [health]
  (or (:generated-at-ms health)
      0))

(defn- set-copy-status!
  [store status]
  (swap! store assoc-in [:websocket-ui :copy-status] status))

(defn- copy-success-status [health]
  {:kind :success
   :at-ms (copy-status-at-ms health)
   :message "Copied (redacted)"})

(defn- copy-error-status [health diagnostics-json]
  {:kind :error
   :at-ms (copy-status-at-ms health)
   :message "Couldn't access clipboard. Copy the redacted JSON below."
   :fallback-json diagnostics-json})

(defn- diagnostics-stream-rows [health]
  (->> (get health :streams {})
       (sort-by (fn [[sub-key stream]]
                  [(name (or (:group stream) :account))
                   (str (:topic stream))
                   (pr-str sub-key)]))
       (mapv (fn [[sub-key stream]]
               {:sub-key sub-key
                :group (:group stream)
                :topic (:topic stream)
                :status (:status stream)
                :last-payload-at-ms (:last-payload-at-ms stream)
                :stale-threshold-ms (:stale-threshold-ms stream)
                :message-count (:message-count stream)
                :descriptor (:descriptor stream)}))))

(defn- diagnostics-copy-payload [health]
  {:generated-at-ms (:generated-at-ms health)
   :transport (:transport health)
   :groups (:groups health)
   :streams (diagnostics-stream-rows health)})

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

(defn refresh-websocket-health [_ store]
  (sync-websocket-health! store :force? true))

(defn confirm-ws-diagnostics-reveal [_ store]
  (let [confirmed? (js/confirm "Reveal sensitive diagnostics values? This may expose wallet identifiers.")]
    (when confirmed?
      (swap! store assoc-in [:websocket-ui :reveal-sensitive?] true))))

(defn copy-websocket-diagnostics [_ store]
  (let [health (get-in @store [:websocket :health] {})
        payload (diagnostics-sanitize/sanitize-value
                  :redact
                  (diagnostics-copy-payload health))
        diagnostics-json (.stringify js/JSON (clj->js payload) nil 2)
        clipboard (some-> js/globalThis .-navigator .-clipboard)
        write-text-fn (some-> clipboard .-writeText)]
    (set-copy-status! store nil)
    (if (and clipboard write-text-fn)
      (try
        (-> (.writeText clipboard diagnostics-json)
            (.then (fn []
                     (set-copy-status! store (copy-success-status health))))
            (.catch (fn [err]
                      (println "Copy diagnostics failed:" err)
                      (set-copy-status! store (copy-error-status health diagnostics-json)))))
        (catch :default err
          (println "Copy diagnostics failed:" err)
          (set-copy-status! store (copy-error-status health diagnostics-json))))
      (do
        (println "Clipboard API unavailable for websocket diagnostics copy")
        (set-copy-status! store (copy-error-status health diagnostics-json))))))

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

(defn toggle-ws-diagnostics [state]
  (let [open? (not (boolean (get-in state [:websocket-ui :diagnostics-open?])))]
    (cond-> [[:effects/save-many [[[:websocket-ui :diagnostics-open?] open?]
                                  [[:websocket-ui :reveal-sensitive?] false]
                                  [[:websocket-ui :copy-status] nil]]]]
      open?
      (conj [:effects/refresh-websocket-health]))))

(defn close-ws-diagnostics [_]
  [[:effects/save-many [[[:websocket-ui :diagnostics-open?] false]
                        [[:websocket-ui :reveal-sensitive?] false]
                        [[:websocket-ui :copy-status] nil]]]])

(defn toggle-ws-diagnostics-sensitive [state]
  (if (boolean (get-in state [:websocket-ui :reveal-sensitive?]))
    [[:effects/save [:websocket-ui :reveal-sensitive?] false]]
    [[:effects/confirm-ws-diagnostics-reveal]]))

(defn- reconnect-blocked? [state]
  (let [transport-state (get-in state [:websocket :health :transport :state])
        generated-at-ms (or (get-in state [:websocket :health :generated-at-ms]) 0)
        cooldown-until-ms (get-in state [:websocket-ui :reconnect-cooldown-until-ms])]
    (or (contains? #{:connecting :reconnecting} transport-state)
        (and (number? cooldown-until-ms)
             (> cooldown-until-ms generated-at-ms)))))

(defn ws-diagnostics-reconnect-now [state]
  (if (reconnect-blocked? state)
    []
    (let [generated-at-ms (or (get-in state [:websocket :health :generated-at-ms]) 0)]
      [[:effects/save-many [[[:websocket-ui :diagnostics-open?] false]
                            [[:websocket-ui :reveal-sensitive?] false]
                            [[:websocket-ui :copy-status] nil]]]
       [:effects/save [:websocket-ui :reconnect-cooldown-until-ms]
        (+ generated-at-ms reconnect-cooldown-ms)]
       [:effects/reconnect-websocket]])))

(defn ws-diagnostics-copy [_]
  [[:effects/copy-websocket-diagnostics]])

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

(def ^:private ui-font-local-storage-key "hyperopen-ui-font")
(def ^:private supported-ui-fonts #{"system" "inter"})

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

(defn restore-order-history-pagination-settings! [store]
  (let [page-size (normalize-order-history-page-size
                   (js/localStorage.getItem "order-history-page-size"))]
    (swap! store update-in [:account-info :order-history] merge
           {:page-size page-size
            :page 1
            :page-input "1"})))

(defn restore-funding-history-pagination-settings! [store]
  (let [page-size (normalize-order-history-page-size
                   (js/localStorage.getItem "funding-history-page-size"))]
    (swap! store update-in [:account-info :funding-history] merge
           {:page-size page-size
            :page 1
            :page-input "1"})))

(defn restore-trade-history-pagination-settings! [store]
  (let [page-size (normalize-order-history-page-size
                   (js/localStorage.getItem "trade-history-page-size"))]
    (swap! store update-in [:account-info :trade-history] merge
           {:page-size page-size
            :page 1
            :page-input "1"})))

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

(defn- normalize-ui-font [value]
  (let [candidate (-> (or value "system")
                      str
                      str/trim
                      str/lower-case)]
    (if (contains? supported-ui-fonts candidate)
      candidate
      "system")))

(defn restore-ui-font-preference! []
  (when (exists? js/document)
    (let [html-el (.-documentElement js/document)
          stored (try
                   (when (exists? js/localStorage)
                     (.getItem js/localStorage ui-font-local-storage-key))
                   (catch :default _
                     nil))
          normalized (normalize-ui-font stored)]
      (set! (.-uiFont (.-dataset html-el)) normalized))))

(defn restore-active-asset! [store]
  (when (nil? (:active-asset @store))
    (let [stored-asset (js/localStorage.getItem "active-asset")
          asset (if (seq stored-asset) stored-asset "BTC")]
      (swap! store assoc :active-asset asset :selected-asset asset)
      (when-not (seq stored-asset)
        (js/localStorage.setItem "active-asset" asset))
      (when (ws-client/connected?)
        (nxr/dispatch store nil [[:actions/subscribe-to-asset asset]])))))

(defn- chart-dropdown-visibility-path-values [open-dropdown]
  [[[:chart-options :timeframes-dropdown-visible] (= open-dropdown :timeframes)]
   [[:chart-options :chart-type-dropdown-visible] (= open-dropdown :chart-type)]
   [[:chart-options :indicators-dropdown-visible] (= open-dropdown :indicators)]])

(defn- chart-dropdown-projection-effect
  ([open-dropdown]
   (chart-dropdown-projection-effect open-dropdown []))
  ([open-dropdown extra-path-values]
   [:effects/save-many (into (vec extra-path-values)
                             (chart-dropdown-visibility-path-values open-dropdown))]))

(defn toggle-timeframes-dropdown [state]
  (let [current-visible (boolean (get-in state [:chart-options :timeframes-dropdown-visible]))
        open-dropdown (when-not current-visible :timeframes)]
    [(chart-dropdown-projection-effect open-dropdown)]))

(defn select-chart-timeframe [state timeframe]
  (js/localStorage.setItem "chart-timeframe" (name timeframe))
  [(chart-dropdown-projection-effect nil [[[:chart-options :selected-timeframe] timeframe]])
   [:effects/fetch-candle-snapshot :interval timeframe]])

(defn toggle-chart-type-dropdown [state]
  (let [current-visible (boolean (get-in state [:chart-options :chart-type-dropdown-visible]))
        open-dropdown (when-not current-visible :chart-type)]
    [(chart-dropdown-projection-effect open-dropdown)]))

(defn select-chart-type [state chart-type]
  (js/localStorage.setItem "chart-type" (name chart-type))
  [(chart-dropdown-projection-effect nil [[[:chart-options :selected-chart-type] chart-type]])])

(defn toggle-indicators-dropdown [state]
  (let [current-visible (boolean (get-in state [:chart-options :indicators-dropdown-visible]))
        open-dropdown (when-not current-visible :indicators)]
    [(chart-dropdown-projection-effect open-dropdown)]))

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

(defn- parse-datetime-local-ms
  [value]
  (let [text (str/trim (str (or value "")))]
    (when (seq text)
      (let [parsed (.parse js/Date text)]
        (when (and (number? parsed)
                   (not (js/isNaN parsed)))
          (js/Math.floor parsed))))))

(defn- funding-history-filters
  [state]
  (api/normalize-funding-history-filters
   (get-in state [:account-info :funding-history :filters])))

(defn- funding-history-draft-filters
  [state]
  (api/normalize-funding-history-filters
   (or (get-in state [:account-info :funding-history :draft-filters])
       (funding-history-filters state))))

(defn- funding-history-request-id
  [state]
  (get-in state [:account-info :funding-history :request-id] 0))

(def ^:private order-history-status-options
  #{:all :open :filled :canceled :rejected :triggered})

(defn- order-history-request-id
  [state]
  (get-in state [:account-info :order-history :request-id] 0))

(defn- normalize-order-history-status-filter
  [status]
  (let [status* (cond
                  (keyword? status) status
                  (string? status) (keyword (str/lower-case status))
                  :else :all)]
    (if (contains? order-history-status-options status*)
      status*
      :all)))

(defn- filtered-funding-rows
  [state filters]
  (api/filter-funding-history-rows
   (get-in state [:orders :fundings-raw] [])
   filters))

(defn select-account-info-tab [state tab]
  (cond
    (= tab :funding-history)
    (let [filters (funding-history-filters state)
          request-id (inc (funding-history-request-id state))
          projected (filtered-funding-rows state filters)]
      [[:effects/save-many [[[:account-info :selected-tab] tab]
                            [[:account-info :funding-history :filters] filters]
                            [[:account-info :funding-history :draft-filters] filters]
                            [[:account-info :funding-history :loading?] true]
                            [[:account-info :funding-history :error] nil]
                            [[:account-info :funding-history :request-id] request-id]
                            [[:orders :fundings] projected]]]
       [:effects/api-fetch-user-funding-history request-id]])

    (= tab :order-history)
    (let [request-id (inc (order-history-request-id state))]
      [[:effects/save-many [[[:account-info :selected-tab] tab]
                            [[:account-info :order-history :loading?] true]
                            [[:account-info :order-history :error] nil]
                            [[:account-info :order-history :request-id] request-id]]]
       [:effects/api-fetch-historical-orders request-id]])

    :else
    [[:effects/save [:account-info :selected-tab] tab]]))

(defn set-funding-history-filters [_state path value]
  (let [path* (if (vector? path) path [path])
        full-path (into [:account-info :funding-history] path*)
        value* (case path*
                 [:draft-filters :start-time-ms] (parse-datetime-local-ms value)
                 [:draft-filters :end-time-ms] (parse-datetime-local-ms value)
                 [:filters :start-time-ms] (parse-datetime-local-ms value)
                 [:filters :end-time-ms] (parse-datetime-local-ms value)
                 value)]
    [[:effects/save full-path value*]]))

(defn toggle-funding-history-filter-open [state]
  (let [open? (boolean (get-in state [:account-info :funding-history :filter-open?]))
        filters (funding-history-filters state)
        draft-filters (if open?
                        (funding-history-draft-filters state)
                        filters)]
    [[:effects/save-many [[[:account-info :funding-history :filter-open?] (not open?)]
                          [[:account-info :funding-history :draft-filters] draft-filters]]]]))

(defn toggle-funding-history-filter-coin [state coin]
  (let [draft-filters (funding-history-draft-filters state)
        current-set (or (:coin-set draft-filters) #{})
        next-set (if (contains? current-set coin)
                   (disj current-set coin)
                   (conj current-set coin))]
    [[:effects/save [:account-info :funding-history :draft-filters]
      (assoc draft-filters :coin-set next-set)]]))

(defn reset-funding-history-filter-draft [state]
  (let [filters (funding-history-filters state)]
    [[:effects/save-many [[[:account-info :funding-history :draft-filters] filters]
                          [[:account-info :funding-history :filter-open?] false]]]]))

(defn apply-funding-history-filters [state]
  (let [current-filters (funding-history-filters state)
        draft-filters (funding-history-draft-filters state)
        time-range-changed?
        (not= (select-keys current-filters [:start-time-ms :end-time-ms])
              (select-keys draft-filters [:start-time-ms :end-time-ms]))
        projected (filtered-funding-rows state draft-filters)
        request-id (inc (funding-history-request-id state))
        base-effects [[:effects/save-many [[[:account-info :funding-history :filters] draft-filters]
                                           [[:account-info :funding-history :draft-filters] draft-filters]
                                           [[:account-info :funding-history :filter-open?] false]
                                           [[:account-info :funding-history :page] 1]
                                           [[:account-info :funding-history :page-input] "1"]
                                           [[:orders :fundings] projected]]]]]
    (if time-range-changed?
      (into base-effects
            [[:effects/save-many [[[:account-info :funding-history :loading?] true]
                                  [[:account-info :funding-history :error] nil]
                                  [[:account-info :funding-history :request-id] request-id]]]
             [:effects/api-fetch-user-funding-history request-id]])
      base-effects)))

(defn view-all-funding-history [state]
  (let [current-filters (funding-history-filters state)
        next-filters (assoc current-filters
                            :start-time-ms 0
                            :end-time-ms (.now js/Date))
        projected (filtered-funding-rows state next-filters)
        request-id (inc (funding-history-request-id state))]
    [[:effects/save-many [[[:account-info :funding-history :filters] next-filters]
                          [[:account-info :funding-history :draft-filters] next-filters]
                          [[:account-info :funding-history :filter-open?] false]
                          [[:account-info :funding-history :page] 1]
                          [[:account-info :funding-history :page-input] "1"]
                          [[:account-info :funding-history :loading?] true]
                          [[:account-info :funding-history :error] nil]
                          [[:account-info :funding-history :request-id] request-id]
                          [[:orders :fundings] projected]]]
     [:effects/api-fetch-user-funding-history request-id]]))

(defn export-funding-history-csv [state]
  (let [filters (funding-history-filters state)
        rows (filtered-funding-rows state filters)]
    [[:effects/export-funding-history-csv rows]]))

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

(defn sort-funding-history [state column]
  (let [current-sort (get-in state
                             [:account-info :funding-history :sort]
                             {:column "Time" :direction :desc})
        current-column (:column current-sort)
        current-direction (:direction current-sort)
        new-direction (if (= current-column column)
                        (if (= current-direction :asc) :desc :asc)
                        (if (contains? #{"Time" "Size" "Payment" "Rate"} column)
                          :desc
                          :asc))]
    [[:effects/save-many [[[:account-info :funding-history :sort]
                           {:column column :direction new-direction}]
                          [[:account-info :funding-history :page] 1]
                          [[:account-info :funding-history :page-input] "1"]]]]))

(defn set-funding-history-page-size [state page-size]
  (let [page-size* (normalize-order-history-page-size page-size)]
    (js/localStorage.setItem "funding-history-page-size" (str page-size*))
    [[:effects/save-many [[[:account-info :funding-history :page-size] page-size*]
                          [[:account-info :funding-history :page] 1]
                          [[:account-info :funding-history :page-input] "1"]]]]))

(defn set-funding-history-page [state page max-page]
  (let [page* (normalize-order-history-page page max-page)]
    [[:effects/save-many [[[:account-info :funding-history :page] page*]
                          [[:account-info :funding-history :page-input] (str page*)]]]]))

(defn next-funding-history-page [state max-page]
  (let [current-page (get-in state [:account-info :funding-history :page] 1)]
    (set-funding-history-page state (inc current-page) max-page)))

(defn prev-funding-history-page [state max-page]
  (let [current-page (get-in state [:account-info :funding-history :page] 1)]
    (set-funding-history-page state (dec current-page) max-page)))

(defn set-funding-history-page-input [_state input-value]
  [[:effects/save [:account-info :funding-history :page-input]
    (if (string? input-value)
      input-value
      (str (or input-value "")))]] )

(defn apply-funding-history-page-input [state max-page]
  (let [raw-value (get-in state [:account-info :funding-history :page-input] "")
        page* (normalize-order-history-page raw-value max-page)]
    [[:effects/save-many [[[:account-info :funding-history :page] page*]
                          [[:account-info :funding-history :page-input] (str page*)]]]]))

(defn handle-funding-history-page-input-keydown [state key max-page]
  (if (= key "Enter")
    (apply-funding-history-page-input state max-page)
    []))

(defn set-trade-history-page-size [state page-size]
  (let [page-size* (normalize-order-history-page-size page-size)]
    (js/localStorage.setItem "trade-history-page-size" (str page-size*))
    [[:effects/save-many [[[:account-info :trade-history :page-size] page-size*]
                          [[:account-info :trade-history :page] 1]
                          [[:account-info :trade-history :page-input] "1"]]]]))

(defn set-trade-history-page [state page max-page]
  (let [page* (normalize-order-history-page page max-page)]
    [[:effects/save-many [[[:account-info :trade-history :page] page*]
                          [[:account-info :trade-history :page-input] (str page*)]]]]))

(defn next-trade-history-page [state max-page]
  (let [current-page (get-in state [:account-info :trade-history :page] 1)]
    (set-trade-history-page state (inc current-page) max-page)))

(defn prev-trade-history-page [state max-page]
  (let [current-page (get-in state [:account-info :trade-history :page] 1)]
    (set-trade-history-page state (dec current-page) max-page)))

(defn set-trade-history-page-input [_state input-value]
  [[:effects/save [:account-info :trade-history :page-input]
    (if (string? input-value)
      input-value
      (str (or input-value "")))]] )

(defn apply-trade-history-page-input [state max-page]
  (let [raw-value (get-in state [:account-info :trade-history :page-input] "")
        page* (normalize-order-history-page raw-value max-page)]
    [[:effects/save-many [[[:account-info :trade-history :page] page*]
                          [[:account-info :trade-history :page-input] (str page*)]]]]))

(defn handle-trade-history-page-input-keydown [state key max-page]
  (if (= key "Enter")
    (apply-trade-history-page-input state max-page)
    []))

(defn sort-trade-history [state column]
  (let [current-sort (get-in state
                             [:account-info :trade-history :sort]
                             {:column "Time" :direction :desc})
        current-column (:column current-sort)
        current-direction (:direction current-sort)
        desc-columns #{"Time" "Price" "Size" "Trade Value" "Fee" "Closed PNL"}
        new-direction (if (= current-column column)
                        (if (= current-direction :asc) :desc :asc)
                        (if (contains? desc-columns column)
                          :desc
                          :asc))]
    [[:effects/save-many [[[:account-info :trade-history :sort]
                           {:column column :direction new-direction}]
                          [[:account-info :trade-history :page] 1]
                          [[:account-info :trade-history :page-input] "1"]]]]))

(defn sort-order-history [state column]
  (let [current-sort (get-in state
                             [:account-info :order-history :sort]
                             {:column "Time" :direction :desc})
        current-column (:column current-sort)
        current-direction (:direction current-sort)
        desc-columns #{"Time" "Size" "Filled Size" "Order Value" "Price" "Order ID"}
        new-direction (if (= current-column column)
                        (if (= current-direction :asc) :desc :asc)
                        (if (contains? desc-columns column)
                          :desc
                          :asc))]
    [[:effects/save-many [[[:account-info :order-history :sort]
                           {:column column :direction new-direction}]
                          [[:account-info :order-history :page] 1]
                          [[:account-info :order-history :page-input] "1"]]]]))

(defn toggle-order-history-filter-open [state]
  (let [open? (boolean (get-in state [:account-info :order-history :filter-open?]))]
    [[:effects/save [:account-info :order-history :filter-open?] (not open?)]]))

(defn set-order-history-status-filter [state status-filter]
  (let [status* (normalize-order-history-status-filter status-filter)]
    [[:effects/save-many [[[:account-info :order-history :status-filter] status*]
                          [[:account-info :order-history :filter-open?] false]
                          [[:account-info :order-history :page] 1]
                          [[:account-info :order-history :page-input] "1"]]]]))

(defn set-order-history-page-size [state page-size]
  (let [page-size* (normalize-order-history-page-size page-size)]
    (js/localStorage.setItem "order-history-page-size" (str page-size*))
    [[:effects/save-many [[[:account-info :order-history :page-size] page-size*]
                          [[:account-info :order-history :page] 1]
                          [[:account-info :order-history :page-input] "1"]]]]))

(defn set-order-history-page [state page max-page]
  (let [page* (normalize-order-history-page page max-page)]
    [[:effects/save-many [[[:account-info :order-history :page] page*]
                          [[:account-info :order-history :page-input] (str page*)]]]]))

(defn next-order-history-page [state max-page]
  (let [current-page (get-in state [:account-info :order-history :page] 1)]
    (set-order-history-page state (inc current-page) max-page)))

(defn prev-order-history-page [state max-page]
  (let [current-page (get-in state [:account-info :order-history :page] 1)]
    (set-order-history-page state (dec current-page) max-page)))

(defn set-order-history-page-input [_state input-value]
  [[:effects/save [:account-info :order-history :page-input]
    (if (string? input-value)
      input-value
      (str (or input-value "")))]] )

(defn apply-order-history-page-input [state max-page]
  (let [raw-value (get-in state [:account-info :order-history :page-input] "")
        page* (normalize-order-history-page raw-value max-page)]
    [[:effects/save-many [[[:account-info :order-history :page] page*]
                          [[:account-info :order-history :page-input] (str page*)]]]]))

(defn handle-order-history-page-input-keydown [state key max-page]
  (if (= key "Enter")
    (apply-order-history-page-input state max-page)
    []))

(defn refresh-order-history [state]
  (let [request-id (inc (order-history-request-id state))
        selected? (= :order-history (get-in state [:account-info :selected-tab]))]
    [[:effects/save-many [[[:account-info :order-history :request-id] request-id]
                          [[:account-info :order-history :loading?] selected?]
                          [[:account-info :order-history :error] nil]]]
     [:effects/api-fetch-historical-orders request-id]]))

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
        close-pro-dropdown? (contains? #{:market :limit} mode*)
        next-type (case mode*
                    :market :market
                    :limit :limit
                    (trading/normalize-pro-order-type (:type form)))
        normalized (trading/normalize-order-form state
                                                 (assoc form
                                                        :entry-mode mode*
                                                        :type next-type
                                                        :pro-order-type-dropdown-open?
                                                        (if close-pro-dropdown?
                                                          false
                                                          (boolean (:pro-order-type-dropdown-open? form)))))
        next-form (-> (trading/sync-size-from-percent state normalized)
                      (assoc :error nil))]
    [[:effects/save-many [[[:order-form] next-form]]]]))

(defn select-pro-order-type [state order-type]
  (let [form (:order-form state)
        next-type (trading/normalize-pro-order-type order-type)
        normalized (trading/normalize-order-form state
                                                 (assoc form
                                                        :entry-mode :pro
                                                        :type next-type
                                                        :pro-order-type-dropdown-open? false))
        next-form (-> (trading/sync-size-from-percent state normalized)
                      (assoc :error nil))]
    [[:effects/save-many [[[:order-form] next-form]]]]))

(defn toggle-pro-order-type-dropdown [state]
  (let [open? (boolean (get-in state [:order-form :pro-order-type-dropdown-open?]))]
    [[:effects/save-many [[[:order-form :pro-order-type-dropdown-open?] (not open?)]]]]))

(defn close-pro-order-type-dropdown [_state]
  [[:effects/save-many [[[:order-form :pro-order-type-dropdown-open?] false]]]])

(defn handle-pro-order-type-dropdown-keydown [state key]
  (if (= key "Escape")
    (close-pro-order-type-dropdown state)
    []))

(defn set-order-ui-leverage [state leverage]
  (let [form (:order-form state)
        normalized (trading/normalize-ui-leverage state leverage)
        updated (assoc form :ui-leverage normalized)
        next-form (-> (trading/sync-size-from-percent state updated)
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
        normalized-form (trading/normalize-order-form state form)]
    (if (= :scale (:type normalized-form))
      []
      (let [next-open? (not (boolean (:tpsl-panel-open? form)))
            next-form (cond-> (assoc form :tpsl-panel-open? next-open?)
                        (not next-open?) (assoc-in [:tp :enabled?] false)
                        (not next-open?) (assoc-in [:sl :enabled?] false))
            next-form* (assoc next-form :error nil)]
        [[:effects/save-many [[[:order-form] next-form*]]]]))))

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
                    (let [typed (-> updated
                                    (update :type trading/normalize-order-type)
                                    (assoc :entry-mode (trading/entry-mode-for-type (:type updated))))
                          normalized (trading/normalize-order-form state typed)]
                      (trading/sync-size-from-percent state normalized))

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
        errors (trading/validate-order-form state form*)
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

(defn- format-funding-history-time [time-ms]
  (let [d (js/Date. time-ms)
        pad2 (fn [v] (.padStart (str v) 2 "0"))]
    (str (inc (.getMonth d))
         "/"
         (.getDate d)
         "/"
         (.getFullYear d)
         " - "
         (pad2 (.getHours d))
         ":"
         (pad2 (.getMinutes d))
         ":"
         (pad2 (.getSeconds d)))))

(defn- funding-position-side-label
  [position-side]
  (case position-side
    :long "Long"
    :short "Short"
    :flat "Flat"
    "Flat"))

(defn- csv-escape
  [value]
  (let [text (str (or value ""))]
    (if (or (str/includes? text ",")
            (str/includes? text "\"")
            (str/includes? text "\n"))
      (str "\""
           (str/replace text "\"" "\"\"")
           "\"")
      text)))

(defn- format-funding-history-size
  [row]
  (let [size (if (number? (:size-raw row)) (:size-raw row) 0)
        coin (or (:coin row) "-")]
    (str (.toLocaleString (js/Number. size)
                          "en-US"
                          #js {:minimumFractionDigits 3
                               :maximumFractionDigits 6})
         " "
         coin)))

(defn- format-funding-history-payment
  [row]
  (let [payment (if (number? (:payment-usdc-raw row)) (:payment-usdc-raw row) 0)]
    (str (.toLocaleString (js/Number. payment)
                          "en-US"
                          #js {:minimumFractionDigits 4
                               :maximumFractionDigits 6})
         " USDC")))

(defn- format-funding-history-rate
  [row]
  (let [rate (if (number? (:funding-rate-raw row)) (:funding-rate-raw row) 0)]
    (str (.toFixed (* 100 rate) 4) "%")))

(defn- funding-row->csv-line
  [row]
  (str/join ","
            (map csv-escape
                 [(format-funding-history-time (:time-ms row))
                  (:coin row)
                  (format-funding-history-size row)
                  (funding-position-side-label (:position-side row))
                  (format-funding-history-payment row)
                  (format-funding-history-rate row)])))

(defn- merge-and-project-funding-history
  [state rows]
  (let [filters (funding-history-filters state)
        merged (api/merge-funding-history-rows (get-in state [:orders :fundings-raw] [])
                                               rows)
        projected (api/filter-funding-history-rows merged filters)]
    (-> state
        (assoc-in [:account-info :funding-history :filters] filters)
        (assoc-in [:orders :fundings-raw] merged)
        (assoc-in [:orders :fundings] projected))))

(defn- fetch-and-merge-funding-history!
  [store address opts]
  (when address
    (let [filters (funding-history-filters @store)
          request-opts (merge {:priority :high}
                              filters
                              (or opts {}))]
      (-> (api/fetch-user-funding-history! store address request-opts)
          (.then
           (fn [rows]
             (swap! store
                    (fn [state]
                      (if (= address (get-in state [:wallet :address]))
                        (-> (merge-and-project-funding-history state rows)
                            (assoc-in [:account-info :funding-history :error] nil))
                        state)))))
          (.catch
           (fn [err]
             (swap! store
                    (fn [state]
                      (if (= address (get-in state [:wallet :address]))
                        (assoc-in state [:account-info :funding-history :error] (str err))
                        state)))))))))


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
(nxr/register-effect! :effects/refresh-websocket-health refresh-websocket-health)
(nxr/register-effect! :effects/confirm-ws-diagnostics-reveal confirm-ws-diagnostics-reveal)
(nxr/register-effect! :effects/copy-websocket-diagnostics copy-websocket-diagnostics)
(nxr/register-effect! :effects/fetch-asset-selector-markets
  (fn [_ store & [opts]]
    (api/fetch-asset-selector-markets! store (or opts {:phase :full}))))
(nxr/register-effect! :effects/api-fetch-user-funding-history
  (fn [_ store request-id]
    (let [address (get-in @store [:wallet :address])
          filters (funding-history-filters @store)
          opts (merge {:priority :high}
                      filters)]
      (if-not address
        (swap! store
               (fn [state]
                 (if (= request-id (funding-history-request-id state))
                   (-> state
                       (assoc-in [:account-info :funding-history :loading?] false)
                       (assoc-in [:orders :fundings-raw] [])
                       (assoc-in [:orders :fundings] []))
                   state)))
        (-> (api/fetch-user-funding-history! store address opts)
            (.then (fn [rows]
                     (swap! store
                            (fn [state]
                              (if (= request-id (funding-history-request-id state))
                                (-> (merge-and-project-funding-history state rows)
                                    (assoc-in [:account-info :funding-history :loading?] false)
                                    (assoc-in [:account-info :funding-history :error] nil))
                                state)))))
            (.catch (fn [err]
                      (swap! store
                             (fn [state]
                               (if (= request-id (funding-history-request-id state))
                                 (-> state
                                     (assoc-in [:account-info :funding-history :loading?] false)
                                     (assoc-in [:account-info :funding-history :error] (str err)))
                                 state))))))))))
(nxr/register-effect! :effects/api-fetch-historical-orders
  (fn [_ store request-id]
    (let [address (get-in @store [:wallet :address])]
      (if-not address
        (swap! store
               (fn [state]
                 (if (= request-id (order-history-request-id state))
                   (-> state
                       (assoc-in [:account-info :order-history :loading?] false)
                       (assoc-in [:account-info :order-history :error] nil)
                       (assoc-in [:orders :order-history] []))
                   state)))
        (-> (api/fetch-historical-orders! store address {:priority :high})
            (.then (fn [rows]
                     (swap! store
                            (fn [state]
                              (if (= request-id (order-history-request-id state))
                                (-> state
                                    (assoc-in [:account-info :order-history :loading?] false)
                                    (assoc-in [:account-info :order-history :error] nil)
                                    (assoc-in [:orders :order-history] (vec (or rows []))))
                                state)))))
            (.catch (fn [err]
                      (swap! store
                             (fn [state]
                               (if (= request-id (order-history-request-id state))
                                 (-> state
                                     (assoc-in [:account-info :order-history :loading?] false)
                                     (assoc-in [:account-info :order-history :error] (str err)))
                                 state))))))))))
(nxr/register-effect! :effects/export-funding-history-csv
  (fn [_ _ rows]
    (let [rows* (vec (or rows []))
          header "Time,Coin,Size,Position Side,Payment,Rate"
          body (map funding-row->csv-line rows*)
          csv (str/join "\n" (cons header body))]
      (when (and (exists? js/document)
                 (exists? js/URL))
        (let [blob (js/Blob. #js [csv] #js {:type "text/csv;charset=utf-8"})
              url (.createObjectURL js/URL blob)
              link (.createElement js/document "a")
              filename (str "funding-history-" (.now js/Date) ".csv")]
          (set! (.-href link) url)
          (set! (.-download link) filename)
          (.appendChild (.-body js/document) link)
          (.click link)
          (.removeChild (.-body js/document) link)
          (.revokeObjectURL js/URL url))))))
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
                           (do
                             (swap! store assoc-in [:order-form :error] nil)
                             (nxr/dispatch store nil [[:actions/refresh-order-history]]))
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
                     (swap! store assoc-in [:orders :cancel-response] resp)
                     (let [data (js->clj resp :keywordize-keys true)]
                       (when (= "ok" (:status data))
                         (nxr/dispatch store nil [[:actions/refresh-order-history]])))))
            (.catch (fn [err]
                      (swap! store assoc-in [:orders :cancel-error] (str err)))))))))

(nxr/register-effect! :effects/api-load-user-data
  (fn [_ store address]
    (when address
      (api/fetch-frontend-open-orders! store address)
      (api/fetch-user-fills! store address)
      (fetch-and-merge-funding-history! store address {:priority :high}))))
(nxr/register-action! :actions/init-websockets init-websockets)
(nxr/register-action! :actions/subscribe-to-asset subscribe-to-asset)
(nxr/register-action! :actions/subscribe-to-webdata2 subscribe-to-webdata2)
(nxr/register-action! :actions/connect-wallet connect-wallet-action)
(nxr/register-action! :actions/reconnect-websocket reconnect-websocket-action)
(nxr/register-action! :actions/toggle-ws-diagnostics toggle-ws-diagnostics)
(nxr/register-action! :actions/close-ws-diagnostics close-ws-diagnostics)
(nxr/register-action! :actions/toggle-ws-diagnostics-sensitive toggle-ws-diagnostics-sensitive)
(nxr/register-action! :actions/ws-diagnostics-reconnect-now ws-diagnostics-reconnect-now)
(nxr/register-action! :actions/ws-diagnostics-copy ws-diagnostics-copy)
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
(nxr/register-action! :actions/set-funding-history-filters set-funding-history-filters)
(nxr/register-action! :actions/toggle-funding-history-filter-open toggle-funding-history-filter-open)
(nxr/register-action! :actions/toggle-funding-history-filter-coin toggle-funding-history-filter-coin)
(nxr/register-action! :actions/reset-funding-history-filter-draft reset-funding-history-filter-draft)
(nxr/register-action! :actions/apply-funding-history-filters apply-funding-history-filters)
(nxr/register-action! :actions/view-all-funding-history view-all-funding-history)
(nxr/register-action! :actions/export-funding-history-csv export-funding-history-csv)
(nxr/register-action! :actions/set-funding-history-page-size set-funding-history-page-size)
(nxr/register-action! :actions/set-funding-history-page set-funding-history-page)
(nxr/register-action! :actions/next-funding-history-page next-funding-history-page)
(nxr/register-action! :actions/prev-funding-history-page prev-funding-history-page)
(nxr/register-action! :actions/set-funding-history-page-input set-funding-history-page-input)
(nxr/register-action! :actions/apply-funding-history-page-input apply-funding-history-page-input)
(nxr/register-action! :actions/handle-funding-history-page-input-keydown handle-funding-history-page-input-keydown)
(nxr/register-action! :actions/set-trade-history-page-size set-trade-history-page-size)
(nxr/register-action! :actions/set-trade-history-page set-trade-history-page)
(nxr/register-action! :actions/next-trade-history-page next-trade-history-page)
(nxr/register-action! :actions/prev-trade-history-page prev-trade-history-page)
(nxr/register-action! :actions/set-trade-history-page-input set-trade-history-page-input)
(nxr/register-action! :actions/apply-trade-history-page-input apply-trade-history-page-input)
(nxr/register-action! :actions/handle-trade-history-page-input-keydown handle-trade-history-page-input-keydown)
(nxr/register-action! :actions/sort-trade-history sort-trade-history)
(nxr/register-action! :actions/sort-positions sort-positions)
(nxr/register-action! :actions/sort-balances sort-balances)
(nxr/register-action! :actions/sort-open-orders sort-open-orders)
(nxr/register-action! :actions/sort-funding-history sort-funding-history)
(nxr/register-action! :actions/sort-order-history sort-order-history)
(nxr/register-action! :actions/toggle-order-history-filter-open toggle-order-history-filter-open)
(nxr/register-action! :actions/set-order-history-status-filter set-order-history-status-filter)
(nxr/register-action! :actions/set-order-history-page-size set-order-history-page-size)
(nxr/register-action! :actions/set-order-history-page set-order-history-page)
(nxr/register-action! :actions/next-order-history-page next-order-history-page)
(nxr/register-action! :actions/prev-order-history-page prev-order-history-page)
(nxr/register-action! :actions/set-order-history-page-input set-order-history-page-input)
(nxr/register-action! :actions/apply-order-history-page-input apply-order-history-page-input)
(nxr/register-action! :actions/handle-order-history-page-input-keydown handle-order-history-page-input-keydown)
(nxr/register-action! :actions/refresh-order-history refresh-order-history)
(nxr/register-action! :actions/set-hide-small-balances set-hide-small-balances)
(nxr/register-action! :actions/select-order-entry-mode select-order-entry-mode)
(nxr/register-action! :actions/select-pro-order-type select-pro-order-type)
(nxr/register-action! :actions/toggle-pro-order-type-dropdown toggle-pro-order-type-dropdown)
(nxr/register-action! :actions/close-pro-order-type-dropdown close-pro-order-type-dropdown)
(nxr/register-action! :actions/handle-pro-order-type-dropdown-keydown handle-pro-order-type-dropdown-keydown)
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

(nxr/register-placeholder! :event/key
  (fn [{:replicant/keys [dom-event]}]
    (some-> dom-event .-key)))

;; Wire up the render loop
(r/set-dispatch! #(nxr/dispatch store %1 %2))
(when (exists? js/document)
  (add-watch store ::render #(r/render (.getElementById js/document "app") (app-view/app-view %4))))

;; Watch for WebSocket connection status changes
(add-watch ws-client/connection-state ::ws-status
  (fn [_ _ old-state new-state]
    (let [old-status (:status old-state)
          new-status (:status new-state)
          status-transition? (not= old-status new-status)
          legacy-projection (select-keys new-state [:status
                                                    :attempt
                                                    :next-retry-at-ms
                                                    :last-close
                                                    :queue-size])]
      ;; Defer store update to next tick to avoid nested renders.
      (js/queueMicrotask
        #(swap! store update :websocket merge legacy-projection))
      (sync-websocket-health! store :force? status-transition?)
      ;; Notify address watcher only on status transitions.
      (when status-transition?
        (if (= new-status :connected)
          (address-watcher/on-websocket-connected!)
          (address-watcher/on-websocket-disconnected!))))))

(add-watch ws-client/stream-runtime ::ws-health
  (fn [_ _ _ _]
    (sync-websocket-health! store)))

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
      (swap! store assoc-in [:orders :fundings-raw] [])
      (swap! store assoc-in [:orders :fundings] [])
      (swap! store assoc-in [:orders :order-history] [])
      (swap! store assoc-in [:perp-dex-clearinghouse] {})
      ;; Stage A: critical account data.
      (api/fetch-frontend-open-orders! store address {:priority :high})
      (api/fetch-user-fills! store address {:priority :high})
      (api/fetch-spot-clearinghouse-state! store address {:priority :high})
      (fetch-and-merge-funding-history! store address {:priority :high})
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
            (swap! store assoc-in [:orders :fundings-raw] [])
            (swap! store assoc-in [:orders :fundings] [])
            (swap! store assoc-in [:orders :order-history] [])
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
  ;; Restore root typography preference (system default, optional Inter override).
  (restore-ui-font-preference!)
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
  ;; Restore funding history pagination settings from localStorage
  (restore-funding-history-pagination-settings! store)
  ;; Restore trade history pagination settings from localStorage
  (restore-trade-history-pagination-settings! store)
  ;; Restore order history pagination settings from localStorage
  (restore-order-history-pagination-settings! store)
  ;; Initialize wallet system
  (wallet/init-wallet! store)
  ;; Initialize router
  (router/init! store)
  ;; Initialize remote data streams
  (initialize-remote-data-streams!)
  ;; Trigger initial render by updating the store
  (swap! store identity))
