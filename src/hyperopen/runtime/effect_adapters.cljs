(ns hyperopen.runtime.effect-adapters
  (:require [clojure.set :as set]
            [nexus.registry :as nxr]
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
            [hyperopen.asset-selector.query :as asset-selector-query]
            [hyperopen.funding.history-cache :as funding-cache]
            [hyperopen.funding.predictability :as funding-predictability]
            [hyperopen.orderbook.price-aggregation :as price-agg]
            [hyperopen.runtime.app-effects :as app-effects]
            [hyperopen.runtime.api-effects :as api-effects]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.startup.restore :as startup-restore]
            [hyperopen.funding.effects :as funding-workflow-effects]
            [hyperopen.funding-comparison.effects :as funding-effects]
            [hyperopen.vaults.effects :as vault-effects]
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
            [hyperopen.websocket.market-projection-runtime :as market-projection-runtime]
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

(defn- market-projection-flush-events
  []
  (->> (telemetry/market-projection-flush-events)
       (mapv (fn [entry]
               (select-keys entry
                            [:seq
                             :event
                             :at-ms
                             :store-id
                             :pending-count
                             :overwrite-count
                             :flush-duration-ms
                             :queue-wait-ms
                             :flush-count
                             :max-pending-depth
                             :p95-flush-duration-ms
                             :queued-total
                             :overwrite-total])))))

(defn- market-projection-diagnostics
  []
  (let [snapshot (market-projection-runtime/market-projection-telemetry-snapshot)
        flush-events (market-projection-flush-events)
        latest-event (last flush-events)]
    {:stores (:stores snapshot)
     :flush-events flush-events
     :flush-event-limit telemetry/market-projection-flush-event-limit
     :flush-event-count (count flush-events)
     :latest-flush-event-seq (:seq latest-event)
     :latest-flush-at-ms (:at-ms latest-event)}))

(defn- enrich-health-with-market-projection
  [health]
  (assoc (or health {})
         :market-projection
         (market-projection-diagnostics)))

(defn sync-websocket-health-with-runtime!
  [_runtime store & {:keys [force? projected-fingerprint]}]
  (health-runtime/sync-websocket-health!
   {:store store
    :force? force?
    :projected-fingerprint projected-fingerprint
    :get-health-snapshot (fn []
                           (enrich-health-with-market-projection
                            (ws-client/get-health-snapshot)))
    :websocket-health-fingerprint websocket-health-fingerprint
    :projection-state ws-client/websocket-health-projection-state
    :auto-recover-enabled-fn health-runtime/auto-recover-enabled?
    :auto-recover-severe-threshold-ms runtime-state/auto-recover-severe-threshold-ms
    :auto-recover-cooldown-ms runtime-state/auto-recover-cooldown-ms
    :dispatch! nxr/dispatch
    :append-diagnostics-event! append-diagnostics-event!
    :queue-microtask-fn platform/queue-microtask!}))

(defn sync-websocket-health!
  [store & {:keys [force? projected-fingerprint]}]
  (sync-websocket-health-with-runtime! nil
                                       store
                                       :force? force?
                                       :projected-fingerprint projected-fingerprint))

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
    :or {log-fn telemetry/log!
         request-candle-snapshot-fn api/request-candle-snapshot!
         apply-candle-snapshot-success api-projections/apply-candle-snapshot-success
         apply-candle-snapshot-error api-projections/apply-candle-snapshot-error}}]
  (fn [_ store & {:keys [coin interval bars] :or {interval :1d bars 330}}]
    (app-effects/fetch-candle-snapshot!
     {:store store
      :coin coin
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
         log-fn telemetry/log!
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

(defn- parse-market-index [value]
  (markets-cache/parse-market-index value))

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
   :parse-max-leverage parse-max-leverage
   :parse-market-index parse-market-index})

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
    :log-fn telemetry/log!
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
    :log-fn telemetry/log!
    :unsubscribe-active-asset-ctx! active-ctx/unsubscribe-active-asset-ctx!}))

(defn subscribe-orderbook [_ store coin]
  (subscriptions-runtime/subscribe-orderbook!
   {:store store
    :coin coin
    :log-fn telemetry/log!
    :normalize-mode-fn price-agg/normalize-mode
    :mode->subscription-config-fn price-agg/mode->subscription-config
    :subscribe-orderbook-fn orderbook/subscribe-orderbook!}))

