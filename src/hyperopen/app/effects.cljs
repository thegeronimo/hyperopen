(ns hyperopen.app.effects
  (:require [hyperopen.runtime.action-adapters :as action-adapters]
            [hyperopen.runtime.collaborators :as runtime-collaborators]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.state :as runtime-state]))

(defn- runtime-effect-overrides
  [runtime]
  {:storage {:save effect-adapters/save
             :save-many effect-adapters/save-many
             :local-storage-set effect-adapters/local-storage-set
             :local-storage-set-json effect-adapters/local-storage-set-json
             :persist-leaderboard-preferences effect-adapters/persist-leaderboard-preferences-effect}
   :asset-selector {:queue-asset-icon-status (effect-adapters/make-queue-asset-icon-status runtime)
                    :sync-asset-selector-active-ctx-subscriptions effect-adapters/sync-asset-selector-active-ctx-subscriptions}
   :navigation {:push-state effect-adapters/push-state
                :replace-state effect-adapters/replace-state
                :load-route-module effect-adapters/load-route-module-effect
                :load-trade-chart-module effect-adapters/load-trade-chart-module-effect
                :load-trading-indicators-module effect-adapters/load-trading-indicators-module-effect}
   :websocket {:init-websocket effect-adapters/init-websocket
               :subscribe-active-asset effect-adapters/subscribe-active-asset
               :sync-active-candle-subscription effect-adapters/sync-active-candle-subscription
               :subscribe-orderbook effect-adapters/subscribe-orderbook
               :subscribe-trades effect-adapters/subscribe-trades
               :subscribe-webdata2 effect-adapters/subscribe-webdata2
               :fetch-candle-snapshot effect-adapters/fetch-candle-snapshot
               :unsubscribe-active-asset effect-adapters/unsubscribe-active-asset
               :unsubscribe-orderbook effect-adapters/unsubscribe-orderbook
               :unsubscribe-trades effect-adapters/unsubscribe-trades
               :unsubscribe-webdata2 effect-adapters/unsubscribe-webdata2
               :reconnect-websocket effect-adapters/reconnect-websocket
               :refresh-websocket-health (effect-adapters/make-refresh-websocket-health runtime)}
   :wallet {:connect-wallet effect-adapters/connect-wallet
            :disconnect-wallet (effect-adapters/make-disconnect-wallet runtime)
            :enable-agent-trading action-adapters/enable-agent-trading
            :set-agent-storage-mode effect-adapters/set-agent-storage-mode
            :copy-wallet-address (effect-adapters/make-copy-wallet-address runtime)
            :copy-spectate-link (effect-adapters/make-copy-spectate-link runtime)}
   :spectate-mode {:clear-disconnected-account-lifecycle effect-adapters/clear-disconnected-account-lifecycle}
   :diagnostics {:confirm-ws-diagnostics-reveal effect-adapters/confirm-ws-diagnostics-reveal
                 :copy-websocket-diagnostics effect-adapters/copy-websocket-diagnostics
                 :ws-reset-subscriptions effect-adapters/ws-reset-subscriptions}
   :api-wallets {:api-load-api-wallets effect-adapters/api-load-api-wallets-effect
                 :generate-api-wallet effect-adapters/generate-api-wallet-effect
                 :api-authorize-api-wallet effect-adapters/api-authorize-api-wallet-effect
                 :api-remove-api-wallet effect-adapters/api-remove-api-wallet-effect}
   :orders {:api-submit-order (effect-adapters/make-api-submit-order runtime)
            :confirm-api-submit-order effect-adapters/confirm-api-submit-order
            :api-cancel-order (effect-adapters/make-api-cancel-order runtime)
            :api-submit-position-tpsl (effect-adapters/make-api-submit-position-tpsl runtime)
            :api-submit-position-margin (effect-adapters/make-api-submit-position-margin runtime)}
   :api {:fetch-asset-selector-markets effect-adapters/fetch-asset-selector-markets-effect
         :restore-dialog-focus effect-adapters/restore-dialog-focus-effect
         :sync-active-asset-funding-predictability effect-adapters/sync-active-asset-funding-predictability
         :api-fetch-leaderboard effect-adapters/api-fetch-leaderboard-effect
         :api-fetch-predicted-fundings effect-adapters/api-fetch-predicted-fundings-effect
         :api-load-user-data effect-adapters/api-load-user-data-effect
         :api-fetch-vault-index effect-adapters/api-fetch-vault-index-effect
         :api-fetch-vault-index-with-cache effect-adapters/api-fetch-vault-index-with-cache-effect
         :api-fetch-vault-summaries effect-adapters/api-fetch-vault-summaries-effect
         :api-fetch-user-vault-equities effect-adapters/api-fetch-user-vault-equities-effect
         :api-fetch-vault-details effect-adapters/api-fetch-vault-details-effect
         :api-fetch-vault-benchmark-details effect-adapters/api-fetch-vault-benchmark-details-effect
         :api-fetch-vault-webdata2 effect-adapters/api-fetch-vault-webdata2-effect
         :api-fetch-vault-fills effect-adapters/api-fetch-vault-fills-effect
         :api-fetch-vault-funding-history effect-adapters/api-fetch-vault-funding-history-effect
         :api-fetch-vault-order-history effect-adapters/api-fetch-vault-order-history-effect
         :api-fetch-vault-ledger-updates effect-adapters/api-fetch-vault-ledger-updates-effect
         :api-submit-vault-transfer effect-adapters/api-submit-vault-transfer-effect
         :api-fetch-staking-validator-summaries effect-adapters/api-fetch-staking-validator-summaries-effect
         :api-fetch-staking-delegator-summary effect-adapters/api-fetch-staking-delegator-summary-effect
         :api-fetch-staking-delegations effect-adapters/api-fetch-staking-delegations-effect
         :api-fetch-staking-rewards effect-adapters/api-fetch-staking-rewards-effect
         :api-fetch-staking-history effect-adapters/api-fetch-staking-history-effect
         :api-fetch-staking-spot-state effect-adapters/api-fetch-staking-spot-state-effect
         :api-submit-staking-deposit effect-adapters/api-submit-staking-deposit-effect
         :api-submit-staking-withdraw effect-adapters/api-submit-staking-withdraw-effect
         :api-submit-staking-delegate effect-adapters/api-submit-staking-delegate-effect
         :api-submit-staking-undelegate effect-adapters/api-submit-staking-undelegate-effect
         :api-fetch-hyperunit-fee-estimate effect-adapters/api-fetch-hyperunit-fee-estimate-effect
         :api-fetch-hyperunit-withdrawal-queue effect-adapters/api-fetch-hyperunit-withdrawal-queue-effect
         :api-submit-funding-transfer effect-adapters/api-submit-funding-transfer-effect
         :api-submit-funding-send effect-adapters/api-submit-funding-send-effect
         :api-submit-funding-withdraw effect-adapters/api-submit-funding-withdraw-effect
         :api-submit-funding-deposit effect-adapters/api-submit-funding-deposit-effect}})

(defn runtime-effect-deps
  ([] (runtime-effect-deps runtime-state/runtime))
  ([runtime]
   (runtime-collaborators/runtime-effect-deps
    (runtime-effect-overrides runtime))))
