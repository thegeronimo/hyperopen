(ns hyperopen.vaults.route-runtime-module
  (:require [hyperopen.runtime.collaborators.vaults :as vault-collaborators]
            [hyperopen.runtime.effect-adapters.vaults :as vault-effect-adapters]))

(def ^:private eager-action-keys
  #{:load-vault-route
    :load-vaults
    :load-vault-detail})

(defn ^:export action-deps
  []
  {:vaults
   (apply dissoc
          (vault-collaborators/action-deps)
          eager-action-keys)})

(defn ^:export effect-deps
  [_runtime]
  {:api
   {:api-fetch-vault-index vault-effect-adapters/api-fetch-vault-index-effect
    :api-fetch-vault-index-with-cache vault-effect-adapters/api-fetch-vault-index-with-cache-effect
    :api-fetch-vault-summaries vault-effect-adapters/api-fetch-vault-summaries-effect
    :api-fetch-user-vault-equities vault-effect-adapters/api-fetch-user-vault-equities-effect
    :api-fetch-vault-details vault-effect-adapters/api-fetch-vault-details-effect
    :api-fetch-vault-benchmark-details vault-effect-adapters/api-fetch-vault-benchmark-details-effect
    :api-fetch-vault-webdata2 vault-effect-adapters/api-fetch-vault-webdata2-effect
    :api-fetch-vault-fills vault-effect-adapters/api-fetch-vault-fills-effect
    :api-fetch-vault-funding-history vault-effect-adapters/api-fetch-vault-funding-history-effect
    :api-fetch-vault-order-history vault-effect-adapters/api-fetch-vault-order-history-effect
    :api-fetch-vault-ledger-updates vault-effect-adapters/api-fetch-vault-ledger-updates-effect
    :api-submit-vault-transfer vault-effect-adapters/api-submit-vault-transfer-effect
    :load-vault-screener-cache-index vault-effect-adapters/load-vault-screener-cache-index-effect
    :run-vault-screener vault-effect-adapters/run-vault-screener-effect}})

(goog/exportSymbol "hyperopen.vaults.route_runtime_module.action_deps" action-deps)
(goog/exportSymbol "hyperopen.vaults.route_runtime_module.effect_deps" effect-deps)
