(ns hyperopen.core
  (:require [replicant.dom :as r]
            [nexus.registry :as nxr]
            [hyperopen.views.app-view :as app-view]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.health-runtime :as health-runtime]
            [hyperopen.websocket.orderbook :as orderbook]
            [hyperopen.websocket.trades :as trades]
            [hyperopen.websocket.webdata2 :as webdata2]
            [hyperopen.websocket.user :as user-ws]
            [hyperopen.websocket.diagnostics-actions :as diagnostics-actions]
            [hyperopen.websocket.diagnostics-copy :as diagnostics-copy]
            [hyperopen.websocket.diagnostics-payload :as diagnostics-payload]
            [hyperopen.websocket.diagnostics-runtime :as diagnostics-runtime]
            [hyperopen.websocket.diagnostics-sanitize :as diagnostics-sanitize]
            [hyperopen.websocket.health-projection :as health-projection]
            [hyperopen.websocket.subscriptions-runtime :as subscriptions-runtime]
            [hyperopen.api :as api]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.account.history.actions :as account-history-actions]
            [hyperopen.account.history.effects :as account-history-effects]
            [hyperopen.order.actions :as order-actions]
            [hyperopen.order.feedback-runtime :as order-feedback-runtime]
            [hyperopen.order.effects :as order-effects]
            [hyperopen.asset-selector.active-market-cache :as active-market-cache]
            [hyperopen.asset-selector.actions :as asset-actions]
            [hyperopen.asset-selector.icon-status-runtime :as icon-status-runtime]
            [hyperopen.asset-selector.markets-cache :as markets-cache]
            [hyperopen.asset-selector.settings :as asset-selector-settings]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.chart.actions :as chart-actions]
            [hyperopen.chart.settings :as chart-settings]
            [hyperopen.orderbook.actions :as orderbook-actions]
            [hyperopen.orderbook.price-aggregation :as price-agg]
            [hyperopen.orderbook.settings :as orderbook-settings]
            [hyperopen.registry.runtime :as runtime-registry]
            [hyperopen.startup.init :as startup-init]
            [hyperopen.startup.restore :as startup-restore]
            [hyperopen.startup.runtime :as startup-runtime-lib]
            [hyperopen.startup.watchers :as startup-watchers]
            [hyperopen.ui.preferences :as ui-preferences]
            [hyperopen.utils.parse :as parse-utils]
            [hyperopen.wallet.agent-runtime :as agent-runtime]
            [hyperopen.wallet.actions :as wallet-actions]
            [hyperopen.wallet.connection-runtime :as wallet-connection-runtime]
            [hyperopen.wallet.copy-feedback-runtime :as wallet-copy-runtime]
            [hyperopen.wallet.core :as wallet]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.address-watcher :as address-watcher]
            [hyperopen.router :as router]
            [hyperopen.state.trading :as trading]))

(def ^:private default-funding-history-state
  account-history-actions/default-funding-history-state)

(def ^:private parse-int-value
  parse-utils/parse-int-value)

(def ^:private default-order-history-state
  account-history-actions/default-order-history-state)

(def ^:private default-trade-history-state
  account-history-actions/default-trade-history-state)

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
                                     :show-surface-freshness-cues? false
                                     :reveal-sensitive? false
                                     :copy-status nil
                                     :reconnect-cooldown-until-ms nil
                                     :reset-in-progress? false
                                     :reset-cooldown-until-ms nil
                                     :reset-counts {:market_data 0
                                                    :orders_oms 0
                                                    :all 0}
                                     :auto-recover-cooldown-until-ms nil
                                     :auto-recover-count 0
                                     :reconnect-count 0
                                     :diagnostics-timeline []}
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
                               :error      nil
                               :agent (agent-session/default-agent-state)}
                      :ui {:toast nil}
                      :account {:mode :classic
                                :abstraction-raw nil}
                      :router {:path "/trade"}
                      :order-form (trading/default-order-form)
                      :funding-ui {:modal nil}
                      :asset-selector {:visible-dropdown nil
                                      :search-term ""
                      				  :sort-by :volume
                     				  :sort-direction :desc
                                      :markets []
                                      :market-by-key {}
                                      :scroll-top 0
                                      :render-limit 120
                                      :loading? false
                                      :phase :bootstrap
                                      :cache-hydrated? false
                                      :loaded-at-ms nil
                                      :favorites #{}
                                      :loaded-icons #{}
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
  (atom {:fingerprint nil}))

(defonce ^:private websocket-health-sync-stats
  (atom {:writes 0}))

(defn- websocket-health-fingerprint [health]
  (health-projection/websocket-health-fingerprint health))

(def ^:private diagnostics-timeline-limit
  50)

(def ^:private reconnect-cooldown-ms
  5000)

(def ^:private reset-subscriptions-cooldown-ms
  5000)

(def ^:private auto-recover-severe-threshold-ms
  30000)

(def ^:private auto-recover-cooldown-ms
  300000)

(def ^:private icon-service-worker-path
  "/sw.js")

(def ^:private app-version
  "0.1.0")

(def ^:private wallet-copy-feedback-duration-ms
  1500)

(def ^:private order-feedback-toast-duration-ms
  3500)

(def ^:private agent-storage-mode-reset-message
  "Trading persistence updated. Enable Trading again.")

(defonce ^:private wallet-copy-feedback-timeout-id
  (atom nil))

(defonce ^:private order-feedback-toast-timeout-id
  (atom nil))

(defonce ^:private pending-asset-icon-statuses
  (atom {}))

(defonce ^:private asset-icon-status-flush-handle
  (atom nil))

(defn- effective-now-ms
  [generated-at-ms]
  (health-runtime/effective-now-ms generated-at-ms))

(defn- append-diagnostics-event!
  [store event at-ms & [details]]
  (swap! store
         health-projection/append-diagnostics-event
         event
         at-ms
         details
         diagnostics-timeline-limit))

