(ns hyperopen.api
  (:require [hyperopen.api.gateway.account :as account-gateway]
            [hyperopen.api.gateway.market :as market-gateway]
            [hyperopen.api.gateway.orders :as order-gateway]
            [hyperopen.api.info-client :as info-client]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.api.service :as api-service]
            [hyperopen.domain.funding-history :as funding-history]
            [hyperopen.platform :as platform]))

(def info-url (:info-url info-client/default-config))
(def default-funding-history-window-ms funding-history/default-window-ms)

(def ^:private default-info-client-config
  (merge info-client/default-config
         {:info-url info-url}))

(defonce ^:private api-service-instance
  (api-service/make-service
   {:info-client-config default-info-client-config
    :log-fn println}))

(declare request-spot-clearinghouse-state!)
(declare request-user-abstraction!)
(declare ensure-perp-dexs-data!)
(declare request-asset-selector-markets!)

(defn- now-ms []
  (platform/now-ms))

(defn- active-api-service
  []
  api-service-instance)

(defn- api-log-fn
  []
  (api-service/log-fn (active-api-service)))

(def funding-position-side funding-history/funding-position-side)
(def funding-history-row-id funding-history/funding-history-row-id)
(def normalize-info-funding-row funding-history/normalize-info-funding-row)
(def normalize-info-funding-rows funding-history/normalize-info-funding-rows)
(def normalize-ws-funding-row funding-history/normalize-ws-funding-row)
(def normalize-ws-funding-rows funding-history/normalize-ws-funding-rows)
(def sort-funding-history-rows funding-history/sort-funding-history-rows)
(def merge-funding-history-rows funding-history/merge-funding-history-rows)

(defn normalize-funding-history-filters
  ([filters]
   (normalize-funding-history-filters filters (now-ms)))
  ([filters now]
   (funding-history/normalize-funding-history-filters filters now default-funding-history-window-ms)))

(defn filter-funding-history-rows
  [rows filters]
  (let [filters* (normalize-funding-history-filters filters)]
    (funding-history/filter-funding-history-rows rows filters*)))

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
   (market-gateway/fetch-asset-contexts!
    {:log-fn (api-log-fn)
     :request-asset-contexts! request-asset-contexts!
     :apply-asset-contexts-success api-projections/apply-asset-contexts-success
     :apply-asset-contexts-error api-projections/apply-asset-contexts-error}
    store
    opts)))

