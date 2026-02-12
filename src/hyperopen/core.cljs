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
            [hyperopen.websocket.diagnostics-effects :as diagnostics-effects]
            [hyperopen.websocket.diagnostics-runtime :as diagnostics-runtime]
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
            [hyperopen.runtime.app-effects :as app-effects]
            [hyperopen.runtime.api-effects :as api-effects]
            [hyperopen.runtime.bootstrap :as runtime-bootstrap]
            [hyperopen.runtime.collaborators :as runtime-collaborators]
            [hyperopen.runtime.registry-composition :as registry-composition]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.startup.collaborators :as startup-collaborators]
            [hyperopen.startup.composition :as startup-composition]
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
            [hyperopen.state.app-defaults :as app-defaults]
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
(defonce store
  (atom
   (app-defaults/default-app-state
    {:websocket-health (ws-client/get-health-snapshot)
     :default-agent-state (agent-session/default-agent-state)
     :default-order-form (trading/default-order-form)
     :default-trade-history (default-trade-history-state)
     :default-funding-history (default-funding-history-state)
     :default-order-history (default-order-history-state)})))

(defn- websocket-health-fingerprint [health]
  (health-projection/websocket-health-fingerprint health))

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
         runtime-state/diagnostics-timeline-limit))

(defn- sync-websocket-health!
  [store & {:keys [force?]}]
  (health-runtime/sync-websocket-health!
   {:store store
    :force? force?
    :get-health-snapshot ws-client/get-health-snapshot
    :websocket-health-fingerprint websocket-health-fingerprint
    :projection-state runtime-state/websocket-health-projection-state
    :sync-stats runtime-state/websocket-health-sync-stats
    :auto-recover-enabled-fn health-runtime/auto-recover-enabled?
    :auto-recover-severe-threshold-ms runtime-state/auto-recover-severe-threshold-ms
    :auto-recover-cooldown-ms runtime-state/auto-recover-cooldown-ms
    :dispatch! nxr/dispatch
    :append-diagnostics-event! append-diagnostics-event!
    :queue-microtask-fn js/queueMicrotask}))

(defn- set-copy-status!
  [store status]
  (swap! store assoc-in [:websocket-ui :copy-status] status))

;; Effects - handle side effects
(defn save [_ store path value]
  (app-effects/save! store path value))

(defn save-many [_ store path-values]
  (app-effects/save-many! store path-values))

(defn local-storage-set [_ _ key value]
  (app-effects/local-storage-set! key value))

(defn local-storage-set-json [_ _ key value]
  (app-effects/local-storage-set-json! key value))

(defn- schedule-animation-frame! [f]
  (if (fn? (.-requestAnimationFrame js/globalThis))
    (.requestAnimationFrame js/globalThis f)
    (js/setTimeout f 16)))

(defn- flush-queued-asset-icon-statuses! [store]
  (icon-status-runtime/flush-queued-asset-icon-statuses!
   {:store store
    :pending-statuses runtime-state/pending-asset-icon-statuses
    :flush-handle runtime-state/asset-icon-status-flush-handle
    :apply-asset-icon-status-updates-fn asset-actions/apply-asset-icon-status-updates
    :save-many! (fn [runtime-store path-values]
                  (save-many nil runtime-store path-values))}))

(defn queue-asset-icon-status [_ store payload]
  (icon-status-runtime/queue-asset-icon-status!
   {:store store
    :payload payload
    :pending-statuses runtime-state/pending-asset-icon-statuses
    :flush-handle runtime-state/asset-icon-status-flush-handle
    :schedule-animation-frame! schedule-animation-frame!
    :flush-queued-asset-icon-statuses! flush-queued-asset-icon-statuses!}))

(defn push-state [_ _ path]
  (app-effects/push-state! path))

(defn replace-state [_ _ path]
  (app-effects/replace-state! path))

(defn fetch-candle-snapshot [_ store & {:keys [interval bars] :or {interval :1d bars 330}}]
  (app-effects/fetch-candle-snapshot!
   {:store store
    :interval interval
    :bars bars
    :log-fn println
    :fetch-candle-snapshot-fn api/fetch-candle-snapshot!}))

(defn init-websocket [_ store]
  (app-effects/init-websocket!
   {:store store
    :ws-url "wss://api.hyperliquid.xyz/ws"
    :log-fn println
    :init-connection! ws-client/init-connection!}))

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
   runtime-state/wallet-copy-feedback-timeout-id
   js/clearTimeout))

(defn- set-order-feedback-toast! [store kind message]
  (order-feedback-runtime/set-order-feedback-toast! store kind message))