(defn- sync-websocket-health!
  [store & {:keys [force?]}]
  (health-runtime/sync-websocket-health!
   {:store store
    :force? force?
    :get-health-snapshot ws-client/get-health-snapshot
    :websocket-health-fingerprint websocket-health-fingerprint
    :projection-state websocket-health-projection-state
    :sync-stats websocket-health-sync-stats
    :auto-recover-enabled-fn health-runtime/auto-recover-enabled?
    :auto-recover-severe-threshold-ms auto-recover-severe-threshold-ms
    :auto-recover-cooldown-ms auto-recover-cooldown-ms
    :dispatch! nxr/dispatch
    :append-diagnostics-event! append-diagnostics-event!
    :queue-microtask-fn js/queueMicrotask}))

(defn- copy-status-at-ms [health]
  (diagnostics-payload/copy-status-at-ms health))

(defn- set-copy-status!
  [store status]
  (swap! store assoc-in [:websocket-ui :copy-status] status))

(defn- copy-success-status [health]
  (diagnostics-payload/copy-success-status health))

(defn- copy-error-status [health diagnostics-json]
  (diagnostics-payload/copy-error-status health diagnostics-json))

(defn- diagnostics-stream-rows [health]
  (diagnostics-payload/diagnostics-stream-rows health))

(defn- app-build-id []
  (diagnostics-payload/app-build-id))

(defn- diagnostics-copy-payload [state health]
  (diagnostics-payload/diagnostics-copy-payload state health app-version))

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

(defn local-storage-set [_ _ key value]
  (try
    (when (exists? js/localStorage)
      (js/localStorage.setItem key (str value)))
    (catch :default e
      (js/console.warn "Failed to persist localStorage value:" key e))))

(defn local-storage-set-json [_ _ key value]
  (try
    (when (exists? js/localStorage)
      (js/localStorage.setItem key (js/JSON.stringify (clj->js value))))
    (catch :default e
      (js/console.warn "Failed to persist localStorage JSON value:" key e))))

(defn- schedule-animation-frame! [f]
  (if (fn? (.-requestAnimationFrame js/globalThis))
    (.requestAnimationFrame js/globalThis f)
    (js/setTimeout f 16)))

(defn- flush-queued-asset-icon-statuses! [store]
  (icon-status-runtime/flush-queued-asset-icon-statuses!
   {:store store
    :pending-statuses pending-asset-icon-statuses
    :flush-handle asset-icon-status-flush-handle
    :apply-asset-icon-status-updates-fn asset-actions/apply-asset-icon-status-updates
    :save-many! (fn [runtime-store path-values]
                  (save-many nil runtime-store path-values))}))

(defn queue-asset-icon-status [_ store payload]
  (icon-status-runtime/queue-asset-icon-status!
   {:store store
    :payload payload
    :pending-statuses pending-asset-icon-statuses
    :flush-handle asset-icon-status-flush-handle
    :schedule-animation-frame! schedule-animation-frame!
    :flush-queued-asset-icon-statuses! flush-queued-asset-icon-statuses!}))

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

(defn- normalize-market-type [value]
  (markets-cache/normalize-market-type value))

(defn- parse-max-leverage [value]
  (markets-cache/parse-max-leverage value))

(defn- normalize-display-text [value]
  (markets-cache/normalize-display-text value))

(defn- persist-asset-selector-markets-cache!
  ([markets]
   (persist-asset-selector-markets-cache! markets {}))
  ([markets state]
   (markets-cache/persist-asset-selector-markets-cache! markets state)))

(defn- load-asset-selector-markets-cache []
  (markets-cache/load-asset-selector-markets-cache))

(defn restore-asset-selector-markets-cache! [store]
  (markets-cache/restore-asset-selector-markets-cache!
   store
   {:load-cache-fn load-asset-selector-markets-cache
    :resolve-market-by-coin-fn markets/resolve-market-by-coin}))

(defn- active-market-display-normalize-deps []
  {:normalize-display-text normalize-display-text
   :normalize-market-type normalize-market-type
   :parse-max-leverage parse-max-leverage})

(defn- persist-active-market-display! [market]
  (active-market-cache/persist-active-market-display!
   market
   (active-market-display-normalize-deps)))

(defn- load-active-market-display [active-asset]
  (active-market-cache/load-active-market-display
   active-asset
   (active-market-display-normalize-deps)))

(defn subscribe-active-asset [_ store coin]
  (subscriptions-runtime/subscribe-active-asset!
   {:store store
    :coin coin
    :log-fn println
    :resolve-market-by-coin-fn markets/resolve-market-by-coin
    :persist-active-asset! (fn [canonical-coin]
                             (when (string? canonical-coin)
                               (js/localStorage.setItem "active-asset" canonical-coin)))
    :persist-active-market-display! persist-active-market-display!
    :subscribe-active-asset-ctx! active-ctx/subscribe-active-asset-ctx!
    :fetch-candle-snapshot! (fn [selected-timeframe]
                              (fetch-candle-snapshot nil store :interval selected-timeframe))}))

(defn unsubscribe-active-asset [_ store coin]
  (subscriptions-runtime/unsubscribe-active-asset!
   {:store store
    :coin coin
    :log-fn println
    :unsubscribe-active-asset-ctx! active-ctx/unsubscribe-active-asset-ctx!}))

(defn subscribe-orderbook [_ store coin]
  (subscriptions-runtime/subscribe-orderbook!
   {:store store
    :coin coin
    :log-fn println
    :normalize-mode-fn price-agg/normalize-mode
    :mode->subscription-config-fn price-agg/mode->subscription-config
    :subscribe-orderbook-fn orderbook/subscribe-orderbook!}))

(defn subscribe-trades [_ store coin]
  (subscriptions-runtime/subscribe-trades!
   {:coin coin
    :log-fn println
    :subscribe-trades-fn trades/subscribe-trades!}))

