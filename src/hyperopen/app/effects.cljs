(ns hyperopen.app.effects
  (:require [clojure.string :as str]
            [hyperopen.runtime.action-adapters :as action-adapters]
            [hyperopen.route-modules :as route-modules]
            [hyperopen.runtime.collaborators :as runtime-collaborators]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.effect-adapters.margin-rec :as margin-rec-effects]
            [hyperopen.runtime.effect-adapters.pnl-share :as pnl-share-effects]
            [hyperopen.runtime.effect-adapters.spectate-mode :as spectate-mode-effects]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.schema.runtime-registration.portfolio :as portfolio-registration]
            [hyperopen.schema.runtime-registration.vaults :as vault-registration]))

(defn- optimizer-handler-key?
  [handler-key]
  (str/includes? (name handler-key) "portfolio-optimizer"))

(def ^:private lazy-portfolio-optimizer-effect-keys
  (->> portfolio-registration/effect-binding-rows
       (map second)
       (filter optimizer-handler-key?)
       vec))

(def ^:private lazy-vault-effect-keys
  (->> vault-registration/effect-binding-rows
       (map second)
       vec))

(defn- runtime-effect-overrides
  [runtime]
  (let [lazy-portfolio-optimizer-effect-deps
        (route-modules/lazy-route-effect-leaf-deps
         runtime
         :portfolio
         :portfolio-optimizer
         lazy-portfolio-optimizer-effect-keys)
        lazy-vault-effect-deps
        (route-modules/lazy-route-effect-leaf-deps
         runtime
         :vaults
         :api
         lazy-vault-effect-keys)]
    {:storage {:save effect-adapters/save
               :save-many effect-adapters/save-many
               :local-storage-set effect-adapters/local-storage-set
               :local-storage-set-json effect-adapters/local-storage-set-json
               :apply-ui-theme effect-adapters/apply-ui-theme
               :persist-leaderboard-preferences effect-adapters/persist-leaderboard-preferences-effect}
     :asset-selector {:queue-asset-icon-status (effect-adapters/make-queue-asset-icon-status runtime)
                      :sync-asset-selector-active-ctx-subscriptions effect-adapters/sync-asset-selector-active-ctx-subscriptions}
     :navigation {:push-state effect-adapters/push-state
                  :replace-state effect-adapters/replace-state
                  :replace-shareable-route-query effect-adapters/replace-shareable-route-query
                  :load-route-module effect-adapters/load-route-module-effect
                  :load-surface-module effect-adapters/load-surface-module-effect
                  :load-account-tab-module effect-adapters/load-account-tab-module-effect
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
              :set-agent-local-protection-mode effect-adapters/set-agent-local-protection-mode
              :unlock-agent-trading effect-adapters/unlock-agent-trading
              :copy-wallet-address (effect-adapters/make-copy-wallet-address runtime)
              :copy-spectate-link (effect-adapters/make-copy-spectate-link runtime)}
     :pnl-share {:export-pnl-share-card-png pnl-share-effects/export-pnl-share-card-png
                 :copy-pnl-share-link pnl-share-effects/copy-pnl-share-link
                 :resolve-pnl-share-icon pnl-share-effects/resolve-pnl-share-icon}
     :spectate-mode {:clear-disconnected-account-lifecycle effect-adapters/clear-disconnected-account-lifecycle
                     :download-spectate-watchlist-file spectate-mode-effects/download-spectate-watchlist-file
                     :pick-spectate-watchlist-file spectate-mode-effects/pick-spectate-watchlist-file
                     :spectate-watchlist-feedback spectate-mode-effects/spectate-watchlist-feedback}
     :diagnostics {:confirm-ws-diagnostics-reveal effect-adapters/confirm-ws-diagnostics-reveal
                   :copy-websocket-diagnostics effect-adapters/copy-websocket-diagnostics
                   :ws-reset-subscriptions effect-adapters/ws-reset-subscriptions}
     :api-wallets {:api-load-api-wallets effect-adapters/api-load-api-wallets-effect
                   :generate-api-wallet effect-adapters/generate-api-wallet-effect
                   :api-authorize-api-wallet effect-adapters/api-authorize-api-wallet-effect
                   :api-remove-api-wallet effect-adapters/api-remove-api-wallet-effect}
     :subaccounts {:api-load-subaccounts effect-adapters/api-load-subaccounts-effect
                   :api-refresh-subaccounts effect-adapters/api-refresh-subaccounts-effect
                   :api-create-subaccount effect-adapters/api-create-subaccount-effect
                   :api-rename-subaccount effect-adapters/api-rename-subaccount-effect
                   :api-transfer-subaccount effect-adapters/api-transfer-subaccount-effect}
     :orders {:api-submit-order (effect-adapters/make-api-submit-order runtime)
              :confirm-api-submit-order effect-adapters/confirm-api-submit-order
              :api-cancel-order (effect-adapters/make-api-cancel-order runtime)
              :api-submit-position-tpsl (effect-adapters/make-api-submit-position-tpsl runtime)
              :api-submit-position-margin (effect-adapters/make-api-submit-position-margin runtime)
              :clear-order-feedback-toast-timeout (effect-adapters/make-clear-order-feedback-toast-timeout runtime)}
     :margin-rec {:margin-rec-fetch-fills margin-rec-effects/margin-rec-fetch-fills-effect
                  :margin-rec-compute margin-rec-effects/margin-rec-compute-effect}
     :api (merge {:fetch-asset-selector-markets effect-adapters/fetch-asset-selector-markets-effect
                  :restore-dialog-focus effect-adapters/restore-dialog-focus-effect
                  :sync-active-asset-funding-predictability effect-adapters/sync-active-asset-funding-predictability
                  :api-fetch-leaderboard effect-adapters/api-fetch-leaderboard-effect
                  :api-fetch-predicted-fundings effect-adapters/api-fetch-predicted-fundings-effect
                  :api-load-user-data effect-adapters/api-load-user-data-effect
                  :api-fetch-trader-portfolio-benchmark effect-adapters/api-fetch-trader-portfolio-benchmark-effect
                  :api-fetch-staking-validator-summaries effect-adapters/api-fetch-staking-validator-summaries-effect
                  :api-fetch-staking-delegator-summary effect-adapters/api-fetch-staking-delegator-summary-effect
                  :api-fetch-staking-delegations effect-adapters/api-fetch-staking-delegations-effect
                  :api-fetch-staking-rewards effect-adapters/api-fetch-staking-rewards-effect
                  :api-fetch-staking-history effect-adapters/api-fetch-staking-history-effect
                  :api-fetch-staking-spot-state effect-adapters/api-fetch-staking-spot-state-effect
                  :api-fetch-referral effect-adapters/api-fetch-referral-effect
                  :api-set-referrer effect-adapters/api-set-referrer-effect
                  :api-register-referrer effect-adapters/api-register-referrer-effect
                  :api-claim-referral-rewards effect-adapters/api-claim-referral-rewards-effect
                  :api-submit-staking-deposit effect-adapters/api-submit-staking-deposit-effect
                  :api-submit-staking-withdraw effect-adapters/api-submit-staking-withdraw-effect
                  :api-submit-staking-delegate effect-adapters/api-submit-staking-delegate-effect
                  :api-submit-staking-undelegate effect-adapters/api-submit-staking-undelegate-effect
                  :api-fetch-hyperunit-fee-estimate effect-adapters/api-fetch-hyperunit-fee-estimate-effect
                  :api-fetch-hyperunit-withdrawal-queue effect-adapters/api-fetch-hyperunit-withdrawal-queue-effect
                  :api-submit-funding-transfer effect-adapters/api-submit-funding-transfer-effect
                  :api-submit-funding-send effect-adapters/api-submit-funding-send-effect
                  :api-submit-funding-repay effect-adapters/api-submit-funding-repay-effect
                  :api-submit-funding-withdraw effect-adapters/api-submit-funding-withdraw-effect
                  :api-submit-funding-deposit effect-adapters/api-submit-funding-deposit-effect}
                 (:api lazy-vault-effect-deps))
     :portfolio-optimizer (:portfolio-optimizer lazy-portfolio-optimizer-effect-deps)}))

(defn runtime-effect-deps
  ([] (runtime-effect-deps runtime-state/runtime))
  ([runtime]
   (runtime-collaborators/runtime-effect-deps
    (runtime-effect-overrides runtime))))