(defn subscribe-trades [_ store coin]
  (subscriptions-runtime/subscribe-trades!
   {:coin coin
    :log-fn telemetry/log!
    :subscribe-trades-fn trades/subscribe-trades!}))

(defn unsubscribe-orderbook [_ store coin]
  (subscriptions-runtime/unsubscribe-orderbook!
   {:store store
    :coin coin
    :log-fn telemetry/log!
    :unsubscribe-orderbook-fn orderbook/unsubscribe-orderbook!}))

(defn unsubscribe-trades [_ store coin]
  (subscriptions-runtime/unsubscribe-trades!
   {:coin coin
    :log-fn telemetry/log!
    :unsubscribe-trades-fn trades/unsubscribe-trades!}))

(defn subscribe-webdata2 [_ store address]
  (subscriptions-runtime/subscribe-webdata2!
   {:address address
    :log-fn telemetry/log!
    :subscribe-webdata2-fn webdata2/subscribe-webdata2!}))

(defn unsubscribe-webdata2 [_ store address]
  (subscriptions-runtime/unsubscribe-webdata2!
   {:address address
    :log-fn telemetry/log!
    :unsubscribe-webdata2-fn webdata2/unsubscribe-webdata2!}))

(def ^:private asset-selector-active-ctx-owner
  :asset-selector)

(defn sync-asset-selector-active-ctx-subscriptions [_ store]
  (let [state @store
        desired-coins (asset-selector-query/selector-visible-market-coins state)
        owned-coins (active-ctx/get-subscribed-coins-by-owner asset-selector-active-ctx-owner)
        subscribe-coins (sort (set/difference desired-coins owned-coins))
        unsubscribe-coins (sort (set/difference owned-coins desired-coins))]
    (doseq [coin subscribe-coins]
      (active-ctx/subscribe-active-asset-ctx! coin asset-selector-active-ctx-owner))
    (doseq [coin unsubscribe-coins]
      (active-ctx/unsubscribe-active-asset-ctx! coin asset-selector-active-ctx-owner))))

(defn connect-wallet [_ store]
  (wallet-connection-runtime/connect-wallet!
   {:store store
    :log-fn telemetry/log!
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
     :log-fn telemetry/log!
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
     :log-fn telemetry/log!})))

(defn make-copy-wallet-address
  [runtime]
  (fn [ctx store address]
    (copy-wallet-address runtime ctx store address)))

(defn make-reconnect-websocket
  [{:keys [log-fn force-reconnect!]
    :or {log-fn telemetry/log!
         force-reconnect! ws-client/force-reconnect!}}]
  (fn [_ _]
    (app-effects/reconnect-websocket!
     {:log-fn log-fn
      :force-reconnect! force-reconnect!})))

(def reconnect-websocket
  (make-reconnect-websocket {}))

(defn refresh-websocket-health
  ([_ store]
   (refresh-websocket-health nil nil store))
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
    :log-fn telemetry/log!}))

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

(defn api-submit-position-tpsl
  ([ctx store request]
   (api-submit-position-tpsl runtime-state/runtime ctx store request))
  ([runtime ctx store request]
   (order-effects/api-submit-position-tpsl (order-api-effect-deps runtime) ctx store request)))

(defn api-submit-position-margin
  ([ctx store request]
   (api-submit-position-margin runtime-state/runtime ctx store request))
  ([runtime ctx store request]
   (order-effects/api-submit-position-margin (order-api-effect-deps runtime) ctx store request)))

(defn make-api-submit-order
  [runtime]
  (fn [ctx store request]
    (api-submit-order runtime ctx store request)))

(defn make-api-cancel-order
  [runtime]
  (fn [ctx store request]
    (api-cancel-order runtime ctx store request)))

(defn make-api-submit-position-tpsl
  [runtime]
  (fn [ctx store request]
    (api-submit-position-tpsl runtime ctx store request)))

(defn make-api-submit-position-margin
  [runtime]
  (fn [ctx store request]
    (api-submit-position-margin runtime ctx store request)))

