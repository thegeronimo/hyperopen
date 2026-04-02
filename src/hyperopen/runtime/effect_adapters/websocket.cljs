(ns hyperopen.runtime.effect-adapters.websocket
  (:require [nexus.registry :as nxr]
            [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.orderbook.price-aggregation :as price-agg]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.app-effects :as app-effects]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.startup.restore :as startup-restore]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.diagnostics-effects :as diagnostics-effects]
            [hyperopen.websocket.diagnostics-runtime :as diagnostics-runtime]
            [hyperopen.websocket.health-projection :as health-projection]
            [hyperopen.websocket.health-runtime :as health-runtime]
            [hyperopen.websocket.market-projection-runtime :as market-projection-runtime]
            [hyperopen.websocket.candles :as candles]
            [hyperopen.websocket.orderbook :as orderbook]
            [hyperopen.websocket.subscriptions-runtime :as subscriptions-runtime]
            [hyperopen.websocket.trades :as trades]
            [hyperopen.websocket.webdata2 :as webdata2]
            [hyperopen.vaults.domain.identity :as vault-identity]
            [hyperopen.vaults.infrastructure.routes :as vault-routes]))

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
  (telemetry/market-projection-flush-diagnostics-events))

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

(defn- active?
  [active?-fn]
  (if (fn? active?-fn)
    (not (false? (active?-fn)))
    true))

(defn- detail-route-active-for-vault?
  [store requested-vault-address]
  (let [{:keys [kind current-route-vault-address]}
        (let [route (vault-routes/parse-vault-route
                     (get-in @store [:router :path] ""))]
          (assoc route :current-route-vault-address (:vault-address route)))
        requested-address (vault-identity/normalize-vault-address requested-vault-address)
        route-address (vault-identity/normalize-vault-address current-route-vault-address)]
    (and (= :detail kind)
         (= requested-address route-address))))

(defn- combined-active?-fn
  [store active?-fn detail-route-vault-address]
  (let [detail-route-vault-address* (vault-identity/normalize-vault-address detail-route-vault-address)
        route-active?-fn (when detail-route-vault-address*
                           (fn []
                             (detail-route-active-for-vault? store detail-route-vault-address*)))
        active-fns (cond-> []
                     (fn? active?-fn) (conj active?-fn)
                     (fn? route-active?-fn) (conj route-active?-fn))]
    (when (seq active-fns)
      (fn []
        (every? active? active-fns)))))