(defn unsubscribe-orderbook [_ store coin]
  (subscriptions-runtime/unsubscribe-orderbook!
   {:store store
    :coin coin
    :log-fn println
    :unsubscribe-orderbook-fn orderbook/unsubscribe-orderbook!}))

(defn unsubscribe-trades [_ store coin]
  (subscriptions-runtime/unsubscribe-trades!
   {:coin coin
    :log-fn println
    :unsubscribe-trades-fn trades/unsubscribe-trades!}))

(defn subscribe-webdata2 [_ store address]
  (subscriptions-runtime/subscribe-webdata2!
   {:address address
    :log-fn println
    :subscribe-webdata2-fn webdata2/subscribe-webdata2!}))

(defn unsubscribe-webdata2 [_ store address]
  (subscriptions-runtime/unsubscribe-webdata2!
   {:address address
    :log-fn println
    :unsubscribe-webdata2-fn webdata2/unsubscribe-webdata2!}))

(defn connect-wallet [_ store]
  (wallet-connection-runtime/connect-wallet!
   {:store store
    :log-fn println
    :request-connection! wallet/request-connection!}))

(defn- set-wallet-copy-feedback! [store kind message]
  (wallet-copy-runtime/set-wallet-copy-feedback! store kind message))

(defn- clear-wallet-copy-feedback! [store]
  (wallet-copy-runtime/clear-wallet-copy-feedback! store))

(defn- clear-wallet-copy-feedback-timeout! []
  (wallet-copy-runtime/clear-wallet-copy-feedback-timeout!
   wallet-copy-feedback-timeout-id
   js/clearTimeout))

(defn- set-order-feedback-toast! [store kind message]
  (order-feedback-runtime/set-order-feedback-toast! store kind message))

(defn- clear-order-feedback-toast! [store]
  (order-feedback-runtime/clear-order-feedback-toast! store))

(defn- clear-order-feedback-toast-timeout! []
  (order-feedback-runtime/clear-order-feedback-toast-timeout!
   order-feedback-toast-timeout-id
   js/clearTimeout))

(defn- schedule-order-feedback-toast-clear! [store]
  (order-feedback-runtime/schedule-order-feedback-toast-clear!
   {:store store
    :order-feedback-toast-timeout-id order-feedback-toast-timeout-id
    :clear-order-feedback-toast! clear-order-feedback-toast!
    :clear-order-feedback-toast-timeout! clear-order-feedback-toast-timeout!
    :order-feedback-toast-duration-ms order-feedback-toast-duration-ms
    :set-timeout-fn js/setTimeout}))

(defn- show-order-feedback-toast! [store kind message]
  (order-feedback-runtime/show-order-feedback-toast!
   store
   kind
   message
   schedule-order-feedback-toast-clear!))

(defn disconnect-wallet [_ store]
  (wallet-connection-runtime/disconnect-wallet!
   {:store store
    :log-fn println
    :clear-wallet-copy-feedback-timeout! clear-wallet-copy-feedback-timeout!
    :clear-order-feedback-toast-timeout! clear-order-feedback-toast-timeout!
    :clear-order-feedback-toast! clear-order-feedback-toast!
    :set-disconnected! wallet/set-disconnected!}))

(defn set-agent-storage-mode [_ store storage-mode]
  (agent-runtime/set-agent-storage-mode!
   {:store store
    :storage-mode storage-mode
    :normalize-storage-mode agent-session/normalize-storage-mode
    :clear-agent-session-by-mode! agent-session/clear-agent-session-by-mode!
    :persist-storage-mode-preference! agent-session/persist-storage-mode-preference!
    :default-agent-state agent-session/default-agent-state
    :agent-storage-mode-reset-message agent-storage-mode-reset-message}))

(defn- schedule-wallet-copy-feedback-clear! [store]
  (wallet-copy-runtime/schedule-wallet-copy-feedback-clear!
   {:store store
    :wallet-copy-feedback-timeout-id wallet-copy-feedback-timeout-id
    :clear-wallet-copy-feedback! clear-wallet-copy-feedback!
    :clear-wallet-copy-feedback-timeout! clear-wallet-copy-feedback-timeout!
    :wallet-copy-feedback-duration-ms wallet-copy-feedback-duration-ms
    :set-timeout-fn js/setTimeout}))

(defn copy-wallet-address [_ store address]
  (wallet-copy-runtime/copy-wallet-address!
   {:store store
    :address address
    :set-wallet-copy-feedback! set-wallet-copy-feedback!
    :clear-wallet-copy-feedback! clear-wallet-copy-feedback!
    :clear-wallet-copy-feedback-timeout! clear-wallet-copy-feedback-timeout!
    :schedule-wallet-copy-feedback-clear! schedule-wallet-copy-feedback-clear!
    :log-fn println}))

(defn reconnect-websocket [_ _]
  (println "Forcing WebSocket reconnect...")
  (ws-client/force-reconnect!))

(defn refresh-websocket-health [_ store]
  (sync-websocket-health! store :force? true))

(defn ws-reset-subscriptions [_ store {:keys [group source]
                                       :or {group :all
                                            source :manual}}]
  (diagnostics-runtime/ws-reset-subscriptions!
   {:store store
    :group group
    :source source
    :get-health-snapshot ws-client/get-health-snapshot
    :effective-now-ms effective-now-ms
    :reset-subscriptions-cooldown-ms reset-subscriptions-cooldown-ms
    :send-message! ws-client/send-message!
    :append-diagnostics-event! append-diagnostics-event!}))

(defn confirm-ws-diagnostics-reveal [_ store]
  (let [confirmed? (js/confirm "Reveal sensitive diagnostics values? This may expose wallet identifiers.")]
    (when confirmed?
      (swap! store assoc-in [:websocket-ui :reveal-sensitive?] true))))

(defn copy-websocket-diagnostics [_ store]
  (diagnostics-copy/copy-websocket-diagnostics!
   {:store store
    :diagnostics-copy-payload diagnostics-copy-payload
    :sanitize-value diagnostics-sanitize/sanitize-value
    :set-copy-status! set-copy-status!
    :copy-success-status copy-success-status
    :copy-error-status copy-error-status
    :log-fn println}))

