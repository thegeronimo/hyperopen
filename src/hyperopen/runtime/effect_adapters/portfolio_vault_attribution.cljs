(ns hyperopen.runtime.effect-adapters.portfolio-vault-attribution
  (:require [hyperopen.api.default :as api]
            [hyperopen.api.projections.portfolio-vault-attribution :as projections]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.vault-attribution-effects :as effects]))

(defn api-fetch-portfolio-vault-attribution-effect
  [_ store address]
  (effects/fetch-vault-attribution!
   {:store store
    :address address
    :request-user-non-funding-ledger-updates! api/request-user-non-funding-ledger-updates!
    :request-vault-details! api/request-vault-details!
    :begin-load projections/begin-load
    :apply-success projections/apply-success
    :apply-error projections/apply-error
    :now-ms platform/now-ms}))
