(ns hyperopen.runtime.effect-adapters.vaults
  (:require [nexus.registry :as nxr]
            [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.runtime.effect-adapters.common :as common]
            [hyperopen.vaults.effects :as vault-effects]))

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

(defn api-fetch-vault-benchmark-details-effect
  [_ store vault-address]
  (vault-effects/api-fetch-vault-benchmark-details!
   {:store store
    :vault-address vault-address
    :request-vault-details! api/request-vault-details!
    :begin-vault-benchmark-details-load api-projections/begin-vault-benchmark-details-load
    :apply-vault-benchmark-details-success api-projections/apply-vault-benchmark-details-success
    :apply-vault-benchmark-details-error api-projections/apply-vault-benchmark-details-error}))

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
  ([_ store request]
   (api-submit-vault-transfer-effect nil store request {}))
  ([_ store request {:keys [show-toast!]
                     :or {show-toast! (fn [_store _kind _message] nil)}}]
   (vault-effects/api-submit-vault-transfer!
    {:store store
     :request request
     :dispatch! nxr/dispatch
     :exchange-response-error common/exchange-response-error
     :runtime-error-message common/runtime-error-message
     :show-toast! show-toast!})))
