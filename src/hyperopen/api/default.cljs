(ns hyperopen.api.default
  (:require [hyperopen.api.default.account :as account]
            [hyperopen.api.default.funding-hyperunit :as funding-hyperunit]
            [hyperopen.api.default.leaderboard :as leaderboard]
            [hyperopen.api.default.market :as market]
            [hyperopen.api.default.orders :as orders]
            [hyperopen.api.default.state :as state]
            [hyperopen.api.default.vaults :as vaults]))

(def info-url state/info-url)

(def ^:private default-info-client-config state/default-info-client-config)

(defn- make-default-api-service [] (state/make-default-api-service))
(defn- now-ms [] (state/now-ms))
(defn- active-api-service [] (state/active-api-service))
(defn- api-log-fn [] (state/api-log-fn))
(defn- normalize-funding-history-request-filters [filters]
  (state/normalize-funding-history-request-filters filters))

(defn install-api-service! [service] (state/install-api-service! service))
(defn configure-api-service! [opts] (state/configure-api-service! opts))
(defn reset-api-service! [] (state/reset-api-service!))
(defn make-api [opts] (state/make-api opts))
(defn get-request-stats [] (state/get-request-stats))
(defn reset-request-runtime! [] (state/reset-request-runtime!))

(defn- post-info!
  ([body] (state/post-info! body))
  ([body opts] (state/post-info! body opts))
  ([body opts attempt] (state/post-info! body opts attempt)))

(declare request-historical-orders-data!)
(declare request-user-funding-history-data!)
(declare request-asset-selector-markets!)
(declare ensure-perp-dexs-data!)
(declare ensure-spot-meta-data!)
(declare ensure-public-webdata2!)
(declare request-spot-clearinghouse-state!)
(declare request-user-abstraction!)
(declare request-clearinghouse-state!)
(declare fetch-clearinghouse-state!)

(defn request-asset-contexts!
  ([] (request-asset-contexts! {}))
  ([opts] (market/request-asset-contexts! {:post-info! post-info!} opts)))

(defn fetch-asset-contexts!
  ([store] (fetch-asset-contexts! store {}))
  ([store opts]
   (market/fetch-asset-contexts!
    {:log-fn (api-log-fn)
     :request-asset-contexts! request-asset-contexts!}
    store
    opts)))

(defn request-meta-and-asset-ctxs!
  "Request metaAndAssetCtxs for the default perp DEX or a named DEX."
  ([dex] (request-meta-and-asset-ctxs! dex {}))
  ([dex opts] (market/request-meta-and-asset-ctxs! {:post-info! post-info!} dex opts)))

(defn fetch-meta-and-asset-ctxs!
  "Deprecated compatibility alias for `request-meta-and-asset-ctxs!`."
  ([dex] (fetch-meta-and-asset-ctxs! dex {}))
  ([dex opts] (request-meta-and-asset-ctxs! dex opts)))

(defn request-perp-dexs!
  ([] (request-perp-dexs! {}))
  ([opts] (market/request-perp-dexs! {:post-info! post-info!} opts)))

(defn fetch-perp-dexs!
  ([store] (fetch-perp-dexs! store {}))
  ([store opts]
   (market/fetch-perp-dexs!
    {:log-fn (api-log-fn)
     :request-perp-dexs! request-perp-dexs!}
    store
    opts)))

(defn request-candle-snapshot!
  [coin & {:keys [interval bars priority]
           :or {interval :1d bars 330 priority :high}}]
  (market/request-candle-snapshot!
   {:post-info! post-info!
    :now-ms-fn now-ms}
   coin
   {:interval interval
    :bars bars
    :priority priority}))

(defn fetch-candle-snapshot!
  "Fetch `bars` worth of candles for the active asset at keyword interval (e.g. :1m, :1h)."
  [store & {:keys [interval bars priority]
            :or {interval :1d bars 330 priority :high}}]
  (market/fetch-candle-snapshot!
   {:log-fn (api-log-fn)
    :request-candle-snapshot! request-candle-snapshot!}
   store
   {:interval interval
    :bars bars
    :priority priority}))

