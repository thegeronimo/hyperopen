(ns hyperopen.schema.contracts.effect-args
  (:require [cljs.spec.alpha :as s]
            [hyperopen.schema.contracts.common :as common]))

(s/def ::order-submit-confirmation-variant
  #{:open-order :close-position})
(s/def ::enable-agent-trading-args (s/tuple map?))
(s/def ::api-submit-request (s/keys :req-un [::common/action]))
(s/def ::api-submit-order-args (s/tuple ::api-submit-request))

(defn- confirm-api-submit-order-args?
  [args]
  (and (= 1 (count args))
       (let [{:keys [variant message request path-values]} (first args)]
         (and (common/non-empty-string? message)
              (or (nil? variant)
                  (s/valid? ::order-submit-confirmation-variant variant))
              (s/valid? ::api-submit-request request)
              (s/valid? ::common/path-values path-values)))))

(s/def ::confirm-api-submit-order-args confirm-api-submit-order-args?)
(s/def ::api-cancel-order-args (s/tuple ::api-submit-request))
(s/def ::api-submit-position-tpsl-args (s/tuple ::api-submit-request))
(s/def ::api-submit-position-margin-args (s/tuple ::api-submit-request))
(s/def ::api-submit-vault-transfer-args (s/tuple ::api-submit-request))
(s/def ::api-submit-funding-transfer-args (s/tuple ::api-submit-request))
(s/def ::api-submit-funding-send-args (s/tuple ::api-submit-request))
(s/def ::api-submit-funding-withdraw-args (s/tuple ::api-submit-request))
(s/def ::api-submit-funding-deposit-args (s/tuple ::api-submit-request))

(defn- fetch-asset-selector-markets-args?
  [args]
  (or (empty? args)
      (and (= 1 (count args))
           (map? (first args)))))

(defn- api-fetch-leaderboard-args?
  [args]
  (or (empty? args)
      (and (= 1 (count args))
           (map? (first args)))))

(s/def ::fetch-asset-selector-markets-args fetch-asset-selector-markets-args?)
(s/def ::api-fetch-leaderboard-args api-fetch-leaderboard-args?)
(s/def ::request-id ::common/non-negative-int)
(s/def ::request-id-args (s/tuple ::request-id))
(s/def ::export-funding-history-csv-args (s/tuple ::common/map-vector))
(s/def ::portfolio-optimizer-run-args
  (s/or :base (s/tuple map? map?)
        :with-opts (s/tuple map? map? map?)))
(s/def ::portfolio-optimizer-history-load-args
  (s/or :base empty?
        :with-opts (s/tuple map?)))
(s/def ::portfolio-optimizer-scenario-index-load-args
  (s/or :base empty?
        :with-opts (s/tuple map?)))
(s/def ::portfolio-optimizer-scenario-load-args
  (s/or :base (s/tuple ::common/non-empty-string)
        :with-opts (s/tuple ::common/non-empty-string map?)))
(s/def ::portfolio-optimizer-scenario-mutation-args
  (s/or :base (s/tuple ::common/non-empty-string)
        :with-opts (s/tuple ::common/non-empty-string map?)))
(s/def ::portfolio-optimizer-scenario-save-args
  (s/or :base empty?
        :with-opts (s/tuple map?)))
(s/def ::portfolio-optimizer-execution-plan-args
  (s/tuple map?))

(s/def ::effect-id (s/and keyword?
                          #(= "effects" (namespace %))))

(def effect-args-spec-by-id
  {:effects/save ::common/save-args
   :effects/save-many ::common/save-many-args
   :effects/restore-dialog-focus ::common/no-args
   :effects/local-storage-set ::common/storage-args
   :effects/local-storage-set-json ::common/storage-args
   :effects/persist-leaderboard-preferences ::common/no-args
   :effects/queue-asset-icon-status ::common/queue-asset-icon-status-args
   :effects/sync-asset-selector-active-ctx-subscriptions ::common/no-args
   :effects/push-state ::common/path-args
   :effects/replace-state ::common/path-args
   :effects/replace-shareable-route-query ::common/no-args
   :effects/load-route-module ::common/path-args
   :effects/load-surface-module ::common/keyword-args
   :effects/load-trade-chart-module ::common/no-args
   :effects/load-trading-indicators-module ::common/no-args
   :effects/init-websocket ::common/no-args
   :effects/subscribe-active-asset ::common/coin-args
   :effects/subscribe-orderbook ::common/coin-args
   :effects/subscribe-trades ::common/coin-args
   :effects/subscribe-webdata2 ::common/address-args
   :effects/sync-active-candle-subscription ::common/fetch-candle-snapshot-args
   :effects/fetch-candle-snapshot ::common/fetch-candle-snapshot-args
   :effects/unsubscribe-active-asset ::common/coin-args
   :effects/unsubscribe-orderbook ::common/coin-args
   :effects/unsubscribe-trades ::common/coin-args
   :effects/unsubscribe-webdata2 ::common/address-args
   :effects/connect-wallet ::common/no-args
   :effects/disconnect-wallet ::common/no-args
   :effects/enable-agent-trading ::enable-agent-trading-args
   :effects/set-agent-storage-mode ::common/set-agent-storage-mode-args
   :effects/set-agent-local-protection-mode ::common/set-agent-local-protection-mode-args
   :effects/unlock-agent-trading ::common/unlock-agent-trading-args
   :effects/copy-wallet-address ::common/optional-address-args
   :effects/copy-spectate-link ::common/path-and-address-args
   :effects/clear-disconnected-account-lifecycle ::common/address-args
   :effects/reconnect-websocket ::common/no-args
   :effects/refresh-websocket-health ::common/no-args
   :effects/confirm-ws-diagnostics-reveal ::common/no-args
   :effects/copy-websocket-diagnostics ::common/no-args
   :effects/ws-reset-subscriptions ::common/ws-reset-subscriptions-args
   :effects/fetch-asset-selector-markets ::fetch-asset-selector-markets-args
   :effects/sync-active-asset-funding-predictability ::common/coin-args
   :effects/api-load-api-wallets ::common/no-args
   :effects/generate-api-wallet ::common/no-args
   :effects/api-authorize-api-wallet ::common/no-args
   :effects/api-remove-api-wallet ::common/no-args
   :effects/api-fetch-user-funding-history ::request-id-args
   :effects/api-fetch-historical-orders ::request-id-args
   :effects/export-funding-history-csv ::export-funding-history-csv-args
   :effects/api-fetch-leaderboard ::api-fetch-leaderboard-args
   :effects/api-fetch-predicted-fundings ::common/no-args
   :effects/api-submit-order ::api-submit-order-args
   :effects/confirm-api-submit-order ::confirm-api-submit-order-args
   :effects/api-cancel-order ::api-cancel-order-args
   :effects/api-submit-position-tpsl ::api-submit-position-tpsl-args
   :effects/api-submit-position-margin ::api-submit-position-margin-args
   :effects/clear-order-feedback-toast-timeout ::common/optional-string-args
   :effects/api-load-user-data ::common/address-args
   :effects/api-fetch-vault-index ::common/no-args
   :effects/api-fetch-vault-index-with-cache ::common/no-args
   :effects/api-fetch-vault-summaries ::common/no-args
   :effects/api-fetch-user-vault-equities ::common/optional-address-args
   :effects/api-fetch-vault-details ::common/address-and-optional-address-args
   :effects/api-fetch-vault-benchmark-details ::common/address-args
   :effects/api-fetch-vault-webdata2 ::common/address-args
   :effects/api-fetch-vault-fills ::common/address-args
   :effects/api-fetch-vault-funding-history ::common/address-args
   :effects/api-fetch-vault-order-history ::common/address-args
   :effects/api-fetch-vault-ledger-updates ::common/address-args
   :effects/api-submit-vault-transfer ::api-submit-vault-transfer-args
   :effects/api-fetch-staking-validator-summaries ::common/no-args
   :effects/api-fetch-staking-delegator-summary ::common/address-args
   :effects/api-fetch-staking-delegations ::common/address-args
   :effects/api-fetch-staking-rewards ::common/address-args
   :effects/api-fetch-staking-history ::common/address-args
   :effects/api-fetch-staking-spot-state ::common/address-args
   :effects/api-submit-staking-deposit ::api-submit-order-args
   :effects/api-submit-staking-withdraw ::api-submit-order-args
   :effects/api-submit-staking-delegate ::api-submit-order-args
   :effects/api-submit-staking-undelegate ::api-submit-order-args
   :effects/api-fetch-hyperunit-fee-estimate ::common/no-args
   :effects/api-fetch-hyperunit-withdrawal-queue ::common/no-args
   :effects/api-submit-funding-send ::api-submit-funding-send-args
   :effects/api-submit-funding-transfer ::api-submit-funding-transfer-args
   :effects/api-submit-funding-withdraw ::api-submit-funding-withdraw-args
   :effects/api-submit-funding-deposit ::api-submit-funding-deposit-args
   :effects/run-portfolio-optimizer ::portfolio-optimizer-run-args
   :effects/load-portfolio-optimizer-history ::portfolio-optimizer-history-load-args
   :effects/load-portfolio-optimizer-scenario-index
   ::portfolio-optimizer-scenario-index-load-args
   :effects/load-portfolio-optimizer-scenario
   ::portfolio-optimizer-scenario-load-args
   :effects/archive-portfolio-optimizer-scenario
   ::portfolio-optimizer-scenario-mutation-args
   :effects/duplicate-portfolio-optimizer-scenario
   ::portfolio-optimizer-scenario-mutation-args
	   :effects/save-portfolio-optimizer-scenario ::portfolio-optimizer-scenario-save-args
	   :effects/execute-portfolio-optimizer-plan ::portfolio-optimizer-execution-plan-args
	   :effects/refresh-portfolio-optimizer-tracking ::common/no-args})