(defn make-fetch-candle-snapshot
  [{:keys [log-fn
           request-candle-snapshot-fn
           apply-candle-snapshot-success
           apply-candle-snapshot-error]
    :or {log-fn telemetry/log!
         request-candle-snapshot-fn api/request-candle-snapshot!
         apply-candle-snapshot-success api-projections/apply-candle-snapshot-success
         apply-candle-snapshot-error api-projections/apply-candle-snapshot-error}}]
  (fn [_ store & {:keys [coin interval bars active?-fn detail-route-vault-address]
                  :or {interval :1d bars 330}}]
    (app-effects/fetch-candle-snapshot!
     {:store store
      :coin coin
      :interval interval
      :bars bars
      :active?-fn (combined-active?-fn store active?-fn detail-route-vault-address)
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

(defn- persist-active-asset!
  [canonical-coin]
  (when (string? canonical-coin)
    (app-effects/local-storage-set! "active-asset" canonical-coin)))

(defn subscribe-active-asset
  [store coin {:keys [fetch-candle-snapshot-fn
                      persist-active-market-display-fn
                      log-fn
                      resolve-market-by-coin-fn
                      subscribe-active-asset-ctx-fn
                      sync-candle-subscription-fn
                      clear-candle-subscription-fn]
               :or {log-fn telemetry/log!
                    resolve-market-by-coin-fn markets/resolve-or-infer-market-by-coin
                    subscribe-active-asset-ctx-fn active-ctx/subscribe-active-asset-ctx!
                    sync-candle-subscription-fn candles/sync-candle-subscription!
                    clear-candle-subscription-fn candles/clear-owner-subscription!}}]
  (subscriptions-runtime/subscribe-active-asset!
   {:store store
    :coin coin
    :log-fn log-fn
    :resolve-market-by-coin-fn resolve-market-by-coin-fn
    :persist-active-asset! persist-active-asset!
    :persist-active-market-display! persist-active-market-display-fn
    :subscribe-active-asset-ctx! subscribe-active-asset-ctx-fn
    :sync-candle-subscription! sync-candle-subscription-fn
    :clear-candle-subscription! clear-candle-subscription-fn
    :fetch-candle-snapshot! fetch-candle-snapshot-fn}))

(defn unsubscribe-active-asset
  ([store coin]
   (unsubscribe-active-asset store coin {}))
  ([store coin {:keys [clear-candle-subscription-fn]
                :or {clear-candle-subscription-fn candles/clear-owner-subscription!}}]
   (subscriptions-runtime/unsubscribe-active-asset!
    {:store store
     :coin coin
     :log-fn telemetry/log!
     :unsubscribe-active-asset-ctx! active-ctx/unsubscribe-active-asset-ctx!
     :clear-candle-subscription! clear-candle-subscription-fn})))

(defn sync-active-candle-subscription
  ([store]
   (sync-active-candle-subscription store {}))
  ([store {:keys [interval
                  sync-candle-subscription-fn]
           :or {sync-candle-subscription-fn candles/sync-candle-subscription!}}]
   (subscriptions-runtime/sync-active-candle-subscription!
    {:store store
     :interval interval
     :log-fn telemetry/log!
     :sync-candle-subscription! sync-candle-subscription-fn
     :clear-candle-subscription! candles/clear-owner-subscription!})))

(defn subscribe-orderbook
  [store coin]
  (subscriptions-runtime/subscribe-orderbook!
   {:store store
    :coin coin
    :log-fn telemetry/log!
    :normalize-mode-fn price-agg/normalize-mode
    :mode->subscription-config-fn price-agg/mode->subscription-config
    :subscribe-orderbook-fn orderbook/subscribe-orderbook!}))

(defn subscribe-trades
  [coin]
  (subscriptions-runtime/subscribe-trades!
   {:coin coin
    :log-fn telemetry/log!
    :subscribe-trades-fn trades/subscribe-trades!}))

(defn unsubscribe-orderbook
  [store coin]
  (subscriptions-runtime/unsubscribe-orderbook!
   {:store store
    :coin coin
    :log-fn telemetry/log!
    :unsubscribe-orderbook-fn orderbook/unsubscribe-orderbook!}))

(defn unsubscribe-trades
  [coin]
  (subscriptions-runtime/unsubscribe-trades!
   {:coin coin
    :log-fn telemetry/log!
    :unsubscribe-trades-fn trades/unsubscribe-trades!}))

(defn subscribe-webdata2
  [address]
  (subscriptions-runtime/subscribe-webdata2!
   {:address address
    :log-fn telemetry/log!
    :subscribe-webdata2-fn webdata2/subscribe-webdata2!}))

(defn unsubscribe-webdata2
  [address]
  (subscriptions-runtime/unsubscribe-webdata2!
   {:address address
    :log-fn telemetry/log!
    :unsubscribe-webdata2-fn webdata2/unsubscribe-webdata2!}))

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

(defn ws-reset-subscriptions
  [_ store {:keys [group source]
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

(defn confirm-ws-diagnostics-reveal
  [_ store]
  (diagnostics-effects/confirm-ws-diagnostics-reveal!
   {:store store
    :confirm-fn platform/confirm!}))

(defn- set-copy-status!
  [store status]
  (swap! store assoc-in [:websocket-ui :copy-status] status))

(defn copy-websocket-diagnostics
  [_ store]
  (diagnostics-effects/copy-websocket-diagnostics!
   {:store store
    :app-version runtime-state/app-version
    :set-copy-status! set-copy-status!
    :log-fn telemetry/log!}))

(defn restore-active-asset!
  [{:keys [store
           connected?-fn
           dispatch!
           load-active-market-display-fn]
    :or {connected?-fn ws-client/connected?
         dispatch! nxr/dispatch}}]
  (startup-restore/restore-active-asset!
   store
   {:connected?-fn connected?-fn
    :dispatch! dispatch!
    :load-active-market-display-fn load-active-market-display-fn}))