(defn init-websockets [state]
  [[:effects/init-websocket]])

(defn subscribe-to-asset [state coin]
  [[:effects/subscribe-active-asset coin]
   [:effects/subscribe-orderbook coin]
   [:effects/subscribe-trades coin]])

(defn subscribe-to-webdata2 [state address]
  [[:effects/subscribe-webdata2 address]])

(defn connect-wallet-action [state]
  (wallet-actions/connect-wallet-action state))

(defn disconnect-wallet-action [_state]
  (wallet-actions/disconnect-wallet-action nil))

(defn- should-auto-enable-agent-trading?
  [state connected-address]
  (wallet-connection-runtime/should-auto-enable-agent-trading?
   state
   connected-address))

(defn handle-wallet-connected
  [store connected-address]
  (wallet-connection-runtime/handle-wallet-connected!
   {:store store
    :connected-address connected-address
    :should-auto-enable-agent-trading? should-auto-enable-agent-trading?
    :dispatch! nxr/dispatch}))

(defn- exchange-response-error
  [resp]
  (agent-runtime/exchange-response-error resp))

(defn- runtime-error-message
  [err]
  (agent-runtime/runtime-error-message err))

(defn enable-agent-trading
  [_ store {:keys [storage-mode is-mainnet agent-name signature-chain-id]
            :or {storage-mode :local
                 is-mainnet true
                 agent-name nil
                 signature-chain-id nil}}]
  (agent-runtime/enable-agent-trading!
   {:store store
    :options {:storage-mode storage-mode
              :is-mainnet is-mainnet
              :agent-name agent-name
              :signature-chain-id signature-chain-id}
    :create-agent-credentials! agent-session/create-agent-credentials!
    :now-ms-fn (fn []
                 (.now js/Date))
    :normalize-storage-mode agent-session/normalize-storage-mode
    :default-signature-chain-id-for-environment agent-session/default-signature-chain-id-for-environment
    :build-approve-agent-action agent-session/build-approve-agent-action
    :approve-agent! trading-api/approve-agent!
    :persist-agent-session-by-mode! agent-session/persist-agent-session-by-mode!
    :runtime-error-message runtime-error-message
    :exchange-response-error exchange-response-error}))

(defn enable-agent-trading-action
  [state]
  (wallet-actions/enable-agent-trading-action
   state
   agent-session/normalize-storage-mode))

(defn set-agent-storage-mode-action
  [state storage-mode]
  (wallet-actions/set-agent-storage-mode-action
   state
   storage-mode
   agent-session/normalize-storage-mode))

(defn copy-wallet-address-action [state]
  (wallet-actions/copy-wallet-address-action state))

(defn reconnect-websocket-action [state]
  [[:effects/reconnect-websocket]])

(defn- ws-diagnostics-action-deps []
  {:effective-now-ms effective-now-ms
   :reconnect-cooldown-ms reconnect-cooldown-ms})

(defn toggle-ws-diagnostics [state]
  (diagnostics-actions/toggle-ws-diagnostics state))

(defn close-ws-diagnostics [_]
  (diagnostics-actions/close-ws-diagnostics nil))

(defn toggle-ws-diagnostics-sensitive [state]
  (diagnostics-actions/toggle-ws-diagnostics-sensitive state))

(defn ws-diagnostics-reconnect-now [state]
  (diagnostics-actions/ws-diagnostics-reconnect-now
   state
   (ws-diagnostics-action-deps)))

(defn ws-diagnostics-copy [_]
  (diagnostics-actions/ws-diagnostics-copy nil))

(defn set-show-surface-freshness-cues [_ checked]
  (diagnostics-actions/set-show-surface-freshness-cues nil checked))

(defn toggle-show-surface-freshness-cues [state]
  (diagnostics-actions/toggle-show-surface-freshness-cues state))

(defn ws-diagnostics-reset-market-subscriptions
  ([state]
   (ws-diagnostics-reset-market-subscriptions state :manual))
  ([state source]
   (diagnostics-actions/ws-diagnostics-reset-market-subscriptions
    state
    source
    (ws-diagnostics-action-deps))))

(defn ws-diagnostics-reset-orders-subscriptions
  ([state]
   (ws-diagnostics-reset-orders-subscriptions state :manual))
  ([state source]
   (diagnostics-actions/ws-diagnostics-reset-orders-subscriptions
    state
    source
    (ws-diagnostics-action-deps))))

(defn ws-diagnostics-reset-all-subscriptions
  ([state]
   (ws-diagnostics-reset-all-subscriptions state :manual))
  ([state source]
   (diagnostics-actions/ws-diagnostics-reset-all-subscriptions
    state
    source
    (ws-diagnostics-action-deps))))

(def toggle-asset-dropdown
  asset-actions/toggle-asset-dropdown)

(def close-asset-dropdown
  asset-actions/close-asset-dropdown)

(def select-asset
  asset-actions/select-asset)

(def update-asset-search
  asset-actions/update-asset-search)

;; --- asset selector sort settings logic moved to asset_selector/settings.cljs ---

(def update-asset-selector-sort
  asset-actions/update-asset-selector-sort)

(def toggle-asset-selector-strict
  asset-actions/toggle-asset-selector-strict)

(def toggle-asset-favorite
  asset-actions/toggle-asset-favorite)

(def set-asset-selector-favorites-only
  asset-actions/set-asset-selector-favorites-only)

(def set-asset-selector-tab
  asset-actions/set-asset-selector-tab)

(def set-asset-selector-scroll-top
  asset-actions/set-asset-selector-scroll-top)

(def increase-asset-selector-render-limit
  asset-actions/increase-asset-selector-render-limit)

(def show-all-asset-selector-markets
  asset-actions/show-all-asset-selector-markets)

(def maybe-increase-asset-selector-render-limit
  asset-actions/maybe-increase-asset-selector-render-limit)

