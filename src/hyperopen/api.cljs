(ns hyperopen.api
  (:require [hyperopen.api.compat :as api-compat]
            [hyperopen.api.gateway.account :as account-gateway]
            [hyperopen.api.gateway.market :as market-gateway]
            [hyperopen.api.gateway.orders :as order-gateway]
            [hyperopen.api.instance :as api-instance]
            [hyperopen.api.service :as api-service]
            [hyperopen.domain.funding-history :as funding-history]
            [hyperopen.platform :as platform]))

(def info-url api-instance/info-url)

(def ^:private default-info-client-config
  api-instance/default-info-client-config)

(defn- make-default-api-service
  []
  (api-instance/make-default-api-service))

(defonce ^:private api-facade-state
  (atom {:service (make-default-api-service)}))

(declare request-spot-clearinghouse-state!)
(declare request-user-abstraction!)
(declare ensure-perp-dexs-data!)
(declare request-asset-selector-markets!)
(declare request-historical-orders-data!)
(declare request-user-funding-history-data!)

(defn- now-ms []
  (platform/now-ms))

(defn- active-api-service
  []
  (:service @api-facade-state))

(defn install-api-service!
  [service]
  (swap! api-facade-state assoc :service service)
  nil)

(defn configure-api-service!
  [opts]
  (let [opts* (or opts {})
        configured-info-client
        (if (contains? opts* :info-client-config)
          (merge default-info-client-config
                 (:info-client-config opts*))
          default-info-client-config)
        service-opts (merge {:info-client-config configured-info-client
                             :log-fn println}
                            (dissoc opts* :info-client-config))]
    (install-api-service! (api-service/make-service service-opts))))

(defn reset-api-service!
  []
  (install-api-service! (make-default-api-service)))

(defn make-api
  [opts]
  (api-instance/make-api opts))

(defn- api-log-fn
  []
  (api-service/log-fn (active-api-service)))

(defn- normalize-funding-history-request-filters
  [filters]
  (funding-history/normalize-funding-history-filters
   filters
   (now-ms)
   funding-history/default-window-ms))

(defn get-request-stats
  []
  (api-service/get-request-stats (active-api-service)))

(defn reset-request-runtime!
  []
  (api-service/reset-service! (active-api-service)))

(defn- post-info!
  ([body]
   (post-info! body {}))
  ([body opts]
   (api-service/request-info! (active-api-service) body opts))
  ([body opts attempt]
   (api-service/request-info! (active-api-service) body opts attempt)))

(defn request-asset-contexts!
  ([] (request-asset-contexts! {}))
  ([opts]
   (market-gateway/request-asset-contexts!
    {:post-info! post-info!}
    opts)))

(defn fetch-asset-contexts!
  ([store]
   (fetch-asset-contexts! store {}))
  ([store opts]
   (api-compat/fetch-asset-contexts!
    {:log-fn (api-log-fn)
     :request-asset-contexts! request-asset-contexts!}
    store
    opts)))

(defn request-meta-and-asset-ctxs!
  "Request metaAndAssetCtxs for the default perp DEX or a named DEX."
  ([dex]
   (request-meta-and-asset-ctxs! dex {}))
  ([dex opts]
   (market-gateway/request-meta-and-asset-ctxs!
    {:post-info! post-info!}
    dex
    opts)))

(defn fetch-meta-and-asset-ctxs!
  "Deprecated compatibility alias for `request-meta-and-asset-ctxs!`."
  ([dex]
   (fetch-meta-and-asset-ctxs! dex {}))
  ([dex opts]
   (request-meta-and-asset-ctxs! dex opts)))

(defn request-perp-dexs!
  ([] (request-perp-dexs! {}))
  ([opts]
   (market-gateway/request-perp-dexs!
    {:post-info! post-info!}
    opts)))

(defn fetch-perp-dexs!
  "Fetch the list of available perp DEXes. The default DEX is omitted from
  the response, so we only store named DEXes."
  ([store]
   (fetch-perp-dexs! store {}))
  ([store opts]
   (api-compat/fetch-perp-dexs!
    {:log-fn (api-log-fn)
     :request-perp-dexs! request-perp-dexs!}
    store
    opts)))

(defn request-candle-snapshot!
  [coin & {:keys [interval bars priority]
           :or {interval :1d bars 330 priority :high}}]
  (market-gateway/request-candle-snapshot!
   {:post-info! post-info!
    :now-ms-fn now-ms}
   coin
   {:interval interval
    :bars bars
    :priority priority}))

(defn fetch-candle-snapshot!
  "Fetch `bars` worth of candles for the active asset at keyword interval (e.g. :1m, :1h).
   Defaults to :1d interval and 330 bars if not specified."
  [store & {:keys [interval bars priority]
            :or {interval :1d bars 330 priority :high}}]
  (api-compat/fetch-candle-snapshot!
   {:log-fn (api-log-fn)
    :request-candle-snapshot! request-candle-snapshot!}
   store
   {:interval interval
    :bars bars
    :priority priority}))