(defn- funding-predictability-path
  [bucket coin]
  [:active-assets :funding-predictability bucket coin])

(defn- set-funding-predictability-loading!
  [store coin loading?]
  (swap! store
         (fn [state]
           (-> state
               (assoc-in (funding-predictability-path :loading-by-coin coin)
                         loading?)
               (assoc-in (funding-predictability-path :error-by-coin coin)
                         nil)))))

(defn- set-funding-predictability-success!
  [store coin summary loaded-at-ms]
  (swap! store
         (fn [state]
           (-> state
               (assoc-in (funding-predictability-path :loading-by-coin coin)
                         false)
               (assoc-in (funding-predictability-path :error-by-coin coin)
                         nil)
               (assoc-in (funding-predictability-path :by-coin coin)
                         summary)
               (assoc-in (funding-predictability-path :loaded-at-ms-by-coin coin)
                         loaded-at-ms)))))

(defn- set-funding-predictability-error!
  [store coin error-message loaded-at-ms]
  (swap! store
         (fn [state]
           (-> state
               (assoc-in (funding-predictability-path :loading-by-coin coin)
                         false)
               (assoc-in (funding-predictability-path :error-by-coin coin)
                         error-message)
               (assoc-in (funding-predictability-path :loaded-at-ms-by-coin coin)
                         loaded-at-ms)))))

(defn sync-active-asset-funding-predictability
  [_ store coin]
  (if-let [coin* (funding-cache/normalize-coin coin)]
    (do
      (set-funding-predictability-loading! store coin* true)
      (-> (apply funding-cache/sync-market-funding-history-cache!
                 [coin*])
          (.then (fn [{:keys [rows]}]
                   (let [now-ms (platform/now-ms)
                         rows* (funding-cache/rows-for-window rows
                                                              now-ms
                                                              funding-predictability/thirty-day-window-ms)
                         summary (funding-predictability/compute-30d-summary rows* now-ms)]
                     (set-funding-predictability-success! store coin* summary now-ms))))
          (.catch (fn [error]
                    (let [now-ms (platform/now-ms)
                          error-message (or (some-> error .-message)
                                            (str error))]
                      (set-funding-predictability-error! store coin* error-message now-ms))))))
    (js/Promise.resolve nil)))

(defn fetch-asset-selector-markets-effect
  [_ store & [opts]]
  (api-effects/fetch-asset-selector-markets!
   {:store store
    :opts opts
    :request-asset-selector-markets-fn api/request-asset-selector-markets!
    :begin-asset-selector-load api-projections/begin-asset-selector-load
    :apply-asset-selector-success api-projections/apply-asset-selector-success
    :apply-asset-selector-error api-projections/apply-asset-selector-error
    :after-asset-selector-success! (fn [runtime-store _phase _market-state]
                                     (sync-asset-selector-active-ctx-subscriptions nil runtime-store))}))

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

(defn api-fetch-predicted-fundings-effect
  [_ store]
  (funding-effects/api-fetch-predicted-fundings!
   {:store store
    :request-predicted-fundings! api/request-predicted-fundings!
    :begin-funding-comparison-load api-projections/begin-funding-comparison-load
    :apply-funding-comparison-success api-projections/apply-funding-comparison-success
    :apply-funding-comparison-error api-projections/apply-funding-comparison-error}))

(defn api-fetch-vault-index-effect
  [_ store]
  (vault-effects/api-fetch-vault-index!
   {:store store
    :request-vault-index! api/request-vault-index!
    :begin-vault-index-load api-projections/begin-vault-index-load
    :apply-vault-index-success api-projections/apply-vault-index-success
    :apply-vault-index-error api-projections/apply-vault-index-error}))

(defn api-fetch-vault-summaries-effect
  [_ store]
  (vault-effects/api-fetch-vault-summaries!
   {:store store
    :request-vault-summaries! api/request-vault-summaries!
    :begin-vault-summaries-load api-projections/begin-vault-summaries-load
    :apply-vault-summaries-success api-projections/apply-vault-summaries-success
    :apply-vault-summaries-error api-projections/apply-vault-summaries-error}))