(defn refresh-asset-markets [state]
  [[:effects/fetch-asset-selector-markets]])

(def apply-asset-icon-status-updates
  asset-actions/apply-asset-icon-status-updates)

(def mark-loaded-asset-icon
  asset-actions/mark-loaded-asset-icon)

(def mark-missing-asset-icon
  asset-actions/mark-missing-asset-icon)

(def restore-open-orders-sort-settings!
  account-history-actions/restore-open-orders-sort-settings!)

(def restore-order-history-pagination-settings!
  account-history-actions/restore-order-history-pagination-settings!)

(def restore-funding-history-pagination-settings!
  account-history-actions/restore-funding-history-pagination-settings!)

(def restore-trade-history-pagination-settings!
  account-history-actions/restore-trade-history-pagination-settings!)

(def restore-chart-options!
  chart-settings/restore-chart-options!)

(def restore-orderbook-ui!
  orderbook-settings/restore-orderbook-ui!)

(def restore-agent-storage-mode!
  startup-restore/restore-agent-storage-mode!)

(def restore-ui-font-preference!
  ui-preferences/restore-ui-font-preference!)

(defn- restore-active-asset-deps []
  {:connected?-fn ws-client/connected?
   :dispatch! nxr/dispatch
   :load-active-market-display-fn load-active-market-display})

(defn restore-active-asset! [store]
  (startup-restore/restore-active-asset! store (restore-active-asset-deps)))

(def toggle-timeframes-dropdown
  chart-actions/toggle-timeframes-dropdown)

(def select-chart-timeframe
  chart-actions/select-chart-timeframe)

(def toggle-chart-type-dropdown
  chart-actions/toggle-chart-type-dropdown)

(def select-chart-type
  chart-actions/select-chart-type)

(def toggle-indicators-dropdown
  chart-actions/toggle-indicators-dropdown)

(def toggle-orderbook-size-unit-dropdown
  orderbook-actions/toggle-orderbook-size-unit-dropdown)

(def select-orderbook-size-unit
  orderbook-actions/select-orderbook-size-unit)

(def toggle-orderbook-price-aggregation-dropdown
  orderbook-actions/toggle-orderbook-price-aggregation-dropdown)

(def select-orderbook-price-aggregation
  orderbook-actions/select-orderbook-price-aggregation)

(def select-orderbook-tab
  orderbook-actions/select-orderbook-tab)

(def add-indicator
  chart-settings/add-indicator)

(def remove-indicator
  chart-settings/remove-indicator)

(def update-indicator-period
  chart-settings/update-indicator-period)

(def select-account-info-tab
  account-history-actions/select-account-info-tab)

(def set-funding-history-filters
  account-history-actions/set-funding-history-filters)

(def toggle-funding-history-filter-open
  account-history-actions/toggle-funding-history-filter-open)

(def toggle-funding-history-filter-coin
  account-history-actions/toggle-funding-history-filter-coin)

(def reset-funding-history-filter-draft
  account-history-actions/reset-funding-history-filter-draft)

(def apply-funding-history-filters
  account-history-actions/apply-funding-history-filters)

(def view-all-funding-history
  account-history-actions/view-all-funding-history)

(def export-funding-history-csv
  account-history-actions/export-funding-history-csv)

(def sort-positions
  account-history-actions/sort-positions)

(def sort-balances
  account-history-actions/sort-balances)

(def sort-open-orders
  account-history-actions/sort-open-orders)

(def sort-funding-history
  account-history-actions/sort-funding-history)

(def set-funding-history-page-size
  account-history-actions/set-funding-history-page-size)

(def set-funding-history-page
  account-history-actions/set-funding-history-page)

(def next-funding-history-page
  account-history-actions/next-funding-history-page)

(def prev-funding-history-page
  account-history-actions/prev-funding-history-page)

(def set-funding-history-page-input
  account-history-actions/set-funding-history-page-input)

(def apply-funding-history-page-input
  account-history-actions/apply-funding-history-page-input)

(def handle-funding-history-page-input-keydown
  account-history-actions/handle-funding-history-page-input-keydown)

(def set-trade-history-page-size
  account-history-actions/set-trade-history-page-size)

(def set-trade-history-page
  account-history-actions/set-trade-history-page)

(def next-trade-history-page
  account-history-actions/next-trade-history-page)

(def prev-trade-history-page
  account-history-actions/prev-trade-history-page)

(def set-trade-history-page-input
  account-history-actions/set-trade-history-page-input)

(def apply-trade-history-page-input
  account-history-actions/apply-trade-history-page-input)

(def handle-trade-history-page-input-keydown
  account-history-actions/handle-trade-history-page-input-keydown)

(def sort-trade-history
  account-history-actions/sort-trade-history)

(def sort-order-history
  account-history-actions/sort-order-history)

(def toggle-order-history-filter-open
  account-history-actions/toggle-order-history-filter-open)

(def set-order-history-status-filter
  account-history-actions/set-order-history-status-filter)

(def set-order-history-page-size
  account-history-actions/set-order-history-page-size)

(def set-order-history-page
  account-history-actions/set-order-history-page)

(def next-order-history-page
  account-history-actions/next-order-history-page)

(def prev-order-history-page
  account-history-actions/prev-order-history-page)

(def set-order-history-page-input
  account-history-actions/set-order-history-page-input)

(def apply-order-history-page-input
  account-history-actions/apply-order-history-page-input)

(def handle-order-history-page-input-keydown
  account-history-actions/handle-order-history-page-input-keydown)

(def refresh-order-history
  account-history-actions/refresh-order-history)

(def set-hide-small-balances
  account-history-actions/set-hide-small-balances)

(def select-order-entry-mode
  order-actions/select-order-entry-mode)

(def select-pro-order-type
  order-actions/select-pro-order-type)

(def toggle-pro-order-type-dropdown
  order-actions/toggle-pro-order-type-dropdown)

