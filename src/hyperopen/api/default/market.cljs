(ns hyperopen.api.default.market
  (:require [hyperopen.api.compat :as api-compat]
            [hyperopen.api.gateway.market :as market-gateway]
            [hyperopen.api.service :as api-service]))

(defn request-asset-contexts!
  [{:keys [post-info!]} opts]
  (market-gateway/request-asset-contexts! {:post-info! post-info!} opts))

(defn fetch-asset-contexts!
  [{:keys [log-fn request-asset-contexts!]} store opts]
  (api-compat/fetch-asset-contexts!
   {:log-fn log-fn
    :request-asset-contexts! request-asset-contexts!}
   store
   opts))

(defn request-meta-and-asset-ctxs!
  [{:keys [post-info!]} dex opts]
  (market-gateway/request-meta-and-asset-ctxs! {:post-info! post-info!} dex opts))

(defn request-perp-dexs!
  [{:keys [post-info!]} opts]
  (market-gateway/request-perp-dexs! {:post-info! post-info!} opts))

(defn fetch-perp-dexs!
  [{:keys [log-fn request-perp-dexs!]} store opts]
  (api-compat/fetch-perp-dexs!
   {:log-fn log-fn
    :request-perp-dexs! request-perp-dexs!}
   store
   opts))

(defn request-candle-snapshot!
  [{:keys [post-info! now-ms-fn]} coin opts]
  (market-gateway/request-candle-snapshot!
   {:post-info! post-info!
    :now-ms-fn now-ms-fn}
   coin
   opts))

(defn fetch-candle-snapshot!
  [{:keys [log-fn request-candle-snapshot!]} store opts]
  (api-compat/fetch-candle-snapshot!
   {:log-fn log-fn
    :request-candle-snapshot! request-candle-snapshot!}
   store
   opts))

(defn request-spot-meta!
  [{:keys [post-info!]} opts]
  (market-gateway/request-spot-meta! {:post-info! post-info!} opts))

(defn request-outcome-meta!
  [{:keys [post-info!]} opts]
  (market-gateway/request-outcome-meta! {:post-info! post-info!} opts))

(defn fetch-spot-meta!
  [{:keys [log-fn request-spot-meta!]} store opts]
  (api-compat/fetch-spot-meta!
   {:log-fn log-fn
    :request-spot-meta! request-spot-meta!}
   store
   opts))

(defn request-public-webdata2!
  [{:keys [post-info!]} opts]
  (market-gateway/request-public-webdata2! {:post-info! post-info!} opts))

(defn request-market-funding-history!
  [{:keys [post-info!]} coin opts]
  (market-gateway/request-market-funding-history! {:post-info! post-info!} coin opts))

(defn request-predicted-fundings!
  [{:keys [post-info!]} opts]
  (market-gateway/request-predicted-fundings! {:post-info! post-info!} opts))

(defn ensure-perp-dexs!
  [{:keys [ensure-perp-dexs-data!]} store opts]
  (api-compat/ensure-perp-dexs!
   {:ensure-perp-dexs-data! ensure-perp-dexs-data!}
   store
   opts))

(defn ensure-perp-dexs-data!
  [{:keys [active-api-service request-perp-dexs!]} store opts]
  (api-service/ensure-perp-dexs-data!
   (active-api-service)
   store
   request-perp-dexs!
   opts))

(defn ensure-spot-meta-data!
  [{:keys [active-api-service request-spot-meta!]} store opts]
  (api-service/ensure-spot-meta-data!
   (active-api-service)
   store
   request-spot-meta!
   opts))

(defn ensure-outcome-meta-data!
  [{:keys [active-api-service request-outcome-meta!]} store opts]
  (api-service/ensure-outcome-meta-data!
   (active-api-service)
   store
   request-outcome-meta!
   opts))

(defn ensure-spot-meta!
  [{:keys [ensure-spot-meta-data!]} store opts]
  (api-compat/ensure-spot-meta!
   {:ensure-spot-meta-data! ensure-spot-meta-data!}
   store
   opts))

(defn ensure-public-webdata2!
  [{:keys [active-api-service request-public-webdata2!]} opts]
  (api-service/ensure-public-webdata2!
   (active-api-service)
   request-public-webdata2!
   opts))

(defn- build-market-state-with-optional-outcomes
  [now-ms-fn active-asset phase dexs spot-meta spot-asset-ctxs perp-results outcome-meta]
  (let [builder market-gateway/build-market-state]
    (if (some? (.-cljs$core$IFn$_invoke$arity$8 builder))
      (builder now-ms-fn active-asset phase dexs spot-meta spot-asset-ctxs perp-results outcome-meta)
      (builder now-ms-fn active-asset phase dexs spot-meta spot-asset-ctxs perp-results))))

(defn fetch-asset-selector-markets!
  [{:keys [log-fn request-asset-selector-markets!]} store opts]
  (api-compat/fetch-asset-selector-markets!
   {:log-fn log-fn
    :request-asset-selector-markets! request-asset-selector-markets!}
   store
   opts))

(defn request-asset-selector-markets!
  [{:keys [opts
           store
           ensure-perp-dexs-data!
           ensure-spot-meta-data!
           ensure-outcome-meta-data!
           ensure-public-webdata2!
           request-meta-and-asset-ctxs!
           now-ms-fn
           log-fn]}]
  (market-gateway/request-asset-selector-markets!
   {:opts opts
    :active-asset (:active-asset @store)
    :ensure-perp-dexs-data! (fn [request-opts]
                              (ensure-perp-dexs-data! store request-opts))
    :ensure-spot-meta-data! (fn [request-opts]
                              (ensure-spot-meta-data! store request-opts))
    :ensure-outcome-meta-data! (fn [request-opts]
                                 (ensure-outcome-meta-data! store request-opts))
    :ensure-public-webdata2! ensure-public-webdata2!
    :request-meta-and-asset-ctxs! request-meta-and-asset-ctxs!
    :build-market-state (fn [active-asset phase dexs spot-meta spot-asset-ctxs perp-results outcome-meta]
                          (build-market-state-with-optional-outcomes now-ms-fn
                                                                     active-asset
                                                                     phase
                                                                     dexs
                                                                     spot-meta
                                                                     spot-asset-ctxs
                                                                     perp-results
                                                                     outcome-meta))
    :log-fn log-fn}))
