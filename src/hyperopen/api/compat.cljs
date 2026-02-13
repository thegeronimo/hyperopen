(ns hyperopen.api.compat
  (:require [hyperopen.api.gateway.account :as account-gateway]
            [hyperopen.api.gateway.market :as market-gateway]
            [hyperopen.api.gateway.orders :as order-gateway]
            [hyperopen.api.projections :as api-projections]))

(defn fetch-asset-contexts!
  [{:keys [log-fn request-asset-contexts!]}
   store
   opts]
  (market-gateway/fetch-asset-contexts!
   {:log-fn log-fn
    :request-asset-contexts! request-asset-contexts!
    :apply-asset-contexts-success api-projections/apply-asset-contexts-success
    :apply-asset-contexts-error api-projections/apply-asset-contexts-error}
   store
   opts))

(defn fetch-perp-dexs!
  [{:keys [log-fn request-perp-dexs!]}
   store
   opts]
  (market-gateway/fetch-perp-dexs!
   {:log-fn log-fn
    :request-perp-dexs! request-perp-dexs!
    :apply-perp-dexs-success api-projections/apply-perp-dexs-success
    :apply-perp-dexs-error api-projections/apply-perp-dexs-error}
   store
   opts))

(defn fetch-candle-snapshot!
  [{:keys [log-fn request-candle-snapshot!]}
   store
   opts]
  (market-gateway/fetch-candle-snapshot!
   {:log-fn log-fn
    :request-candle-snapshot! request-candle-snapshot!
    :apply-candle-snapshot-success api-projections/apply-candle-snapshot-success
    :apply-candle-snapshot-error api-projections/apply-candle-snapshot-error}
   store
   opts))

(defn fetch-frontend-open-orders!
  ([deps store address]
   (fetch-frontend-open-orders! deps store address nil {}))
  ([deps store address dex-or-opts]
   (if (map? dex-or-opts)
     (fetch-frontend-open-orders! deps store address nil dex-or-opts)
     (fetch-frontend-open-orders! deps store address dex-or-opts {})))
  ([{:keys [log-fn request-frontend-open-orders!]}
    store
    address
    dex
    opts]
   (order-gateway/fetch-frontend-open-orders!
    {:log-fn log-fn
     :request-frontend-open-orders! request-frontend-open-orders!
     :apply-open-orders-success api-projections/apply-open-orders-success
     :apply-open-orders-error api-projections/apply-open-orders-error}
    store
    address
    dex
    opts)))

(defn fetch-user-fills!
  [{:keys [log-fn request-user-fills!]}
   store
   address
   opts]
  (order-gateway/fetch-user-fills!
   {:log-fn log-fn
    :request-user-fills! request-user-fills!
    :apply-user-fills-success api-projections/apply-user-fills-success
    :apply-user-fills-error api-projections/apply-user-fills-error}
   store
   address
   opts))

(defn fetch-historical-orders!
  [{:keys [log-fn post-info!]}
   address
   opts]
  (order-gateway/request-historical-orders-data!
   {:log-fn log-fn
    :post-info! post-info!}
   address
   opts))

(defn fetch-spot-meta!
  [{:keys [log-fn request-spot-meta!]}
   store
   opts]
  (market-gateway/fetch-spot-meta!
   {:log-fn log-fn
    :request-spot-meta! request-spot-meta!
    :begin-spot-meta-load api-projections/begin-spot-meta-load
    :apply-spot-meta-success api-projections/apply-spot-meta-success
    :apply-spot-meta-error api-projections/apply-spot-meta-error}
   store
   opts))

(defn ensure-perp-dexs!
  [{:keys [ensure-perp-dexs-data!]}
   store
   opts]
  (market-gateway/ensure-perp-dexs!
   {:ensure-perp-dexs-data! ensure-perp-dexs-data!
    :apply-perp-dexs-success api-projections/apply-perp-dexs-success
    :apply-perp-dexs-error api-projections/apply-perp-dexs-error}
   store
   opts))

(defn ensure-spot-meta!
  [{:keys [ensure-spot-meta-data!]}
   store
   opts]
  (market-gateway/ensure-spot-meta!
   {:ensure-spot-meta-data! ensure-spot-meta-data!
    :apply-spot-meta-success api-projections/apply-spot-meta-success
    :apply-spot-meta-error api-projections/apply-spot-meta-error}
   store
   opts))

(defn fetch-asset-selector-markets!
  [{:keys [log-fn request-asset-selector-markets!]}
   store
   opts]
  (market-gateway/fetch-asset-selector-markets!
   {:log-fn log-fn
    :request-asset-selector-markets! request-asset-selector-markets!
    :begin-asset-selector-load api-projections/begin-asset-selector-load
    :apply-asset-selector-success api-projections/apply-asset-selector-success
    :apply-asset-selector-error api-projections/apply-asset-selector-error}
   store
   opts))

(defn fetch-spot-clearinghouse-state!
  [{:keys [log-fn request-spot-clearinghouse-state!]}
   store
   address
   opts]
  (account-gateway/fetch-spot-clearinghouse-state!
   {:log-fn log-fn
    :request-spot-clearinghouse-state! request-spot-clearinghouse-state!
    :begin-spot-balances-load api-projections/begin-spot-balances-load
    :apply-spot-balances-success api-projections/apply-spot-balances-success
    :apply-spot-balances-error api-projections/apply-spot-balances-error}
   store
   address
   opts))

(defn fetch-user-abstraction!
  [{:keys [log-fn request-user-abstraction!]}
   store
   address
   opts]
  (account-gateway/fetch-user-abstraction!
   {:log-fn log-fn
    :request-user-abstraction! request-user-abstraction!
    :normalize-user-abstraction-mode account-gateway/normalize-user-abstraction-mode
    :apply-user-abstraction-snapshot api-projections/apply-user-abstraction-snapshot}
   store
   address
   opts))

(defn fetch-clearinghouse-state!
  [{:keys [log-fn request-clearinghouse-state!]}
   store
   address
   dex
   opts]
  (account-gateway/fetch-clearinghouse-state!
   {:log-fn log-fn
    :request-clearinghouse-state! request-clearinghouse-state!
    :apply-perp-dex-clearinghouse-success api-projections/apply-perp-dex-clearinghouse-success
    :apply-perp-dex-clearinghouse-error api-projections/apply-perp-dex-clearinghouse-error}
   store
   address
   dex
   opts))

(defn fetch-perp-dex-clearinghouse-states!
  [{:keys [fetch-clearinghouse-state!]}
   store
   address
   dex-names
   opts]
  (account-gateway/fetch-perp-dex-clearinghouse-states!
   {:fetch-clearinghouse-state! fetch-clearinghouse-state!}
   store
   address
   dex-names
   opts))