(defn request-frontend-open-orders!
  ([address] (request-frontend-open-orders! address {}))
  ([address opts] (orders/request-frontend-open-orders! {:post-info! post-info!} address opts))
  ([address dex opts]
   (request-frontend-open-orders! address
                                  (cond-> (or opts {})
                                    (and dex (not= dex "")) (assoc :dex dex)))))

(defn fetch-frontend-open-orders!
  ([store address]
   (fetch-frontend-open-orders! store address {}))
  ([store address opts]
   (orders/fetch-frontend-open-orders!
    {:log-fn (api-log-fn)
     :request-frontend-open-orders! request-frontend-open-orders!}
    store
    address
    opts))
  ([store address dex opts]
   (fetch-frontend-open-orders! store
                                address
                                (cond-> (or opts {})
                                  (and dex (not= dex "")) (assoc :dex dex)))))

(defn request-user-fills!
  ([address] (request-user-fills! address {}))
  ([address opts] (orders/request-user-fills! {:post-info! post-info!} address opts)))

(defn fetch-user-fills!
  ([store address] (fetch-user-fills! store address {}))
  ([store address opts]
   (orders/fetch-user-fills!
    {:log-fn (api-log-fn)
     :request-user-fills! request-user-fills!}
    store
    address
    opts)))

(defn fetch-historical-orders!
  "Deprecated compatibility wrapper; prefer `request-historical-orders!`."
  ([store address] (fetch-historical-orders! store address {}))
  ([_store address opts] (request-historical-orders-data! address opts)))

(defn- request-historical-orders-data!
  ([address] (request-historical-orders-data! address {}))
  ([address opts]
   (orders/request-historical-orders-data!
    {:log-fn (api-log-fn)
     :post-info! post-info!}
    address
    opts)))

(defn request-historical-orders!
  ([address] (request-historical-orders! address {}))
  ([address opts]
   (orders/request-historical-orders!
    {:request-historical-orders-data! request-historical-orders-data!}
    address
    opts)))

(defn fetch-user-funding-history!
  "Deprecated compatibility wrapper; prefer `request-user-funding-history!`."
  ([store address] (fetch-user-funding-history! store address {}))
  ([_store address opts] (request-user-funding-history-data! address opts)))

(defn- request-user-funding-history-data!
  ([address] (request-user-funding-history-data! address {}))
  ([address opts]
   (account/request-user-funding-history-data!
    {:post-info! post-info!
     :normalize-funding-history-filters normalize-funding-history-request-filters}
    address
    opts)))

(defn request-user-funding-history!
  ([address] (request-user-funding-history! address {}))
  ([address opts]
   (account/request-user-funding-history!
    {:request-user-funding-history-data! request-user-funding-history-data!}
    address
    opts)))

(defn request-extra-agents!
  ([address] (request-extra-agents! address {}))
  ([address opts] (account/request-extra-agents! {:post-info! post-info!} address opts)))

(defn request-user-webdata2!
  ([address] (request-user-webdata2! address {}))
  ([address opts] (account/request-user-webdata2! {:post-info! post-info!} address opts)))

(defn request-staking-validator-summaries!
  ([] (request-staking-validator-summaries! {}))
  ([opts] (account/request-staking-validator-summaries! {:post-info! post-info!} opts)))

(defn request-staking-delegator-summary!
  ([address] (request-staking-delegator-summary! address {}))
  ([address opts] (account/request-staking-delegator-summary! {:post-info! post-info!} address opts)))

(defn request-staking-delegations!
  ([address] (request-staking-delegations! address {}))
  ([address opts] (account/request-staking-delegations! {:post-info! post-info!} address opts)))

(defn request-staking-delegator-rewards!
  ([address] (request-staking-delegator-rewards! address {}))
  ([address opts] (account/request-staking-delegator-rewards! {:post-info! post-info!} address opts)))

(defn request-staking-delegator-history!
  ([address] (request-staking-delegator-history! address {}))
  ([address opts] (account/request-staking-delegator-history! {:post-info! post-info!} address opts)))

