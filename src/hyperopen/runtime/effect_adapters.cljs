(ns hyperopen.runtime.effect-adapters
  (:require [nexus.registry :as nxr]
            [hyperopen.platform :as platform]
            [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.account.history.effects :as account-history-effects]
            [hyperopen.order.effects :as order-effects]
            [hyperopen.order.feedback-runtime :as order-feedback-runtime]
            [hyperopen.funding.history-cache :as funding-cache]
            [hyperopen.funding.predictability :as funding-predictability]
            [hyperopen.runtime.effect-adapters.asset-selector :as asset-adapters]
            [hyperopen.runtime.effect-adapters.common :as common]
            [hyperopen.runtime.effect-adapters.wallet :as wallet-adapters]
            [hyperopen.runtime.effect-adapters.websocket :as ws-adapters]
            [hyperopen.runtime.api-effects :as api-effects]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.funding.effects :as funding-workflow-effects]
            [hyperopen.funding-comparison.effects :as funding-effects]
            [hyperopen.vaults.effects :as vault-effects]
            [hyperopen.websocket.client :as ws-client]))

(def append-diagnostics-event! ws-adapters/append-diagnostics-event!)

(def sync-websocket-health-with-runtime! ws-adapters/sync-websocket-health-with-runtime!)

(def sync-websocket-health! ws-adapters/sync-websocket-health!)

(def save common/save)

(def save-many common/save-many)

(def local-storage-set common/local-storage-set)

(def local-storage-set-json common/local-storage-set-json)

(def schedule-animation-frame! common/schedule-animation-frame!)

(defn flush-queued-asset-icon-statuses!
  ([store]
   (flush-queued-asset-icon-statuses! runtime-state/runtime store))
  ([runtime store]
   (asset-adapters/flush-queued-asset-icon-statuses!
    {:store store
     :runtime runtime
     :save-many! (fn [runtime-store path-values]
                   (save-many nil runtime-store path-values))})))

(defn queue-asset-icon-status
  ([_ store payload]
   (queue-asset-icon-status runtime-state/runtime nil store payload))
  ([runtime _ store payload]
   (asset-adapters/queue-asset-icon-status!
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

(def push-state common/push-state)

(def replace-state common/replace-state)

(def make-fetch-candle-snapshot ws-adapters/make-fetch-candle-snapshot)

(def fetch-candle-snapshot ws-adapters/fetch-candle-snapshot)

(def make-init-websocket ws-adapters/make-init-websocket)

(def init-websocket ws-adapters/init-websocket)

(def persist-asset-selector-markets-cache! asset-adapters/persist-asset-selector-markets-cache!)

(defn restore-asset-selector-markets-cache! [store]
  (asset-adapters/restore-asset-selector-markets-cache! {:store store}))

(def persist-active-market-display! asset-adapters/persist-active-market-display!)

(def load-active-market-display asset-adapters/load-active-market-display)

(defn subscribe-active-asset [_ store coin]
  (ws-adapters/subscribe-active-asset
   store
   coin
   {:persist-active-market-display-fn persist-active-market-display!
    :fetch-candle-snapshot-fn (fn [selected-timeframe]
                                (fetch-candle-snapshot nil store :interval selected-timeframe))}))

(defn unsubscribe-active-asset [_ store coin]
  (ws-adapters/unsubscribe-active-asset store coin))

(defn subscribe-orderbook [_ store coin]
  (ws-adapters/subscribe-orderbook store coin))

(defn subscribe-trades [_ store coin]
  (ws-adapters/subscribe-trades coin))

(defn unsubscribe-orderbook [_ store coin]
  (ws-adapters/unsubscribe-orderbook store coin))

(defn unsubscribe-trades [_ store coin]
  (ws-adapters/unsubscribe-trades coin))

(defn subscribe-webdata2 [_ store address]
  (ws-adapters/subscribe-webdata2 address))

(defn unsubscribe-webdata2 [_ store address]
  (ws-adapters/unsubscribe-webdata2 address))

(def sync-asset-selector-active-ctx-subscriptions
  asset-adapters/sync-asset-selector-active-ctx-subscriptions)

(def connect-wallet wallet-adapters/connect-wallet)

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
   (wallet-adapters/disconnect-wallet
    runtime
    store
    {:clear-order-feedback-toast-timeout! clear-order-feedback-toast-timeout!
     :clear-order-feedback-toast! clear-order-feedback-toast!})))

(defn make-disconnect-wallet
  [runtime]
  (fn [ctx store]
    (disconnect-wallet runtime ctx store)))

(def set-agent-storage-mode wallet-adapters/set-agent-storage-mode)

(def copy-wallet-address wallet-adapters/copy-wallet-address)

(def make-copy-wallet-address wallet-adapters/make-copy-wallet-address)

(def make-reconnect-websocket ws-adapters/make-reconnect-websocket)

(def reconnect-websocket ws-adapters/reconnect-websocket)

(def refresh-websocket-health ws-adapters/refresh-websocket-health)

(def make-refresh-websocket-health ws-adapters/make-refresh-websocket-health)

(def ws-reset-subscriptions ws-adapters/ws-reset-subscriptions)

(def confirm-ws-diagnostics-reveal ws-adapters/confirm-ws-diagnostics-reveal)

(def copy-websocket-diagnostics ws-adapters/copy-websocket-diagnostics)

(defn restore-active-asset! [store]
  (ws-adapters/restore-active-asset!
   {:store store
    :dispatch! nxr/dispatch
    :connected?-fn ws-client/connected?
    :load-active-market-display-fn load-active-market-display}))

(def ^:private exchange-response-error common/exchange-response-error)

(def ^:private runtime-error-message common/runtime-error-message)

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

(defn api-fetch-hyperunit-fee-estimate-effect
  [_ store]
  (funding-workflow-effects/api-fetch-hyperunit-fee-estimate!
   {:store store
    :request-hyperunit-estimate-fees! api/request-hyperunit-estimate-fees!
    :now-ms-fn platform/now-ms
    :runtime-error-message runtime-error-message}))

(defn api-fetch-hyperunit-withdrawal-queue-effect
  [_ store]
  (funding-workflow-effects/api-fetch-hyperunit-withdrawal-queue!
   {:store store
    :request-hyperunit-withdrawal-queue! api/request-hyperunit-withdrawal-queue!
    :now-ms-fn platform/now-ms
    :runtime-error-message runtime-error-message}))

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
    :request-hyperunit-operations! api/request-hyperunit-operations!
    :request-hyperunit-withdrawal-queue! api/request-hyperunit-withdrawal-queue!
    :set-timeout-fn platform/set-timeout!
    :now-ms-fn platform/now-ms
    :exchange-response-error exchange-response-error
    :runtime-error-message runtime-error-message
    :show-toast! show-order-feedback-toast!}))

(defn api-submit-funding-deposit-effect
  [_ store request]
  (funding-workflow-effects/api-submit-funding-deposit!
   {:store store
    :request request
    :dispatch! nxr/dispatch
    :request-hyperunit-operations! api/request-hyperunit-operations!
    :set-timeout-fn platform/set-timeout!
    :now-ms-fn platform/now-ms
    :runtime-error-message runtime-error-message
    :show-toast! show-order-feedback-toast!}))