(defn- clear-order-feedback-toast! [store]
  (order-feedback-runtime/clear-order-feedback-toast! store))

(defn- clear-order-feedback-toast-timeout! []
  (order-feedback-runtime/clear-order-feedback-toast-timeout!
   runtime-state/order-feedback-toast-timeout-id
   js/clearTimeout))

(defn- schedule-order-feedback-toast-clear! [store]
  (order-feedback-runtime/schedule-order-feedback-toast-clear!
   {:store store
    :order-feedback-toast-timeout-id runtime-state/order-feedback-toast-timeout-id
    :clear-order-feedback-toast! clear-order-feedback-toast!
    :clear-order-feedback-toast-timeout! clear-order-feedback-toast-timeout!
    :order-feedback-toast-duration-ms runtime-state/order-feedback-toast-duration-ms
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
    :agent-storage-mode-reset-message runtime-state/agent-storage-mode-reset-message}))

(defn- schedule-wallet-copy-feedback-clear! [store]
  (wallet-copy-runtime/schedule-wallet-copy-feedback-clear!
   {:store store
    :wallet-copy-feedback-timeout-id runtime-state/wallet-copy-feedback-timeout-id
    :clear-wallet-copy-feedback! clear-wallet-copy-feedback!
    :clear-wallet-copy-feedback-timeout! clear-wallet-copy-feedback-timeout!
    :wallet-copy-feedback-duration-ms runtime-state/wallet-copy-feedback-duration-ms
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
  (app-effects/reconnect-websocket!
   {:log-fn println
    :force-reconnect! ws-client/force-reconnect!}))

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
    :reset-subscriptions-cooldown-ms runtime-state/reset-subscriptions-cooldown-ms
    :send-message! ws-client/send-message!
    :append-diagnostics-event! append-diagnostics-event!}))

(defn confirm-ws-diagnostics-reveal [_ store]
  (diagnostics-effects/confirm-ws-diagnostics-reveal!
   {:store store
    :confirm-fn js/confirm}))

(defn copy-websocket-diagnostics [_ store]
  (diagnostics-effects/copy-websocket-diagnostics!
   {:store store
    :app-version runtime-state/app-version
    :set-copy-status! set-copy-status!
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
   :reconnect-cooldown-ms runtime-state/reconnect-cooldown-ms})

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
  (api-effects/fetch-asset-selector-markets!
   {:store store
    :opts opts
    :fetch-asset-selector-markets-fn api/fetch-asset-selector-markets!}))

(defn- api-load-user-data-effect
  [_ store address]
  (api-effects/load-user-data!
   {:store store
    :address address
    :fetch-frontend-open-orders! api/fetch-frontend-open-orders!
    :fetch-user-fills! api/fetch-user-fills!
    :fetch-and-merge-funding-history! account-history-effects/fetch-and-merge-funding-history!}))

(defn navigate
  [state path & [opts]]
  (let [p (router/normalize-path path)
        replace? (boolean (:replace? opts))]
    (cond-> [[:effects/save [:router :path] p]]
      replace? (conj [:effects/replace-state p])
      (not replace?) (conj [:effects/push-state p]))))

(defn- runtime-effect-deps
  []
  (runtime-collaborators/runtime-effect-deps
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
    :api-submit-order api-submit-order
    :api-cancel-order api-cancel-order
    :api-load-user-data api-load-user-data-effect}))

(defn- runtime-action-deps
  []
  (runtime-collaborators/runtime-action-deps
   {:init-websockets init-websockets
    :subscribe-to-asset subscribe-to-asset
    :subscribe-to-webdata2 subscribe-to-webdata2
    :enable-agent-trading-action enable-agent-trading-action
    :set-agent-storage-mode-action set-agent-storage-mode-action
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
    :refresh-asset-markets refresh-asset-markets
    :load-user-data load-user-data
    :set-funding-modal set-funding-modal
    :navigate navigate}))

(defn- runtime-registration-deps
  []
  (registry-composition/runtime-registration-deps
   {:register-effects! runtime-registry/register-effects!
    :register-actions! runtime-registry/register-actions!
    :register-system-state! runtime-registry/register-system-state!
    :register-placeholders! runtime-registry/register-placeholders!}
   {:effect-deps (runtime-effect-deps)
    :action-deps (runtime-action-deps)}))

(defn- register-runtime!
  []
  (runtime-bootstrap/register-runtime! (runtime-registration-deps)))

(defn- render-app!
  [state]
  (when (exists? js/document)
    (r/render (.getElementById js/document "app")
              (app-view/app-view state))))