(defn request-hyperunit-operations! [opts]
  (funding-hyperunit/request-hyperunit-operations! opts))

(defn request-hyperunit-estimate-fees!
  ([] (request-hyperunit-estimate-fees! {}))
  ([opts] (funding-hyperunit/request-hyperunit-estimate-fees! opts)))

(defn request-hyperunit-withdrawal-queue!
  ([] (request-hyperunit-withdrawal-queue! {}))
  ([opts] (funding-hyperunit/request-hyperunit-withdrawal-queue! opts)))

(defn request-vault-index!
  ([] (request-vault-index! {}))
  ([opts] (vaults/request-vault-index! opts)))

(defn request-vault-index-response!
  ([] (request-vault-index-response! {}))
  ([opts] (vaults/request-vault-index-response! opts)))

(defn request-vault-summaries!
  ([] (request-vault-summaries! {}))
  ([opts] (vaults/request-vault-summaries! {:post-info! post-info!} opts)))

(defn request-merged-vault-index!
  ([] (request-merged-vault-index! {}))
  ([opts]
   (vaults/request-merged-vault-index!
    {:request-vault-index! request-vault-index!
     :request-vault-summaries! request-vault-summaries!}
    opts)))

(defn request-user-vault-equities!
  ([address] (request-user-vault-equities! address {}))
  ([address opts] (vaults/request-user-vault-equities! {:post-info! post-info!} address opts)))

(defn request-vault-details!
  ([vault-address] (request-vault-details! vault-address {}))
  ([vault-address opts] (vaults/request-vault-details! {:post-info! post-info!} vault-address opts)))

(defn request-vault-webdata2!
  ([vault-address] (request-vault-webdata2! vault-address {}))
  ([vault-address opts] (vaults/request-vault-webdata2! {:post-info! post-info!} vault-address opts)))

(defn request-leaderboard!
  ([] (request-leaderboard! {}))
  ([opts] (leaderboard/request-leaderboard! opts)))

(defn request-spot-meta!
  ([] (request-spot-meta! {}))
  ([opts] (market/request-spot-meta! {:post-info! post-info!} opts)))

(defn fetch-spot-meta!
  ([store] (fetch-spot-meta! store {}))
  ([store opts]
   (market/fetch-spot-meta!
    {:log-fn (api-log-fn)
     :request-spot-meta! request-spot-meta!}
    store
    opts)))

(defn fetch-spot-meta-raw!
  "Deprecated compatibility alias for `request-spot-meta!`."
  ([] (fetch-spot-meta-raw! {}))
  ([opts] (request-spot-meta! opts)))

(defn request-public-webdata2!
  "Request a public WebData2 snapshot to access spotAssetCtxs."
  ([] (request-public-webdata2! {}))
  ([opts] (market/request-public-webdata2! {:post-info! post-info!} opts)))

(defn request-market-funding-history!
  ([coin] (request-market-funding-history! coin {}))
  ([coin opts] (market/request-market-funding-history! {:post-info! post-info!} coin opts)))

(defn request-predicted-fundings!
  ([] (request-predicted-fundings! {}))
  ([opts] (market/request-predicted-fundings! {:post-info! post-info!} opts)))

(defn fetch-public-webdata2!
  "Deprecated compatibility alias for `request-public-webdata2!`."
  ([] (fetch-public-webdata2! {}))
  ([opts] (request-public-webdata2! opts)))

(defn ensure-perp-dexs!
  ([store] (ensure-perp-dexs! store {}))
  ([store opts]
   (market/ensure-perp-dexs!
    {:ensure-perp-dexs-data! ensure-perp-dexs-data!}
    store
    opts)))

(defn ensure-perp-dexs-data!
  ([store] (ensure-perp-dexs-data! store {}))
  ([store opts]
   (market/ensure-perp-dexs-data!
    {:active-api-service active-api-service
     :request-perp-dexs! request-perp-dexs!}
    store
    opts)))

(defn ensure-spot-meta-data!
  ([store] (ensure-spot-meta-data! store {}))
  ([store opts]
   (market/ensure-spot-meta-data!
    {:active-api-service active-api-service
     :request-spot-meta! request-spot-meta!}
    store
    opts)))