(defn request-frontend-open-orders!
  ([address]
   (order-gateway/request-frontend-open-orders!
    {:post-info! post-info!}
    address))
  ([address dex-or-opts]
   (order-gateway/request-frontend-open-orders!
    {:post-info! post-info!}
    address
    dex-or-opts))
  ([address dex opts]
   (order-gateway/request-frontend-open-orders!
    {:post-info! post-info!}
    address
    dex
    opts)))

(defn fetch-frontend-open-orders!
  ([store address]
   (api-compat/fetch-frontend-open-orders!
    {:log-fn (api-log-fn)
     :request-frontend-open-orders! request-frontend-open-orders!}
    store
    address))
  ([store address dex-or-opts]
   (api-compat/fetch-frontend-open-orders!
    {:log-fn (api-log-fn)
     :request-frontend-open-orders! request-frontend-open-orders!}
    store
    address
    dex-or-opts))
  ([store address dex opts]
   (api-compat/fetch-frontend-open-orders!
    {:log-fn (api-log-fn)
     :request-frontend-open-orders! request-frontend-open-orders!}
    store
    address
    dex
    opts)))

(defn request-user-fills!
  ([address]
   (request-user-fills! address {}))
  ([address opts]
   (order-gateway/request-user-fills!
    {:post-info! post-info!}
    address
    opts)))

(defn fetch-user-fills!
  ([store address]
   (fetch-user-fills! store address {}))
  ([store address opts]
   (api-compat/fetch-user-fills!
    {:log-fn (api-log-fn)
     :request-user-fills! request-user-fills!}
    store
    address
    opts)))

(defn fetch-historical-orders!
  "Deprecated compatibility wrapper; prefer `request-historical-orders!`."
  ([store address]
   (fetch-historical-orders! store address {}))
  ([_store address opts]
   (request-historical-orders-data! address opts)))

(defn- request-historical-orders-data!
  ([address]
   (request-historical-orders-data! address {}))
  ([address opts]
   (api-compat/fetch-historical-orders!
    {:log-fn (api-log-fn)
     :post-info! post-info!}
    address
    opts)))

(defn request-historical-orders!
  ([address]
   (request-historical-orders! address {}))
  ([address opts]
   (order-gateway/request-historical-orders!
    {:request-historical-orders-data! request-historical-orders-data!}
    address
    opts)))

(defn fetch-user-funding-history!
  "Deprecated compatibility wrapper; prefer `request-user-funding-history!`."
  ([store address]
   (fetch-user-funding-history! store address {}))
  ([_store address opts]
   (request-user-funding-history-data! address opts)))

(defn- request-user-funding-history-data!
  ([address]
   (request-user-funding-history-data! address {}))
  ([address opts]
   (account-gateway/request-user-funding-history-data!
    {:post-info! post-info!
     :normalize-funding-history-filters normalize-funding-history-request-filters
     :normalize-info-funding-rows funding-history/normalize-info-funding-rows
     :sort-funding-history-rows funding-history/sort-funding-history-rows}
    address
    opts)))

(defn request-user-funding-history!
  ([address]
   (request-user-funding-history! address {}))
  ([address opts]
   (account-gateway/request-user-funding-history!
    {:request-user-funding-history-data! request-user-funding-history-data!}
    address
    opts)))

(defn request-spot-meta!
  ([] (request-spot-meta! {}))
  ([opts]
   (market-gateway/request-spot-meta!
    {:post-info! post-info!}
    opts)))

(defn fetch-spot-meta!
  ([store]
   (fetch-spot-meta! store {}))
  ([store opts]
   (api-compat/fetch-spot-meta!
    {:log-fn (api-log-fn)
     :request-spot-meta! request-spot-meta!}
    store
    opts)))

(defn fetch-spot-meta-raw!
  "Deprecated compatibility alias for `request-spot-meta!`."
  ([]
   (fetch-spot-meta-raw! {}))
  ([opts]
   (request-spot-meta! opts)))

(defn request-public-webdata2!
  "Request a public WebData2 snapshot to access spotAssetCtxs."
  ([]
   (request-public-webdata2! {}))
  ([opts]
   (market-gateway/request-public-webdata2!
    {:post-info! post-info!}
    opts)))

(defn fetch-public-webdata2!
  "Deprecated compatibility alias for `request-public-webdata2!`."
  ([]
   (fetch-public-webdata2! {}))
  ([opts]
   (request-public-webdata2! opts)))

(defn ensure-perp-dexs!
  ([store]
   (ensure-perp-dexs! store {}))
  ([store opts]
   (api-compat/ensure-perp-dexs!
    {:ensure-perp-dexs-data! ensure-perp-dexs-data!}
    store
    opts)))

