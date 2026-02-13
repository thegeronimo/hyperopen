(ns hyperopen.runtime.effect-adapters
  (:require [nexus.registry :as nxr]
            [hyperopen.platform :as platform]
            [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.account.history.effects :as account-history-effects]
            [hyperopen.order.effects :as order-effects]
            [hyperopen.order.feedback-runtime :as order-feedback-runtime]
            [hyperopen.asset-selector.active-market-cache :as active-market-cache]
            [hyperopen.asset-selector.actions :as asset-actions]
            [hyperopen.asset-selector.icon-status-runtime :as icon-status-runtime]
            [hyperopen.asset-selector.markets-cache :as markets-cache]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.orderbook.price-aggregation :as price-agg]
            [hyperopen.runtime.app-effects :as app-effects]
            [hyperopen.runtime.api-effects :as api-effects]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.startup.restore :as startup-restore]
            [hyperopen.wallet.agent-runtime :as agent-runtime]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.connection-runtime :as wallet-connection-runtime]
            [hyperopen.wallet.copy-feedback-runtime :as wallet-copy-runtime]
            [hyperopen.wallet.core :as wallet]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.diagnostics-effects :as diagnostics-effects]
            [hyperopen.websocket.diagnostics-runtime :as diagnostics-runtime]
            [hyperopen.websocket.health-projection :as health-projection]
            [hyperopen.websocket.health-runtime :as health-runtime]
            [hyperopen.websocket.orderbook :as orderbook]
            [hyperopen.websocket.subscriptions-runtime :as subscriptions-runtime]
            [hyperopen.websocket.trades :as trades]
            [hyperopen.websocket.webdata2 :as webdata2]))

(defn- websocket-health-fingerprint [health]
  (health-projection/websocket-health-fingerprint health))

(defn- effective-now-ms
  [generated-at-ms]
  (health-runtime/effective-now-ms generated-at-ms))

(defn append-diagnostics-event!
  [store event at-ms & [details]]
  (swap! store
         health-projection/append-diagnostics-event
         event
         at-ms
         details
         runtime-state/diagnostics-timeline-limit))

(defn sync-websocket-health-with-runtime!
  [runtime store & {:keys [force?]}]
  (health-runtime/sync-websocket-health!
   {:store store
    :force? force?
    :get-health-snapshot ws-client/get-health-snapshot
    :websocket-health-fingerprint websocket-health-fingerprint
    :runtime runtime
    :auto-recover-enabled-fn health-runtime/auto-recover-enabled?
    :auto-recover-severe-threshold-ms runtime-state/auto-recover-severe-threshold-ms
    :auto-recover-cooldown-ms runtime-state/auto-recover-cooldown-ms
    :dispatch! nxr/dispatch
    :append-diagnostics-event! append-diagnostics-event!
    :queue-microtask-fn platform/queue-microtask!}))

(defn sync-websocket-health!
  [store & {:keys [force?]}]
  (sync-websocket-health-with-runtime! runtime-state/runtime store :force? force?))

(defn- set-copy-status!
  [store status]
  (swap! store assoc-in [:websocket-ui :copy-status] status))

(defn- effect-handler-store-1
  [f]
  (fn [_ store arg]
    (f store arg)))

(defn- effect-handler-store-2
  [f]
  (fn [_ store arg1 arg2]
    (f store arg1 arg2)))

(defn- effect-handler-1
  [f]
  (fn [_ _ arg]
    (f arg)))

(defn- effect-handler-2
  [f]
  (fn [_ _ arg1 arg2]
    (f arg1 arg2)))

