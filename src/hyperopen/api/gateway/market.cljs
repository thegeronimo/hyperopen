(ns hyperopen.api.gateway.market
  (:require [hyperopen.api.endpoints.market :as market-endpoints]
            [hyperopen.api.fetch-compat :as fetch-compat]
            [hyperopen.api.market-loader :as market-loader]
            [hyperopen.schema.api-market-contracts :as api-market-contracts]))

(defn- assert-perp-dex-metadata!
  [payload context]
  (api-market-contracts/assert-normalized-perp-dex-metadata! payload context))

(defn- with-validated-perp-dex-metadata
  [payload-promise context]
  (-> payload-promise
      (.then (fn [payload]
               (assert-perp-dex-metadata! payload context)))))

(defn- contract-checked-request-perp-dexs!
  [request-perp-dexs! boundary]
  (fn [opts]
    (with-validated-perp-dex-metadata
      (request-perp-dexs! opts)
      {:boundary boundary
       :opts opts})))

(defn- contract-checked-ensure-perp-dexs-data!
  [ensure-perp-dexs-data! boundary]
  (fn [store opts]
    (with-validated-perp-dex-metadata
      (ensure-perp-dexs-data! store opts)
      {:boundary boundary
       :opts opts})))

(defn build-market-state
  [now-ms-fn active-asset phase dexs spot-meta spot-asset-ctxs perp-results]
  (market-endpoints/build-market-state now-ms-fn
                                       active-asset
                                       phase
                                       dexs
                                       spot-meta
                                       spot-asset-ctxs
                                       perp-results))

(defn request-asset-contexts!
  [{:keys [post-info!]}
  opts]
  (market-endpoints/request-asset-contexts! post-info! opts))

(defn fetch-asset-contexts!
  [{:keys [log-fn
           request-asset-contexts!
           apply-asset-contexts-success
           apply-asset-contexts-error]}
   store
   opts]
  (fetch-compat/fetch-asset-contexts!
   {:log-fn log-fn
    :request-asset-contexts! request-asset-contexts!
    :apply-asset-contexts-success apply-asset-contexts-success
    :apply-asset-contexts-error apply-asset-contexts-error}
   store
   opts))

(defn request-meta-and-asset-ctxs!
  [{:keys [post-info!]}
   dex
   opts]
  (market-endpoints/request-meta-and-asset-ctxs! post-info! dex opts))

(defn request-perp-dexs!
  [{:keys [post-info!]}
   opts]
  ((contract-checked-request-perp-dexs!
     (partial market-endpoints/request-perp-dexs! post-info!)
     :api.gateway.market/request-perp-dexs)
   opts))

(defn fetch-perp-dexs!
  [{:keys [log-fn
           request-perp-dexs!
           apply-perp-dexs-success
           apply-perp-dexs-error]}
   store
   opts]
  (fetch-compat/fetch-perp-dexs!
   {:log-fn log-fn
    :request-perp-dexs! (contract-checked-request-perp-dexs!
                         request-perp-dexs!
                         :api.gateway.market/fetch-perp-dexs)
    :apply-perp-dexs-success apply-perp-dexs-success
    :apply-perp-dexs-error apply-perp-dexs-error}
   store
   opts))

(defn request-candle-snapshot!
  [{:keys [post-info! now-ms-fn]}
   coin
   opts]
  (market-endpoints/request-candle-snapshot! post-info!
                                             now-ms-fn
                                             coin
                                             opts))

(defn fetch-candle-snapshot!
  [{:keys [log-fn
           request-candle-snapshot!
           apply-candle-snapshot-success
           apply-candle-snapshot-error]}
   store
   opts]
  (fetch-compat/fetch-candle-snapshot!
   {:log-fn log-fn
    :request-candle-snapshot! request-candle-snapshot!
    :apply-candle-snapshot-success apply-candle-snapshot-success
    :apply-candle-snapshot-error apply-candle-snapshot-error}
   store
   opts))

(defn request-spot-meta!
  [{:keys [post-info!]}
   opts]
  (market-endpoints/request-spot-meta! post-info! opts))

(defn fetch-spot-meta!
  [{:keys [log-fn
           request-spot-meta!
           begin-spot-meta-load
           apply-spot-meta-success
           apply-spot-meta-error]}
   store
   opts]
  (fetch-compat/fetch-spot-meta!
   {:log-fn log-fn
    :request-spot-meta! request-spot-meta!
    :begin-spot-meta-load begin-spot-meta-load
    :apply-spot-meta-success apply-spot-meta-success
    :apply-spot-meta-error apply-spot-meta-error}
   store
   opts))

(defn request-spot-meta-raw!
  [{:keys [request-spot-meta!]}
   opts]
  (request-spot-meta! opts))

(defn fetch-spot-meta-raw!
  "Deprecated compatibility alias for `request-spot-meta-raw!`."
  [deps
   opts]
  (request-spot-meta-raw! deps opts))

(defn request-public-webdata2!
  [{:keys [post-info!]}
   opts]
  (market-endpoints/request-public-webdata2! post-info! opts))

(defn fetch-public-webdata2!
  "Deprecated compatibility alias for `request-public-webdata2!`."
  [{:keys [request-public-webdata2!]}
   opts]
  (request-public-webdata2! opts))

(defn ensure-perp-dexs!
  [{:keys [ensure-perp-dexs-data!
           apply-perp-dexs-success
           apply-perp-dexs-error]}
   store
   opts]
  (fetch-compat/ensure-perp-dexs!
   {:ensure-perp-dexs-data! (contract-checked-ensure-perp-dexs-data!
                             ensure-perp-dexs-data!
                             :api.gateway.market/ensure-perp-dexs)
    :apply-perp-dexs-success apply-perp-dexs-success
    :apply-perp-dexs-error apply-perp-dexs-error}
   store
   opts))

(defn ensure-spot-meta!
  [{:keys [ensure-spot-meta-data!
           apply-spot-meta-success
           apply-spot-meta-error]}
   store
   opts]
  (fetch-compat/ensure-spot-meta!
   {:ensure-spot-meta-data! ensure-spot-meta-data!
    :apply-spot-meta-success apply-spot-meta-success
    :apply-spot-meta-error apply-spot-meta-error}
   store
   opts))

(defn fetch-asset-selector-markets!
  [{:keys [log-fn
           request-asset-selector-markets!
           begin-asset-selector-load
           apply-spot-meta-success
           apply-asset-selector-success
           apply-asset-selector-error]}
   store
   opts]
  (fetch-compat/fetch-asset-selector-markets!
   {:log-fn log-fn
    :request-asset-selector-markets! request-asset-selector-markets!
    :begin-asset-selector-load begin-asset-selector-load
    :apply-spot-meta-success apply-spot-meta-success
    :apply-asset-selector-success apply-asset-selector-success
    :apply-asset-selector-error apply-asset-selector-error}
   store
   opts))

(defn request-asset-selector-markets!
  [{:keys [opts
           active-asset
           ensure-perp-dexs-data!
           ensure-spot-meta-data!
           ensure-public-webdata2!
           request-meta-and-asset-ctxs!
           build-market-state
           log-fn]}]
  (market-loader/request-asset-selector-markets!
   {:opts opts
    :active-asset active-asset
    :ensure-perp-dexs-data! ensure-perp-dexs-data!
    :ensure-spot-meta-data! ensure-spot-meta-data!
    :ensure-public-webdata2! ensure-public-webdata2!
    :request-meta-and-asset-ctxs! request-meta-and-asset-ctxs!
    :build-market-state build-market-state
    :log-fn log-fn}))