(def close-pro-order-type-dropdown
  order-actions/close-pro-order-type-dropdown)

(def handle-pro-order-type-dropdown-keydown
  order-actions/handle-pro-order-type-dropdown-keydown)

(def set-order-ui-leverage
  order-actions/set-order-ui-leverage)

(def set-order-size-percent
  order-actions/set-order-size-percent)

(def set-order-size-display
  order-actions/set-order-size-display)

(def focus-order-price-input
  order-actions/focus-order-price-input)

(def blur-order-price-input
  order-actions/blur-order-price-input)

(def set-order-price-to-mid
  order-actions/set-order-price-to-mid)

(def toggle-order-tpsl-panel
  order-actions/toggle-order-tpsl-panel)

(def update-order-form
  order-actions/update-order-form)

(def submit-order
  order-actions/submit-order)

(def prune-canceled-open-orders
  order-actions/prune-canceled-open-orders)

(def cancel-order
  order-actions/cancel-order)

(defn load-user-data [state address]
  [[:effects/api-load-user-data address]])

(defn set-funding-modal [state modal]
  [[:effects/save [:funding-ui :modal] modal]])

(defn- order-api-effect-deps []
  {:dispatch! nxr/dispatch
   :exchange-response-error exchange-response-error
   :prune-canceled-open-orders-fn order-effects/prune-canceled-open-orders
   :runtime-error-message runtime-error-message
   :show-toast! show-order-feedback-toast!})

(defn api-submit-order
  [ctx store request]
  (order-effects/api-submit-order (order-api-effect-deps) ctx store request))

(defn api-cancel-order
  [ctx store request]
  (order-effects/api-cancel-order (order-api-effect-deps) ctx store request))

(defn- fetch-asset-selector-markets-effect
  [_ store & [opts]]
  (api/fetch-asset-selector-markets! store (or opts {:phase :full})))

(defn- api-load-user-data-effect
  [_ store address]
  (when address
    (api/fetch-frontend-open-orders! store address)
    (api/fetch-user-fills! store address)
    (account-history-effects/fetch-and-merge-funding-history! store address {:priority :high})))

(defn navigate
  [state path & [opts]]
  (let [p (router/normalize-path path)
        replace? (boolean (:replace? opts))]
    (cond-> [[:effects/save [:router :path] p]]
      replace? (conj [:effects/replace-state p])
      (not replace?) (conj [:effects/push-state p]))))