(defn api-fetch-user-vault-equities-effect
  [_ store address]
  (vault-effects/api-fetch-user-vault-equities!
   {:store store
    :address address
    :request-user-vault-equities! api/request-user-vault-equities!
    :begin-user-vault-equities-load api-projections/begin-user-vault-equities-load
    :apply-user-vault-equities-success api-projections/apply-user-vault-equities-success
    :apply-user-vault-equities-error api-projections/apply-user-vault-equities-error}))

(defn api-fetch-vault-details-effect
  [_ store vault-address user-address]
  (vault-effects/api-fetch-vault-details!
   {:store store
    :vault-address vault-address
    :user-address user-address
    :request-vault-details! api/request-vault-details!
    :begin-vault-details-load api-projections/begin-vault-details-load
    :apply-vault-details-success api-projections/apply-vault-details-success
    :apply-vault-details-error api-projections/apply-vault-details-error}))

(defn api-fetch-vault-webdata2-effect
  [_ store vault-address]
  (vault-effects/api-fetch-vault-webdata2!
   {:store store
    :vault-address vault-address
    :request-vault-webdata2! api/request-vault-webdata2!
    :begin-vault-webdata2-load api-projections/begin-vault-webdata2-load
    :apply-vault-webdata2-success api-projections/apply-vault-webdata2-success
    :apply-vault-webdata2-error api-projections/apply-vault-webdata2-error}))

(defn api-fetch-vault-fills-effect
  [_ store vault-address]
  (vault-effects/api-fetch-vault-fills!
   {:store store
    :vault-address vault-address
    :request-user-fills! api/request-user-fills!
    :begin-vault-fills-load api-projections/begin-vault-fills-load
    :apply-vault-fills-success api-projections/apply-vault-fills-success
    :apply-vault-fills-error api-projections/apply-vault-fills-error}))

(defn api-fetch-vault-funding-history-effect
  [_ store vault-address]
  (vault-effects/api-fetch-vault-funding-history!
   {:store store
    :vault-address vault-address
    :request-user-funding-history! api/request-user-funding-history!
    :begin-vault-funding-history-load api-projections/begin-vault-funding-history-load
    :apply-vault-funding-history-success api-projections/apply-vault-funding-history-success
    :apply-vault-funding-history-error api-projections/apply-vault-funding-history-error}))

(defn api-fetch-vault-order-history-effect
  [_ store vault-address]
  (vault-effects/api-fetch-vault-order-history!
   {:store store
    :vault-address vault-address
    :request-historical-orders! api/request-historical-orders!
    :begin-vault-order-history-load api-projections/begin-vault-order-history-load
    :apply-vault-order-history-success api-projections/apply-vault-order-history-success
    :apply-vault-order-history-error api-projections/apply-vault-order-history-error}))

(defn api-fetch-vault-ledger-updates-effect
  [_ store vault-address]
  (vault-effects/api-fetch-vault-ledger-updates!
   {:store store
    :vault-address vault-address
    :request-user-non-funding-ledger-updates! api/request-user-non-funding-ledger-updates!
    :begin-vault-ledger-updates-load api-projections/begin-vault-ledger-updates-load
    :apply-vault-ledger-updates-success api-projections/apply-vault-ledger-updates-success
    :apply-vault-ledger-updates-error api-projections/apply-vault-ledger-updates-error}))

(defn api-submit-vault-transfer-effect
  [_ store request]
  (vault-effects/api-submit-vault-transfer!
   {:store store
    :request request
    :dispatch! nxr/dispatch
    :exchange-response-error exchange-response-error
    :runtime-error-message runtime-error-message
    :show-toast! show-order-feedback-toast!}))

(defn api-submit-funding-transfer-effect
  [_ store request]
  (funding-workflow-effects/api-submit-funding-transfer!
   {:store store
    :request request
    :dispatch! nxr/dispatch
    :exchange-response-error exchange-response-error
    :runtime-error-message runtime-error-message
    :show-toast! show-order-feedback-toast!}))

(defn api-submit-funding-withdraw-effect
  [_ store request]
  (funding-workflow-effects/api-submit-funding-withdraw!
   {:store store
    :request request
    :dispatch! nxr/dispatch
    :exchange-response-error exchange-response-error
    :runtime-error-message runtime-error-message
    :show-toast! show-order-feedback-toast!}))