(defn ensure-spot-meta!
  ([store] (ensure-spot-meta! store {}))
  ([store opts]
   (market/ensure-spot-meta!
    {:ensure-spot-meta-data! ensure-spot-meta-data!}
    store
    opts)))

(defn ensure-public-webdata2!
  ([] (ensure-public-webdata2! {}))
  ([opts]
   (market/ensure-public-webdata2!
    {:active-api-service active-api-service
     :request-public-webdata2! request-public-webdata2!}
    opts)))

(defn fetch-asset-selector-markets!
  "Fetch and build a unified market list for the asset selector."
  ([store] (fetch-asset-selector-markets! store {:phase :full}))
  ([store opts]
   (market/fetch-asset-selector-markets!
    {:log-fn (api-log-fn)
     :request-asset-selector-markets! request-asset-selector-markets!}
    store
    opts)))

(defn request-asset-selector-markets!
  ([store] (request-asset-selector-markets! store {:phase :full}))
  ([store opts]
   (market/request-asset-selector-markets!
    {:opts opts
     :store store
     :ensure-perp-dexs-data! ensure-perp-dexs-data!
     :ensure-spot-meta-data! ensure-spot-meta-data!
     :ensure-public-webdata2! ensure-public-webdata2!
     :request-meta-and-asset-ctxs! request-meta-and-asset-ctxs!
     :now-ms-fn now-ms
     :log-fn (api-log-fn)})))

(defn fetch-spot-clearinghouse-state!
  ([store address] (fetch-spot-clearinghouse-state! store address {}))
  ([store address opts]
   (account/fetch-spot-clearinghouse-state!
    {:log-fn (api-log-fn)
     :request-spot-clearinghouse-state! request-spot-clearinghouse-state!}
    store
    address
    opts)))

(defn request-spot-clearinghouse-state!
  ([address] (request-spot-clearinghouse-state! address {}))
  ([address opts] (account/request-spot-clearinghouse-state! {:post-info! post-info!} address opts)))

(defn fetch-user-abstraction!
  "Fetch account abstraction mode for a user and project normalized account mode."
  ([store address] (fetch-user-abstraction! store address {}))
  ([store address opts]
   (account/fetch-user-abstraction!
    {:log-fn (api-log-fn)
     :request-user-abstraction! request-user-abstraction!}
    store
    address
    opts)))

(defn request-user-abstraction!
  ([address] (request-user-abstraction! address {}))
  ([address opts] (account/request-user-abstraction! {:post-info! post-info!} address opts)))

(defn request-portfolio!
  ([address] (request-portfolio! address {}))
  ([address opts] (account/request-portfolio! {:post-info! post-info!} address opts)))

(defn request-user-fees!
  ([address] (request-user-fees! address {}))
  ([address opts] (account/request-user-fees! {:post-info! post-info!} address opts)))

(defn request-user-non-funding-ledger-updates!
  ([address start-time-ms]
   (request-user-non-funding-ledger-updates! address start-time-ms nil {}))
  ([address start-time-ms end-time-ms]
   (request-user-non-funding-ledger-updates! address start-time-ms end-time-ms {}))
  ([address start-time-ms end-time-ms opts]
   (account/request-user-non-funding-ledger-updates!
    {:post-info! post-info!}
    address
    start-time-ms
    end-time-ms
    opts)))

(defn request-clearinghouse-state!
  ([address dex] (request-clearinghouse-state! address dex {}))
  ([address dex opts] (account/request-clearinghouse-state! {:post-info! post-info!} address dex opts)))

(defn fetch-clearinghouse-state!
  "Fetch clearinghouse state for a specific perp DEX."
  ([store address dex] (fetch-clearinghouse-state! store address dex {}))
  ([store address dex opts]
   (account/fetch-clearinghouse-state!
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
   (account/fetch-perp-dex-clearinghouse-states!
    {:fetch-clearinghouse-state! fetch-clearinghouse-state!}
    store
    address
    dex-names
    opts)))