(defn- register-runtime!
  []
  (runtime-registry/register-effects!
   {:save save
    :save-many save-many
    :local-storage-set local-storage-set
    :local-storage-set-json local-storage-set-json
    :queue-asset-icon-status queue-asset-icon-status
    :push-state push-state
    :replace-state replace-state
    :init-websocket init-websocket
    :subscribe-active-asset subscribe-active-asset
    :subscribe-orderbook subscribe-orderbook
    :subscribe-trades subscribe-trades
    :subscribe-webdata2 subscribe-webdata2
    :fetch-candle-snapshot fetch-candle-snapshot
    :unsubscribe-active-asset unsubscribe-active-asset
    :unsubscribe-orderbook unsubscribe-orderbook
    :unsubscribe-trades unsubscribe-trades
    :unsubscribe-webdata2 unsubscribe-webdata2
    :connect-wallet connect-wallet
    :disconnect-wallet disconnect-wallet
    :enable-agent-trading enable-agent-trading
    :set-agent-storage-mode set-agent-storage-mode
    :copy-wallet-address copy-wallet-address
    :reconnect-websocket reconnect-websocket
    :refresh-websocket-health refresh-websocket-health
    :confirm-ws-diagnostics-reveal confirm-ws-diagnostics-reveal
    :copy-websocket-diagnostics copy-websocket-diagnostics
    :ws-reset-subscriptions ws-reset-subscriptions
    :fetch-asset-selector-markets fetch-asset-selector-markets-effect
    :api-fetch-user-funding-history account-history-effects/api-fetch-user-funding-history-effect
    :api-fetch-historical-orders account-history-effects/api-fetch-historical-orders-effect
    :export-funding-history-csv account-history-effects/export-funding-history-csv-effect
    :api-submit-order api-submit-order
    :api-cancel-order api-cancel-order
    :api-load-user-data api-load-user-data-effect})

  (runtime-registry/register-actions!
   {:init-websockets init-websockets
    :subscribe-to-asset subscribe-to-asset
    :subscribe-to-webdata2 subscribe-to-webdata2
    :connect-wallet-action connect-wallet-action
    :disconnect-wallet-action disconnect-wallet-action
    :enable-agent-trading-action enable-agent-trading-action
    :set-agent-storage-mode-action set-agent-storage-mode-action
    :copy-wallet-address-action copy-wallet-address-action
    :reconnect-websocket-action reconnect-websocket-action
    :toggle-ws-diagnostics toggle-ws-diagnostics
    :close-ws-diagnostics close-ws-diagnostics
    :toggle-ws-diagnostics-sensitive toggle-ws-diagnostics-sensitive
    :ws-diagnostics-reconnect-now ws-diagnostics-reconnect-now
    :ws-diagnostics-copy ws-diagnostics-copy
    :set-show-surface-freshness-cues set-show-surface-freshness-cues
    :toggle-show-surface-freshness-cues toggle-show-surface-freshness-cues
    :ws-diagnostics-reset-market-subscriptions ws-diagnostics-reset-market-subscriptions
    :ws-diagnostics-reset-orders-subscriptions ws-diagnostics-reset-orders-subscriptions
    :ws-diagnostics-reset-all-subscriptions ws-diagnostics-reset-all-subscriptions
    :toggle-asset-dropdown toggle-asset-dropdown
    :close-asset-dropdown close-asset-dropdown
    :select-asset select-asset
    :update-asset-search update-asset-search
    :update-asset-selector-sort update-asset-selector-sort
    :toggle-asset-selector-strict toggle-asset-selector-strict
    :toggle-asset-favorite toggle-asset-favorite
    :set-asset-selector-favorites-only set-asset-selector-favorites-only
    :set-asset-selector-tab set-asset-selector-tab
    :set-asset-selector-scroll-top set-asset-selector-scroll-top
    :increase-asset-selector-render-limit increase-asset-selector-render-limit
    :show-all-asset-selector-markets show-all-asset-selector-markets
    :maybe-increase-asset-selector-render-limit maybe-increase-asset-selector-render-limit
    :refresh-asset-markets refresh-asset-markets
    :mark-loaded-asset-icon mark-loaded-asset-icon
    :mark-missing-asset-icon mark-missing-asset-icon
    :toggle-timeframes-dropdown toggle-timeframes-dropdown
    :select-chart-timeframe select-chart-timeframe
    :toggle-chart-type-dropdown toggle-chart-type-dropdown
    :select-chart-type select-chart-type
    :toggle-indicators-dropdown toggle-indicators-dropdown
    :toggle-orderbook-size-unit-dropdown toggle-orderbook-size-unit-dropdown
    :select-orderbook-size-unit select-orderbook-size-unit
    :toggle-orderbook-price-aggregation-dropdown toggle-orderbook-price-aggregation-dropdown
    :select-orderbook-price-aggregation select-orderbook-price-aggregation
    :select-orderbook-tab select-orderbook-tab
    :add-indicator add-indicator
    :remove-indicator remove-indicator
    :update-indicator-period update-indicator-period
    :select-account-info-tab select-account-info-tab
    :set-funding-history-filters set-funding-history-filters
    :toggle-funding-history-filter-open toggle-funding-history-filter-open
    :toggle-funding-history-filter-coin toggle-funding-history-filter-coin
    :reset-funding-history-filter-draft reset-funding-history-filter-draft
    :apply-funding-history-filters apply-funding-history-filters
    :view-all-funding-history view-all-funding-history
    :export-funding-history-csv export-funding-history-csv
    :set-funding-history-page-size set-funding-history-page-size
    :set-funding-history-page set-funding-history-page
    :next-funding-history-page next-funding-history-page
    :prev-funding-history-page prev-funding-history-page
    :set-funding-history-page-input set-funding-history-page-input
    :apply-funding-history-page-input apply-funding-history-page-input
    :handle-funding-history-page-input-keydown handle-funding-history-page-input-keydown
    :set-trade-history-page-size set-trade-history-page-size
    :set-trade-history-page set-trade-history-page
    :next-trade-history-page next-trade-history-page
    :prev-trade-history-page prev-trade-history-page
    :set-trade-history-page-input set-trade-history-page-input
    :apply-trade-history-page-input apply-trade-history-page-input
    :handle-trade-history-page-input-keydown handle-trade-history-page-input-keydown
    :sort-trade-history sort-trade-history
    :sort-positions sort-positions
    :sort-balances sort-balances
    :sort-open-orders sort-open-orders
    :sort-funding-history sort-funding-history
    :sort-order-history sort-order-history
    :toggle-order-history-filter-open toggle-order-history-filter-open
    :set-order-history-status-filter set-order-history-status-filter
    :set-order-history-page-size set-order-history-page-size
    :set-order-history-page set-order-history-page
    :next-order-history-page next-order-history-page
    :prev-order-history-page prev-order-history-page
    :set-order-history-page-input set-order-history-page-input
    :apply-order-history-page-input apply-order-history-page-input
    :handle-order-history-page-input-keydown handle-order-history-page-input-keydown
    :refresh-order-history refresh-order-history
    :set-hide-small-balances set-hide-small-balances
    :select-order-entry-mode select-order-entry-mode
    :select-pro-order-type select-pro-order-type
    :toggle-pro-order-type-dropdown toggle-pro-order-type-dropdown
    :close-pro-order-type-dropdown close-pro-order-type-dropdown
    :handle-pro-order-type-dropdown-keydown handle-pro-order-type-dropdown-keydown
    :set-order-ui-leverage set-order-ui-leverage
    :set-order-size-percent set-order-size-percent
    :set-order-size-display set-order-size-display
    :focus-order-price-input focus-order-price-input
    :blur-order-price-input blur-order-price-input
    :set-order-price-to-mid set-order-price-to-mid
    :toggle-order-tpsl-panel toggle-order-tpsl-panel
    :update-order-form update-order-form
    :submit-order submit-order
    :cancel-order cancel-order
    :load-user-data load-user-data
    :set-funding-modal set-funding-modal
    :navigate navigate})

  (runtime-registry/register-system-state!)
  (runtime-registry/register-placeholders!))

(register-runtime!)