(def save
  (effect-handler-store-2 #'app-effects/save!))

(def save-many
  (effect-handler-store-1 #'app-effects/save-many!))

(def local-storage-set
  (effect-handler-2 #'app-effects/local-storage-set!))

(def local-storage-set-json
  (effect-handler-2 #'app-effects/local-storage-set-json!))

(defn schedule-animation-frame! [f]
  (platform/request-animation-frame! f))

(defn flush-queued-asset-icon-statuses!
  ([store]
   (flush-queued-asset-icon-statuses! runtime-state/runtime store))
  ([runtime store]
   (icon-status-runtime/flush-queued-asset-icon-statuses!
    {:store store
     :runtime runtime
     :apply-asset-icon-status-updates-fn asset-actions/apply-asset-icon-status-updates
     :save-many! (fn [runtime-store path-values]
                   (save-many nil runtime-store path-values))})))

(defn queue-asset-icon-status
  ([_ store payload]
   (queue-asset-icon-status runtime-state/runtime nil store payload))
  ([runtime _ store payload]
   (icon-status-runtime/queue-asset-icon-status!
    {:store store
     :runtime runtime
     :payload payload
     :schedule-animation-frame! schedule-animation-frame!
     :flush-queued-asset-icon-statuses! (fn [runtime-store]
                                          (flush-queued-asset-icon-statuses! runtime runtime-store))})))

(defn make-queue-asset-icon-status
  [runtime]
  (fn [ctx store payload]
    (queue-asset-icon-status runtime ctx store payload)))

(def push-state
  (effect-handler-1 #'app-effects/push-state!))

(def replace-state
  (effect-handler-1 #'app-effects/replace-state!))

(defn make-fetch-candle-snapshot
  [{:keys [log-fn
           request-candle-snapshot-fn
           apply-candle-snapshot-success
           apply-candle-snapshot-error]
    :or {log-fn println
         request-candle-snapshot-fn api/request-candle-snapshot!
         apply-candle-snapshot-success api-projections/apply-candle-snapshot-success
         apply-candle-snapshot-error api-projections/apply-candle-snapshot-error}}]
  (fn [_ store & {:keys [interval bars] :or {interval :1d bars 330}}]
    (app-effects/fetch-candle-snapshot!
     {:store store
      :interval interval
      :bars bars
      :log-fn log-fn
      :request-candle-snapshot-fn request-candle-snapshot-fn
      :apply-candle-snapshot-success apply-candle-snapshot-success
      :apply-candle-snapshot-error apply-candle-snapshot-error})))

(def fetch-candle-snapshot
  (make-fetch-candle-snapshot {}))

(defn make-init-websocket
  [{:keys [ws-url log-fn init-connection!]
    :or {ws-url runtime-state/websocket-url
         log-fn println
         init-connection! ws-client/init-connection!}}]
  (fn [_ store]
    (app-effects/init-websocket!
     {:store store
      :ws-url ws-url
      :log-fn log-fn
      :init-connection! init-connection!})))

(def init-websocket
  (make-init-websocket {}))

(defn- normalize-market-type [value]
  (markets-cache/normalize-market-type value))

(defn- parse-max-leverage [value]
  (markets-cache/parse-max-leverage value))

(defn- normalize-display-text [value]
  (markets-cache/normalize-display-text value))

(defn persist-asset-selector-markets-cache!
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

(defn persist-active-market-display! [market]
  (active-market-cache/persist-active-market-display!
   market
   (active-market-display-normalize-deps)))

(defn load-active-market-display [active-asset]
  (active-market-cache/load-active-market-display
   active-asset
   (active-market-display-normalize-deps)))

(defn- persist-active-asset!
  [canonical-coin]
  (when (string? canonical-coin)
    (app-effects/local-storage-set! "active-asset" canonical-coin)))

(defn subscribe-active-asset [_ store coin]
  (subscriptions-runtime/subscribe-active-asset!
   {:store store
    :coin coin
    :log-fn println
    :resolve-market-by-coin-fn markets/resolve-market-by-coin
    :persist-active-asset! persist-active-asset!
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

(defn- clear-wallet-copy-feedback-timeout!
  [runtime]
  (wallet-copy-runtime/clear-wallet-copy-feedback-timeout-in-runtime!
   runtime
   platform/clear-timeout!))

(defn- set-order-feedback-toast! [store kind message]
  (order-feedback-runtime/set-order-feedback-toast! store kind message))

(defn- clear-order-feedback-toast! [store]
  (order-feedback-runtime/clear-order-feedback-toast! store))

(defn- clear-order-feedback-toast-timeout!
  [runtime]
  (order-feedback-runtime/clear-order-feedback-toast-timeout-in-runtime!
   runtime
   platform/clear-timeout!))

(defn- schedule-order-feedback-toast-clear! [runtime store]
  (order-feedback-runtime/schedule-order-feedback-toast-clear!
   {:store store
    :runtime runtime
    :clear-order-feedback-toast! clear-order-feedback-toast!
    :clear-order-feedback-toast-timeout! #(clear-order-feedback-toast-timeout! runtime)
    :order-feedback-toast-duration-ms runtime-state/order-feedback-toast-duration-ms
    :set-timeout-fn platform/set-timeout!}))

(defn- show-order-feedback-toast!
  ([store kind message]
   (show-order-feedback-toast! runtime-state/runtime store kind message))
  ([runtime store kind message]
   (order-feedback-runtime/show-order-feedback-toast!
    store
    kind
    message
    #(schedule-order-feedback-toast-clear! runtime %))))

(defn disconnect-wallet
  ([_ store]
   (disconnect-wallet runtime-state/runtime nil store))
  ([runtime _ store]
   (wallet-connection-runtime/disconnect-wallet!
    {:store store
     :log-fn println
     :clear-wallet-copy-feedback-timeout! #(clear-wallet-copy-feedback-timeout! runtime)
     :clear-order-feedback-toast-timeout! #(clear-order-feedback-toast-timeout! runtime)
     :clear-order-feedback-toast! clear-order-feedback-toast!
     :set-disconnected! wallet/set-disconnected!})))

(defn make-disconnect-wallet
  [runtime]
  (fn [ctx store]
    (disconnect-wallet runtime ctx store)))

(defn set-agent-storage-mode [_ store storage-mode]
  (agent-runtime/set-agent-storage-mode!
   {:store store
    :storage-mode storage-mode
    :normalize-storage-mode agent-session/normalize-storage-mode
    :clear-agent-session-by-mode! agent-session/clear-agent-session-by-mode!
    :persist-storage-mode-preference! agent-session/persist-storage-mode-preference!
    :default-agent-state agent-session/default-agent-state
    :agent-storage-mode-reset-message runtime-state/agent-storage-mode-reset-message}))

(defn- schedule-wallet-copy-feedback-clear! [runtime store]
  (wallet-copy-runtime/schedule-wallet-copy-feedback-clear!
   {:store store
    :runtime runtime
    :clear-wallet-copy-feedback! clear-wallet-copy-feedback!
    :clear-wallet-copy-feedback-timeout! #(clear-wallet-copy-feedback-timeout! runtime)
    :wallet-copy-feedback-duration-ms runtime-state/wallet-copy-feedback-duration-ms
    :set-timeout-fn platform/set-timeout!}))

(defn copy-wallet-address
  ([_ store address]
   (copy-wallet-address runtime-state/runtime nil store address))
  ([runtime _ store address]
   (wallet-copy-runtime/copy-wallet-address!
    {:store store
     :address address
     :set-wallet-copy-feedback! set-wallet-copy-feedback!
     :clear-wallet-copy-feedback! clear-wallet-copy-feedback!
     :clear-wallet-copy-feedback-timeout! #(clear-wallet-copy-feedback-timeout! runtime)
     :schedule-wallet-copy-feedback-clear! #(schedule-wallet-copy-feedback-clear! runtime %)
     :log-fn println})))

(defn make-copy-wallet-address
  [runtime]
  (fn [ctx store address]
    (copy-wallet-address runtime ctx store address)))

(defn make-reconnect-websocket
  [{:keys [log-fn force-reconnect!]
    :or {log-fn println
         force-reconnect! ws-client/force-reconnect!}}]
  (fn [_ _]
    (app-effects/reconnect-websocket!
     {:log-fn log-fn
      :force-reconnect! force-reconnect!})))

(def reconnect-websocket
  (make-reconnect-websocket {}))

(defn refresh-websocket-health
  ([_ store]
   (refresh-websocket-health runtime-state/runtime nil store))
  ([runtime _ store]
   (sync-websocket-health-with-runtime! runtime store :force? true)))

(defn make-refresh-websocket-health
  [runtime]
  (fn [ctx store]
    (refresh-websocket-health runtime ctx store)))

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
    :confirm-fn platform/confirm!}))

(defn copy-websocket-diagnostics [_ store]
  (diagnostics-effects/copy-websocket-diagnostics!
   {:store store
    :app-version runtime-state/app-version
    :set-copy-status! set-copy-status!
    :log-fn println}))

(defn- restore-active-asset-deps []
  {:connected?-fn ws-client/connected?
   :dispatch! nxr/dispatch
   :load-active-market-display-fn load-active-market-display})

(defn restore-active-asset! [store]
  (startup-restore/restore-active-asset! store (restore-active-asset-deps)))

(defn- exchange-response-error
  [resp]
  (agent-runtime/exchange-response-error resp))

(defn- runtime-error-message
  [err]
  (agent-runtime/runtime-error-message err))

(defn- order-api-effect-deps
  ([]
   (order-api-effect-deps runtime-state/runtime))
  ([runtime]
   {:dispatch! nxr/dispatch
    :exchange-response-error exchange-response-error
    :prune-canceled-open-orders-fn order-effects/prune-canceled-open-orders
    :runtime-error-message runtime-error-message
    :show-toast! (fn [store kind message]
                   (show-order-feedback-toast! runtime store kind message))}))

(defn api-submit-order
  ([ctx store request]
   (api-submit-order runtime-state/runtime ctx store request))
  ([runtime ctx store request]
   (order-effects/api-submit-order (order-api-effect-deps runtime) ctx store request)))

(defn api-cancel-order
  ([ctx store request]
   (api-cancel-order runtime-state/runtime ctx store request))
  ([runtime ctx store request]
   (order-effects/api-cancel-order (order-api-effect-deps runtime) ctx store request)))

(defn make-api-submit-order
  [runtime]
  (fn [ctx store request]
    (api-submit-order runtime ctx store request)))

(defn make-api-cancel-order
  [runtime]
  (fn [ctx store request]
    (api-cancel-order runtime ctx store request)))

(defn fetch-asset-selector-markets-effect
  [_ store & [opts]]
  (api-effects/fetch-asset-selector-markets!
   {:store store
    :opts opts
    :request-asset-selector-markets-fn api/request-asset-selector-markets!
    :begin-asset-selector-load api-projections/begin-asset-selector-load
    :apply-asset-selector-success api-projections/apply-asset-selector-success
    :apply-asset-selector-error api-projections/apply-asset-selector-error}))

(defn api-load-user-data-effect
  [_ store address]
  (api-effects/load-user-data!
   {:store store
    :address address
    :request-frontend-open-orders! api/request-frontend-open-orders!
    :request-user-fills! api/request-user-fills!
    :apply-open-orders-success api-projections/apply-open-orders-success
    :apply-open-orders-error api-projections/apply-open-orders-error
    :apply-user-fills-success api-projections/apply-user-fills-success
    :apply-user-fills-error api-projections/apply-user-fills-error
    :fetch-and-merge-funding-history! account-history-effects/fetch-and-merge-funding-history!}))
