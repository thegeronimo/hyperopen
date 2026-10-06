(ns hyperopen.startup.collaborators.api-ops
  (:require [hyperopen.api.default :as api-default]))

(defn- resolve-api-op
  [api-instance key fallback]
  (or (get api-instance key) fallback))

(defn resolve-api-ops
  [api-instance]
  {:get-request-stats (resolve-api-op api-instance :get-request-stats api-default/get-request-stats)
   :request-frontend-open-orders! (resolve-api-op api-instance :request-frontend-open-orders! api-default/request-frontend-open-orders!)
   :request-clearinghouse-state! (resolve-api-op api-instance :request-clearinghouse-state! api-default/request-clearinghouse-state!)
   :request-user-fills! (resolve-api-op api-instance :request-user-fills! api-default/request-user-fills!)
   :request-historical-orders! (resolve-api-op api-instance :request-historical-orders! api-default/request-historical-orders!)
   :request-spot-clearinghouse-state! (resolve-api-op api-instance :request-spot-clearinghouse-state! api-default/request-spot-clearinghouse-state!)
   :request-user-abstraction! (resolve-api-op api-instance :request-user-abstraction! api-default/request-user-abstraction!)
   :request-portfolio! (resolve-api-op api-instance :request-portfolio! api-default/request-portfolio!)
   :request-user-fees! (resolve-api-op api-instance :request-user-fees! api-default/request-user-fees!)
   :request-user-vault-equities! (resolve-api-op api-instance :request-user-vault-equities! api-default/request-user-vault-equities!)
   :request-staking-delegator-summary! (resolve-api-op api-instance :request-staking-delegator-summary! api-default/request-staking-delegator-summary!)
   :request-user-non-funding-ledger-updates! (resolve-api-op api-instance :request-user-non-funding-ledger-updates! api-default/request-user-non-funding-ledger-updates!)
   :ensure-perp-dexs-data! (resolve-api-op api-instance :ensure-perp-dexs-data! api-default/ensure-perp-dexs-data!)
   :request-asset-contexts! (resolve-api-op api-instance :request-asset-contexts! api-default/request-asset-contexts!)
   :request-asset-selector-markets! (resolve-api-op api-instance :request-asset-selector-markets! api-default/request-asset-selector-markets!)})