;; Wire up the render loop
(r/set-dispatch! #(nxr/dispatch store %1 %2))
(when (exists? js/document)
  (add-watch store ::render #(r/render (.getElementById js/document "app") (app-view/app-view %4))))

(startup-watchers/install-store-cache-watchers!
 store {:persist-active-market-display! persist-active-market-display!
        :persist-asset-selector-markets-cache! persist-asset-selector-markets-cache!})

(startup-watchers/install-websocket-watchers!
 {:store store
  :connection-state ws-client/connection-state
  :stream-runtime ws-client/stream-runtime
  :append-diagnostics-event! append-diagnostics-event!
  :sync-websocket-health! sync-websocket-health!
  :on-websocket-connected! address-watcher/on-websocket-connected!
  :on-websocket-disconnected! address-watcher/on-websocket-disconnected!})

(defn reload []
  (println "Reloading Hyperopen...")
  (wallet/set-on-connected-handler! handle-wallet-connected)
  (when (exists? js/document)
    (r/render (.getElementById js/document "app") (app-view/app-view @store))))

(def ^:private deferred-bootstrap-delay-ms 1200)
(def ^:private per-dex-stagger-ms 120)

(defonce ^:private startup-runtime
  (atom (startup-runtime-lib/default-startup-runtime-state)))

(defn- mark-performance!
  [mark-name]
  (startup-runtime-lib/mark-performance! mark-name))

(defn- schedule-idle-or-timeout!
  [f]
  (startup-runtime-lib/schedule-idle-or-timeout! deferred-bootstrap-delay-ms f))

(defn- schedule-startup-summary-log!
  []
  (startup-runtime-lib/schedule-startup-summary-log!
   {:startup-runtime startup-runtime
    :store store
    :get-request-stats api/get-request-stats
    :delay-ms 5000
    :log-fn println}))

(defn- register-icon-service-worker!
  []
  (startup-runtime-lib/register-icon-service-worker!
   {:icon-service-worker-path icon-service-worker-path
    :log-fn println}))

(defn- stage-b-account-bootstrap!
  [address dexs]
  (startup-runtime-lib/stage-b-account-bootstrap!
   {:store store
    :address address
    :dexs dexs
    :per-dex-stagger-ms per-dex-stagger-ms
    :fetch-frontend-open-orders! api/fetch-frontend-open-orders!
    :fetch-clearinghouse-state! api/fetch-clearinghouse-state!}))

(defn- bootstrap-account-data!
  [address]
  (startup-runtime-lib/bootstrap-account-data!
   {:startup-runtime startup-runtime
    :store store
    :address address
    :fetch-frontend-open-orders! api/fetch-frontend-open-orders!
    :fetch-user-fills! api/fetch-user-fills!
    :fetch-spot-clearinghouse-state! api/fetch-spot-clearinghouse-state!
    :fetch-user-abstraction! api/fetch-user-abstraction!
    :fetch-and-merge-funding-history! account-history-effects/fetch-and-merge-funding-history!
    :ensure-perp-dexs! api/ensure-perp-dexs!
    :stage-b-account-bootstrap! stage-b-account-bootstrap!
    :log-fn println}))

(defn- reify-address-handler
  [on-address-changed-fn handler-name]
  (reify address-watcher/IAddressChangeHandler
    (on-address-changed [_ _ new-address]
      (on-address-changed-fn new-address))
    (get-handler-name [_]
      handler-name)))

(defn- install-address-handlers!
  []
  (startup-runtime-lib/install-address-handlers!
   {:store store
    :startup-runtime startup-runtime
    :bootstrap-account-data! bootstrap-account-data!
    :init-with-webdata2! address-watcher/init-with-webdata2!
    :add-handler! address-watcher/add-handler!
    :sync-current-address! address-watcher/sync-current-address!
    :create-user-handler user-ws/create-user-handler
    :subscribe-user! user-ws/subscribe-user!
    :unsubscribe-user! user-ws/unsubscribe-user!
    :subscribe-webdata2! webdata2/subscribe-webdata2!
    :unsubscribe-webdata2! webdata2/unsubscribe-webdata2!
    :address-handler-reify reify-address-handler
    :address-handler-name "startup-account-bootstrap-handler"}))

(defn- start-critical-bootstrap!
  []
  (startup-runtime-lib/start-critical-bootstrap!
   {:store store
    :fetch-asset-contexts! api/fetch-asset-contexts!
    :fetch-asset-selector-markets! api/fetch-asset-selector-markets!
    :mark-performance! mark-performance!}))

(defn- run-deferred-bootstrap!
  []
  (startup-runtime-lib/run-deferred-bootstrap!
   {:store store
    :fetch-asset-selector-markets! api/fetch-asset-selector-markets!
    :mark-performance! mark-performance!}))

(defn- schedule-deferred-bootstrap!
  []
  (startup-runtime-lib/schedule-deferred-bootstrap!
   {:startup-runtime startup-runtime
    :schedule-idle-or-timeout! schedule-idle-or-timeout!
    :run-deferred-bootstrap! run-deferred-bootstrap!}))

(defn initialize-remote-data-streams!
  []
  (startup-runtime-lib/initialize-remote-data-streams!
   {:store store
    :ws-url "wss://api.hyperliquid.xyz/ws"
    :log-fn println
    :init-connection! ws-client/init-connection!
    :init-active-ctx! active-ctx/init!
    :init-orderbook! orderbook/init!
    :init-trades! trades/init!
    :init-user-ws! user-ws/init!
    :init-webdata2! webdata2/init!
    :dispatch! nxr/dispatch
    :install-address-handlers! install-address-handlers!
    :start-critical-bootstrap! start-critical-bootstrap!
    :schedule-deferred-bootstrap! schedule-deferred-bootstrap!}))

(defn init []
  (startup-init/init!
   {:log-fn println
    :startup-runtime startup-runtime
    :default-startup-runtime-state startup-runtime-lib/default-startup-runtime-state
    :mark-performance! mark-performance!
    :schedule-startup-summary-log! schedule-startup-summary-log!
    :store store
    :restore-ui-font-preference! restore-ui-font-preference!
    :restore-asset-selector-sort-settings! asset-selector-settings/restore-asset-selector-sort-settings!
    :restore-chart-options! restore-chart-options!
    :restore-orderbook-ui! restore-orderbook-ui!
    :restore-agent-storage-mode! restore-agent-storage-mode!
    :restore-active-asset! restore-active-asset!
    :restore-asset-selector-markets-cache! restore-asset-selector-markets-cache!
    :restore-open-orders-sort-settings! restore-open-orders-sort-settings!
    :restore-funding-history-pagination-settings! restore-funding-history-pagination-settings!
    :restore-trade-history-pagination-settings! restore-trade-history-pagination-settings!
    :restore-order-history-pagination-settings! restore-order-history-pagination-settings!
    :set-on-connected-handler! wallet/set-on-connected-handler!
    :handle-wallet-connected handle-wallet-connected
    :init-wallet! wallet/init-wallet!
    :init-router! router/init!
    :register-icon-service-worker! register-icon-service-worker!
    :initialize-remote-data-streams! initialize-remote-data-streams!
    :kick-render! (fn [runtime-store]
                    (swap! runtime-store identity))}))