(defn- store-cache-watcher-deps
  []
  {:persist-active-market-display! persist-active-market-display!
   :persist-asset-selector-markets-cache! persist-asset-selector-markets-cache!})

(defn- websocket-watcher-deps
  []
  {:store store
   :connection-state ws-client/connection-state
   :stream-runtime ws-client/stream-runtime
   :append-diagnostics-event! append-diagnostics-event!
   :sync-websocket-health! sync-websocket-health!
   :on-websocket-connected! address-watcher/on-websocket-connected!
   :on-websocket-disconnected! address-watcher/on-websocket-disconnected!})

(defn- bootstrap-runtime!
  []
  (runtime-bootstrap/bootstrap-runtime!
   {:register-runtime-deps (runtime-registration-deps)
    :render-loop-deps {:store store
                       :render-watch-key ::render
                       :set-dispatch! r/set-dispatch!
                       :dispatch! nxr/dispatch
                       :render! render-app!
                       :document? (exists? js/document)}
    :watchers-deps {:store store
                    :install-store-cache-watchers! startup-watchers/install-store-cache-watchers!
                    :store-cache-watchers-deps (store-cache-watcher-deps)
                    :install-websocket-watchers! startup-watchers/install-websocket-watchers!
                    :websocket-watchers-deps (websocket-watcher-deps)}}))

(bootstrap-runtime!)

(defn reload []
  (println "Reloading Hyperopen...")
  (wallet/set-on-connected-handler! handle-wallet-connected)
  (render-app! @store))

(defonce ^:private startup-runtime
  (atom (startup-runtime-lib/default-startup-runtime-state)))

(defn- mark-performance!
  [mark-name]
  (startup-runtime-lib/mark-performance! mark-name))

(defn- schedule-idle-or-timeout!
  [f]
  (startup-runtime-lib/schedule-idle-or-timeout! runtime-state/deferred-bootstrap-delay-ms f))

(defn- startup-base-deps
  []
  (startup-collaborators/startup-base-deps
   {:startup-runtime startup-runtime
    :store store
    :icon-service-worker-path runtime-state/icon-service-worker-path
    :per-dex-stagger-ms runtime-state/per-dex-stagger-ms
    :schedule-idle-or-timeout! schedule-idle-or-timeout!
    :mark-performance! mark-performance!}))

(defn- schedule-startup-summary-log!
  []
  (startup-composition/schedule-startup-summary-log!
   (assoc (startup-base-deps)
          :delay-ms runtime-state/startup-summary-delay-ms)))

(defn- register-icon-service-worker!
  []
  (startup-composition/register-icon-service-worker!
   (startup-base-deps)))

(defn- stage-b-account-bootstrap!
  [address dexs]
  (startup-composition/stage-b-account-bootstrap!
   (startup-base-deps)
   address
   dexs))

(defn- bootstrap-account-data!
  [address]
  (startup-composition/bootstrap-account-data!
   (assoc (startup-base-deps)
          :stage-b-account-bootstrap! stage-b-account-bootstrap!)
   address))

(defn- reify-address-handler
  [on-address-changed-fn handler-name]
  (reify address-watcher/IAddressChangeHandler
    (on-address-changed [_ _ new-address]
      (on-address-changed-fn new-address))
    (get-handler-name [_]
      handler-name)))

(defn- install-address-handlers!
  []
  (startup-composition/install-address-handlers!
   (assoc (startup-base-deps)
          :bootstrap-account-data! bootstrap-account-data!
          :address-handler-reify reify-address-handler
          :address-handler-name "startup-account-bootstrap-handler")))

(defn- start-critical-bootstrap!
  []
  (startup-composition/start-critical-bootstrap!
   (startup-base-deps)))

(defn- run-deferred-bootstrap!
  []
  (startup-composition/run-deferred-bootstrap!
   (startup-base-deps)))

(defn- schedule-deferred-bootstrap!
  []
  (startup-composition/schedule-deferred-bootstrap!
   (assoc (startup-base-deps)
          :run-deferred-bootstrap! run-deferred-bootstrap!)))

(defn initialize-remote-data-streams!
  []
  (startup-composition/initialize-remote-data-streams!
   (assoc (startup-base-deps)
          :install-address-handlers! install-address-handlers!
          :start-critical-bootstrap! start-critical-bootstrap!
          :schedule-deferred-bootstrap! schedule-deferred-bootstrap!)))

(defn init []
  (startup-composition/init!
   (merge
    (startup-base-deps)
    {:default-startup-runtime-state startup-runtime-lib/default-startup-runtime-state
     :schedule-startup-summary-log! schedule-startup-summary-log!
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
                     (swap! runtime-store identity))})))