(defn ensure-perp-dexs-data!
  ([store]
   (ensure-perp-dexs-data! store {}))
  ([store opts]
   (api-service/ensure-perp-dexs-data!
    (active-api-service)
    store
    request-perp-dexs!
    opts)))

(defn ensure-spot-meta-data!
  ([store]
   (ensure-spot-meta-data! store {}))
  ([store opts]
   (api-service/ensure-spot-meta-data!
    (active-api-service)
    store
    request-spot-meta!
    opts)))

(defn ensure-spot-meta!
  ([store]
   (ensure-spot-meta! store {}))
  ([store opts]
   (api-compat/ensure-spot-meta!
    {:ensure-spot-meta-data! ensure-spot-meta-data!}
    store
    opts)))

(defn ensure-public-webdata2!
  ([]
   (ensure-public-webdata2! {}))
  ([opts]
   (api-service/ensure-public-webdata2!
    (active-api-service)
    request-public-webdata2!
    opts)))

(defn fetch-asset-selector-markets!
  "Fetch and build a unified market list for the asset selector.

   Options:
   - :phase :bootstrap|:full"
  ([store]
   (fetch-asset-selector-markets! store {:phase :full}))
  ([store opts]
   (api-compat/fetch-asset-selector-markets!
    {:log-fn (api-log-fn)
     :request-asset-selector-markets! request-asset-selector-markets!}
    store
    opts)))

(defn request-asset-selector-markets!
  ([store]
   (request-asset-selector-markets! store {:phase :full}))
  ([store opts]
   (market-gateway/request-asset-selector-markets!
    {:store store
     :opts opts
     :ensure-perp-dexs-data! ensure-perp-dexs-data!
     :ensure-spot-meta-data! ensure-spot-meta-data!
     :ensure-public-webdata2! ensure-public-webdata2!
     :request-meta-and-asset-ctxs! request-meta-and-asset-ctxs!
     :build-market-state (fn [runtime-store phase dexs spot-meta spot-asset-ctxs perp-results]
                           (market-gateway/build-market-state now-ms
                                                              runtime-store
                                                              phase
                                                              dexs
                                                              spot-meta
                                                              spot-asset-ctxs
                                                              perp-results))
     :log-fn (api-log-fn)})))

(defn fetch-spot-clearinghouse-state!
  ([store address]
   (fetch-spot-clearinghouse-state! store address {}))
  ([store address opts]
   (api-compat/fetch-spot-clearinghouse-state!
    {:log-fn (api-log-fn)
     :request-spot-clearinghouse-state! request-spot-clearinghouse-state!}
    store
    address
    opts)))

(defn request-spot-clearinghouse-state!
  ([address]
   (request-spot-clearinghouse-state! address {}))
  ([address opts]
   (account-gateway/request-spot-clearinghouse-state!
    {:post-info! post-info!}
    address
    opts)))

(defn fetch-user-abstraction!
  "Fetch account abstraction mode for a user and project normalized account mode.
   Supported normalized modes:
   - :unified  => unifiedAccount / portfolioMargin / dexAbstraction
   - :classic  => default / disabled / nil / unknown"
  ([store address]
   (fetch-user-abstraction! store address {}))
  ([store address opts]
   (api-compat/fetch-user-abstraction!
    {:log-fn (api-log-fn)
     :request-user-abstraction! request-user-abstraction!}
    store
    address
    opts)))

(defn request-user-abstraction!
  ([address]
   (request-user-abstraction! address {}))
  ([address opts]
   (account-gateway/request-user-abstraction!
    {:post-info! post-info!}
    address
    opts)))

(defn request-clearinghouse-state!
  ([address dex]
   (request-clearinghouse-state! address dex {}))
  ([address dex opts]
   (account-gateway/request-clearinghouse-state!
    {:post-info! post-info!}
    address
    dex
    opts)))

(defn fetch-clearinghouse-state!
  "Fetch clearinghouse state for a specific perp DEX."
  ([store address dex]
   (fetch-clearinghouse-state! store address dex {}))
  ([store address dex opts]
   (api-compat/fetch-clearinghouse-state!
    {:log-fn (api-log-fn)
     :request-clearinghouse-state! request-clearinghouse-state!}
    store
    address
    dex
    opts)))

(defn fetch-perp-dex-clearinghouse-states!
  "Fetch clearinghouse state for all named perp DEXes."
  ([store address dex-names]
   (fetch-perp-dex-clearinghouse-states! store address dex-names {}))
  ([store address dex-names opts]
   (api-compat/fetch-perp-dex-clearinghouse-states!
    {:fetch-clearinghouse-state! fetch-clearinghouse-state!}
    store
    address
    dex-names
    opts)))