(defn fetch-meta-and-asset-ctxs!
  "Fetch metaAndAssetCtxs for the default perp DEX or a named DEX."
  ([dex]
   (fetch-meta-and-asset-ctxs! dex {}))
  ([dex opts]
   (market-gateway/request-meta-and-asset-ctxs!
    {:post-info! post-info!}
    dex
    opts)))

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
   (market-gateway/fetch-perp-dexs!
    {:log-fn (api-log-fn)
     :request-perp-dexs! request-perp-dexs!
     :apply-perp-dexs-success api-projections/apply-perp-dexs-success
     :apply-perp-dexs-error api-projections/apply-perp-dexs-error}
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
  (market-gateway/fetch-candle-snapshot!
   {:log-fn (api-log-fn)
    :request-candle-snapshot! request-candle-snapshot!
    :apply-candle-snapshot-success api-projections/apply-candle-snapshot-success
    :apply-candle-snapshot-error api-projections/apply-candle-snapshot-error}
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

(defn- open-orders-fetch-deps
  []
  {:log-fn (api-log-fn)
   :request-frontend-open-orders! request-frontend-open-orders!
   :apply-open-orders-success api-projections/apply-open-orders-success
   :apply-open-orders-error api-projections/apply-open-orders-error})

(defn fetch-frontend-open-orders!
  ([store address]
   (order-gateway/fetch-frontend-open-orders!
    (open-orders-fetch-deps)
    store
    address))
  ([store address dex-or-opts]
   (order-gateway/fetch-frontend-open-orders!
    (open-orders-fetch-deps)
    store
    address
    dex-or-opts))
  ([store address dex opts]
   (order-gateway/fetch-frontend-open-orders!
    (open-orders-fetch-deps)
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

(defn- user-fills-fetch-deps
  []
  {:log-fn (api-log-fn)
   :request-user-fills! request-user-fills!
   :apply-user-fills-success api-projections/apply-user-fills-success
   :apply-user-fills-error api-projections/apply-user-fills-error})

(defn fetch-user-fills!
  ([store address]
   (fetch-user-fills! store address {}))
  ([store address opts]
   (order-gateway/fetch-user-fills!
    (user-fills-fetch-deps)
    store
    address
    opts)))

(defn- historical-orders-fetch-deps
  []
  {:log-fn (api-log-fn)
   :post-info! post-info!})

(defn fetch-historical-orders!
  ([store address]
   (fetch-historical-orders! store address {}))
  ([_store address opts]
   (order-gateway/fetch-historical-orders!
    (historical-orders-fetch-deps)
    address
    opts)))

(defn request-historical-orders!
  ([address]
   (request-historical-orders! address {}))
  ([address opts]
   (order-gateway/request-historical-orders!
    {:fetch-historical-orders! fetch-historical-orders!}
    address
    opts)))

(defn fetch-user-funding-history!
  ([store address]
   (fetch-user-funding-history! store address {}))
  ([store address opts]
   (account-gateway/fetch-user-funding-history!
    {:post-info! post-info!
     :normalize-funding-history-filters normalize-funding-history-filters
     :normalize-info-funding-rows normalize-info-funding-rows
     :sort-funding-history-rows sort-funding-history-rows}
    store
    address
    opts)))

(defn request-user-funding-history!
  ([address]
   (request-user-funding-history! address {}))
  ([address opts]
   (account-gateway/request-user-funding-history!
    {:fetch-user-funding-history! fetch-user-funding-history!}
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
   (market-gateway/fetch-spot-meta!
    {:log-fn (api-log-fn)
     :request-spot-meta! request-spot-meta!
     :begin-spot-meta-load api-projections/begin-spot-meta-load
     :apply-spot-meta-success api-projections/apply-spot-meta-success
     :apply-spot-meta-error api-projections/apply-spot-meta-error}
    store
    opts)))

(defn fetch-spot-meta-raw!
  "Fetch spot meta and return the parsed response without touching state."
  ([]
   (fetch-spot-meta-raw! {}))
  ([opts]
   (market-gateway/fetch-spot-meta-raw!
    {:request-spot-meta! request-spot-meta!}
    opts)))

(defn request-public-webdata2!
  "Fetch a public WebData2 snapshot to access spotAssetCtxs."
  ([]
   (request-public-webdata2! {}))
  ([opts]
   (market-gateway/request-public-webdata2!
    {:post-info! post-info!}
    opts)))

(defn fetch-public-webdata2!
  ([]
   (fetch-public-webdata2! {}))
  ([opts]
   (market-gateway/fetch-public-webdata2!
    {:request-public-webdata2! request-public-webdata2!}
    opts)))

(defn ensure-perp-dexs!
  ([store]
   (ensure-perp-dexs! store {}))
  ([store opts]
   (market-gateway/ensure-perp-dexs!
    {:ensure-perp-dexs-data! ensure-perp-dexs-data!
     :apply-perp-dexs-success api-projections/apply-perp-dexs-success
     :apply-perp-dexs-error api-projections/apply-perp-dexs-error}
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
   (market-gateway/ensure-spot-meta!
    {:ensure-spot-meta-data! ensure-spot-meta-data!
     :apply-spot-meta-success api-projections/apply-spot-meta-success
     :apply-spot-meta-error api-projections/apply-spot-meta-error}
    store
    opts)))

(defn ensure-public-webdata2!
  ([]
   (ensure-public-webdata2! {}))
  ([opts]
   (api-service/ensure-public-webdata2!
    (active-api-service)
    fetch-public-webdata2!
    opts)))

(defn fetch-asset-selector-markets!
  "Fetch and build a unified market list for the asset selector.

   Options:
   - :phase :bootstrap|:full"
  ([store]
   (fetch-asset-selector-markets! store {:phase :full}))
  ([store opts]
   (market-gateway/fetch-asset-selector-markets!
    {:log-fn (api-log-fn)
     :request-asset-selector-markets! request-asset-selector-markets!
     :begin-asset-selector-load api-projections/begin-asset-selector-load
     :apply-asset-selector-success api-projections/apply-asset-selector-success
     :apply-asset-selector-error api-projections/apply-asset-selector-error}
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
     :fetch-meta-and-asset-ctxs! fetch-meta-and-asset-ctxs!
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
   (account-gateway/fetch-spot-clearinghouse-state!
    {:log-fn (api-log-fn)
     :request-spot-clearinghouse-state! request-spot-clearinghouse-state!
     :begin-spot-balances-load api-projections/begin-spot-balances-load
     :apply-spot-balances-success api-projections/apply-spot-balances-success
     :apply-spot-balances-error api-projections/apply-spot-balances-error}
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
   (account-gateway/fetch-user-abstraction!
    {:log-fn (api-log-fn)
     :request-user-abstraction! request-user-abstraction!
     :normalize-user-abstraction-mode account-gateway/normalize-user-abstraction-mode
     :apply-user-abstraction-snapshot api-projections/apply-user-abstraction-snapshot}
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
   (account-gateway/fetch-clearinghouse-state!
    {:log-fn (api-log-fn)
     :request-clearinghouse-state! request-clearinghouse-state!
     :apply-perp-dex-clearinghouse-success api-projections/apply-perp-dex-clearinghouse-success
     :apply-perp-dex-clearinghouse-error api-projections/apply-perp-dex-clearinghouse-error}
    store
    address
    dex
    opts)))

(defn fetch-perp-dex-clearinghouse-states!
  "Fetch clearinghouse state for all named perp DEXes."
  ([store address dex-names]
   (fetch-perp-dex-clearinghouse-states! store address dex-names {}))
  ([store address dex-names opts]
   (account-gateway/fetch-perp-dex-clearinghouse-states!
    {:fetch-clearinghouse-state! fetch-clearinghouse-state!}
    store
    address
    dex-names
    opts)))
