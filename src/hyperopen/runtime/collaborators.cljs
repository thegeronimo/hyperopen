(ns hyperopen.runtime.collaborators
  (:require [hyperopen.account.history.effects :as account-history-effects]
            [hyperopen.funding.effects :as funding-effects]
            [hyperopen.funding-comparison.effects :as funding-comparison-effects]
            [hyperopen.leaderboard.effects :as leaderboard-effects]
            [hyperopen.runtime.collaborators.account-history :as account-history-collaborators]
            [hyperopen.runtime.collaborators.asset-selector :as asset-selector-collaborators]
            [hyperopen.runtime.collaborators.chart :as chart-collaborators]
            [hyperopen.runtime.collaborators.funding-comparison :as funding-comparison-collaborators]
            [hyperopen.runtime.collaborators.hyperevm :as hyperevm-collaborators]
            [hyperopen.runtime.collaborators.leaderboard :as leaderboard-collaborators]
            [hyperopen.runtime.collaborators.margin-rec :as margin-rec-collaborators]
            [hyperopen.runtime.collaborators.order :as order-collaborators]
            [hyperopen.runtime.collaborators.spectate-mode :as spectate-mode-collaborators]
            [hyperopen.runtime.collaborators.staking :as staking-collaborators]
            [hyperopen.runtime.collaborators.wallet :as wallet-collaborators]
            [hyperopen.runtime.effect-adapters.hyperevm :as hyperevm-effect-adapters]
            [hyperopen.staking.effects :as staking-effects]))

(defn- merge-nested
  [left right]
  (merge-with (fn [left-value right-value]
                (if (and (map? left-value)
                         (map? right-value))
                  (merge-nested left-value right-value)
                  right-value))
              (or left {})
              (or right {})))

(defn runtime-effect-deps
  [effect-overrides]
  (merge-nested
   {:api {:api-fetch-user-funding-history account-history-effects/api-fetch-user-funding-history-effect
          :api-fetch-historical-orders account-history-effects/api-fetch-historical-orders-effect
          :export-funding-history-csv account-history-effects/export-funding-history-csv-effect
          :api-fetch-leaderboard leaderboard-effects/api-fetch-leaderboard!
          :api-fetch-predicted-fundings funding-comparison-effects/api-fetch-predicted-fundings!
          :api-fetch-staking-validator-summaries staking-effects/api-fetch-staking-validator-summaries!
          :api-fetch-staking-delegator-summary staking-effects/api-fetch-staking-delegator-summary!
          :api-fetch-staking-delegations staking-effects/api-fetch-staking-delegations!
          :api-fetch-staking-rewards staking-effects/api-fetch-staking-rewards!
          :api-fetch-staking-history staking-effects/api-fetch-staking-history!
          :api-fetch-staking-spot-state staking-effects/api-fetch-staking-spot-state!
          :api-submit-staking-deposit staking-effects/api-submit-staking-deposit!
          :api-submit-staking-withdraw staking-effects/api-submit-staking-withdraw!
          :api-submit-staking-delegate staking-effects/api-submit-staking-delegate!
          :api-submit-staking-undelegate staking-effects/api-submit-staking-undelegate!
          :api-fetch-hyperunit-fee-estimate funding-effects/api-fetch-hyperunit-fee-estimate!
          :api-fetch-hyperunit-withdrawal-queue funding-effects/api-fetch-hyperunit-withdrawal-queue!
          :api-submit-funding-send funding-effects/api-submit-funding-send!
          :api-submit-funding-transfer funding-effects/api-submit-funding-transfer!
          :api-submit-funding-repay funding-effects/api-submit-funding-repay!
          :api-submit-funding-withdraw funding-effects/api-submit-funding-withdraw!
          :api-submit-funding-deposit funding-effects/api-submit-funding-deposit!
          :fetch-hyperevm-balances hyperevm-effect-adapters/fetch-hyperevm-balances-effect
          :fetch-hyperevm-core-bridge-balance hyperevm-effect-adapters/fetch-hyperevm-core-bridge-balance-effect
          :fetch-hyperevm-core-account-status hyperevm-effect-adapters/fetch-hyperevm-core-account-status-effect
          :fetch-hyperevm-in-flight-receipt hyperevm-effect-adapters/fetch-hyperevm-in-flight-receipt-effect
          :wallet-watch-asset hyperevm-effect-adapters/wallet-watch-asset-effect}}
   effect-overrides))

(defn runtime-action-deps
  [action-overrides]
  (merge-nested
   {:core {}
    :wallet (wallet-collaborators/action-deps)
    :asset-selector (asset-selector-collaborators/action-deps)
    :chart (chart-collaborators/action-deps)
    :account-history (account-history-collaborators/action-deps)
    :spectate-mode (spectate-mode-collaborators/action-deps)
    :leaderboard (leaderboard-collaborators/action-deps)
    :funding-comparison (funding-comparison-collaborators/action-deps)
    :staking (staking-collaborators/action-deps)
    :orders (order-collaborators/action-deps)
    :margin-rec (margin-rec-collaborators/action-deps)
    :hyperevm (hyperevm-collaborators/action-deps)}
   action-overrides))
