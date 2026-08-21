(ns hyperopen.schema.contracts.effect-args
  (:require [cljs.spec.alpha :as s]
            [clojure.string :as str]
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
(s/def ::margin-rec-fetch-fills-args (s/tuple ::common/non-empty-string))

(defn- margin-rec-compute-args?
  [args]
  (and (= 1 (count args))
       (let [{:keys [key inputs]} (first args)]
         (and (common/non-empty-string? key)
              (map? inputs)))))

(s/def ::margin-rec-compute-args margin-rec-compute-args?)
(s/def ::api-submit-vault-transfer-args (s/tuple ::api-submit-request))
(s/def ::api-submit-funding-transfer-args (s/tuple ::api-submit-request))
(s/def ::api-submit-funding-send-args (s/tuple ::api-submit-request))
(s/def ::api-submit-funding-repay-args (s/tuple ::api-submit-request))
(s/def ::api-submit-funding-withdraw-args (s/tuple ::api-submit-request))
(s/def ::api-submit-funding-deposit-args (s/tuple ::api-submit-request))

(defn- exact-keys?
  [payload allowed-keys]
  (= allowed-keys (set (keys payload))))

(defn- api-referral-fetch-args?
  [args]
  (and (= 1 (count args))
       (common/non-empty-string? (first args))))

(defn- api-referral-code-request?
  [payload]
  (and (map? payload)
       (exact-keys? payload #{:owner :code})
       (common/non-empty-string? (:owner payload))
       (common/non-empty-string? (:code payload))))

(defn- api-referral-owner-request?
  [payload]
  (and (map? payload)
       (exact-keys? payload #{:owner})
       (common/non-empty-string? (:owner payload))))

(defn- safe-positive-integer?
  [value]
  (and (integer? value)
       (pos? value)
       (<= value js/Number.MAX_SAFE_INTEGER)))

(defn- positive-integer-string?
  [value]
  (and (string? value)
       (re-matches #"^[1-9]\d*$" value)))

(defn- decimal-amount-string?
  [value]
  (and (string? value)
       (common/non-empty-string? value)
       (re-matches #"^\d+(?:\.\d*)?$" value)))

(defn- normalized-decimals?
  [value]
  (and (integer? value)
       (<= 0 value 38)))

(defn- strip-leading-zeroes
  [text]
  (let [stripped (str/replace (or text "") #"^0+" "")]
    (if (seq stripped) stripped "0")))

(defn- strip-trailing-zeroes
  [text]
  (str/replace (or text "") #"0+$" ""))

(defn- canonical-decimal-text
  [whole frac]
  (let [whole* (strip-leading-zeroes whole)
        frac* (strip-trailing-zeroes frac)]
    (if (seq frac*)
      (str whole* "." frac*)
      whole*)))

(defn- decimal-amount-units
  [amount decimals]
  (when (and (decimal-amount-string? amount)
             (normalized-decimals? decimals))
    (let [[whole frac] (str/split amount #"\." 2)
          frac* (or frac "")]
      (when (<= (count frac*) decimals)
        (let [frac-padded (subs (str frac* (apply str (repeat decimals "0")))
                                0
                                decimals)
              units (strip-leading-zeroes (str (strip-leading-zeroes whole)
                                               frac-padded))]
          (when (not= "0" units)
            {:canonical (canonical-decimal-text whole frac*)
             :units units}))))))

(defn- normalized-amount-consistent?
  [payload {:keys [require-canonical?]}]
  (when-let [{:keys [canonical units]} (decimal-amount-units
                                        (:amount payload)
                                        (:amount-decimals payload))]
    (and (= (:amount-display payload) (:amount payload))
         (= (:amount-units payload) units)
         (or (not require-canonical?)
             (= (:amount payload) canonical)))))

(defn- subaccount-create-request?
  [payload]
  (and (map? payload)
       (exact-keys? payload #{:name})
       (common/non-empty-string? (:name payload))))

(defn- subaccount-rename-request?
  [payload]
  (and (map? payload)
       (exact-keys? payload #{:sub-account-user :name})
       (common/non-empty-string? (:sub-account-user payload))
       (common/non-empty-string? (:name payload))))

(defn- trading-transfer-request?
  [payload]
  (and (map? payload)
       (exact-keys? payload
                    #{:sub-account-user
                      :is-deposit
                      :account-kind
                      :token
                      :amount
                      :amount-display
                      :amount-units
                      :amount-decimals
                      :usd})
       (common/non-empty-string? (:sub-account-user payload))
       (boolean? (:is-deposit payload))
       (= :trading (:account-kind payload))
       (= "USDC" (:token payload))
       (decimal-amount-string? (:amount payload))
       (decimal-amount-string? (:amount-display payload))
       (positive-integer-string? (:amount-units payload))
       (= 6 (:amount-decimals payload))
       (safe-positive-integer? (:usd payload))
       (= (:amount-units payload) (str (:usd payload)))
       (normalized-amount-consistent? payload {:require-canonical? false})))

(defn- spot-transfer-request?
  [payload]
  (and (map? payload)
       (exact-keys? payload
                    #{:sub-account-user
                      :is-deposit
                      :account-kind
                      :token
                      :token-symbol
                      :amount
                      :amount-display
                      :amount-units
                      :amount-decimals})
       (common/non-empty-string? (:sub-account-user payload))
       (boolean? (:is-deposit payload))
       (= :spot (:account-kind payload))
       (common/non-empty-string? (:token payload))
       (common/non-empty-string? (:token-symbol payload))
       (decimal-amount-string? (:amount payload))
       (decimal-amount-string? (:amount-display payload))
       (positive-integer-string? (:amount-units payload))
       (normalized-decimals? (:amount-decimals payload))
       (normalized-amount-consistent? payload {:require-canonical? true})))

(defn- subaccount-transfer-request?
  [payload]
  (or (trading-transfer-request? payload)
      (spot-transfer-request? payload)))

(s/def ::api-create-subaccount-args (s/tuple subaccount-create-request?))
(s/def ::api-rename-subaccount-args (s/tuple subaccount-rename-request?))
(s/def ::api-transfer-subaccount-args (s/tuple subaccount-transfer-request?))

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
(s/def ::api-fetch-referral-args api-referral-fetch-args?)
(s/def ::api-referral-code-args (s/tuple api-referral-code-request?))
(s/def ::api-referral-owner-args (s/tuple api-referral-owner-request?))
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
(s/def ::portfolio-optimizer-view-library-sync-args
  (s/tuple map?))
(s/def ::portfolio-optimizer-assumption-library-sync-args
  (s/tuple map?))
(s/def ::download-portfolio-optimizer-return-views-file-args
  (s/tuple map?))
(s/def ::download-portfolio-optimizer-history-assumptions-file-args
  (s/tuple map?))

(s/def ::apply-ui-theme-args (s/tuple ::common/non-empty-string))

(s/def ::download-spectate-watchlist-file-args (s/tuple map?))
(s/def ::spectate-watchlist-feedback-args (s/tuple keyword? string?))
(s/def ::pnl-share-coin (s/nilable string?))
(s/def ::pnl-share-side #{:long :short})
(s/def ::pnl-share-export-descriptor
  (s/keys :req-un [::pnl-share-coin ::pnl-share-side]))
(s/def ::export-pnl-share-card-png-args
  (s/tuple (s/and map?
                  #(contains? % :coin)
                  #(contains? % :side)
                  #(or (nil? (:coin %)) (string? (:coin %)))
                  #(contains? #{:long :short} (:side %)))))
(s/def ::copy-pnl-share-link-args (s/tuple (s/nilable string?)))

(s/def ::effect-id (s/and keyword?
                          #(= "effects" (namespace %))))

(def effect-args-spec-by-id
  {:effects/save ::common/save-args
   :effects/save-many ::common/save-many-args
   :effects/restore-dialog-focus ::common/no-args
   :effects/local-storage-set ::common/storage-args
   :effects/local-storage-set-json ::common/storage-args
   :effects/apply-ui-theme ::apply-ui-theme-args
   :effects/persist-leaderboard-preferences ::common/no-args
   :effects/queue-asset-icon-status ::common/queue-asset-icon-status-args
   :effects/sync-asset-selector-active-ctx-subscriptions ::common/no-args
   :effects/push-state ::common/path-args
   :effects/replace-state ::common/path-args
   :effects/replace-shareable-route-query ::common/no-args
   :effects/load-route-module ::common/path-args
   :effects/load-surface-module ::common/keyword-args
   :effects/load-account-tab-module ::common/keyword-args
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
   :effects/connect-wallet ::common/optional-string-args
   :effects/disconnect-wallet ::common/no-args
   :effects/enable-agent-trading ::enable-agent-trading-args
   :effects/set-agent-storage-mode ::common/set-agent-storage-mode-args
   :effects/set-agent-local-protection-mode ::common/set-agent-local-protection-mode-args
   :effects/unlock-agent-trading ::common/unlock-agent-trading-args
   :effects/copy-wallet-address ::common/optional-address-args
   :effects/copy-spectate-link ::common/path-and-address-args
   :effects/export-pnl-share-card-png ::export-pnl-share-card-png-args
   :effects/copy-pnl-share-link ::copy-pnl-share-link-args
   :effects/clear-disconnected-account-lifecycle ::common/address-args
   :effects/download-spectate-watchlist-file ::download-spectate-watchlist-file-args
   :effects/pick-spectate-watchlist-file ::common/no-args
   :effects/spectate-watchlist-feedback ::spectate-watchlist-feedback-args
   :effects/reconnect-websocket ::common/no-args
   :effects/refresh-websocket-health ::common/no-args
   :effects/confirm-ws-diagnostics-reveal ::common/no-args
   :effects/copy-websocket-diagnostics ::common/no-args
   :effects/ws-reset-subscriptions ::common/ws-reset-subscriptions-args
   :effects/fetch-asset-selector-markets ::fetch-asset-selector-markets-args
   :effects/sync-active-asset-funding-predictability ::common/coin-args
   :effects/api-load-api-wallets ::common/no-args
   :effects/api-load-subaccounts ::common/no-args
   :effects/api-refresh-subaccounts ::common/no-args
   :effects/api-create-subaccount ::api-create-subaccount-args
   :effects/api-rename-subaccount ::api-rename-subaccount-args
   :effects/api-transfer-subaccount ::api-transfer-subaccount-args
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
   :effects/margin-rec-fetch-fills ::margin-rec-fetch-fills-args
   :effects/margin-rec-compute ::margin-rec-compute-args
   :effects/clear-order-feedback-toast-timeout ::common/optional-string-args
   :effects/api-load-user-data ::common/address-args
   :effects/api-fetch-trader-portfolio-benchmark ::common/address-args
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
   :effects/api-fetch-referral ::api-fetch-referral-args
   :effects/api-set-referrer ::api-referral-code-args
   :effects/api-register-referrer ::api-referral-code-args
   :effects/api-claim-referral-rewards ::api-referral-owner-args
   :effects/api-submit-staking-deposit ::api-submit-order-args
   :effects/api-submit-staking-withdraw ::api-submit-order-args
   :effects/api-submit-staking-delegate ::api-submit-order-args
   :effects/api-submit-staking-undelegate ::api-submit-order-args
   :effects/api-fetch-hyperunit-fee-estimate ::common/no-args
   :effects/api-fetch-hyperunit-withdrawal-queue ::common/no-args
   :effects/api-submit-funding-send ::api-submit-funding-send-args
   :effects/api-submit-funding-transfer ::api-submit-funding-transfer-args
   :effects/api-submit-funding-repay ::api-submit-funding-repay-args
   :effects/api-submit-funding-withdraw ::api-submit-funding-withdraw-args
   :effects/api-submit-funding-deposit ::api-submit-funding-deposit-args
   :effects/run-portfolio-optimizer ::portfolio-optimizer-run-args
   :effects/run-portfolio-optimizer-pipeline ::common/no-args
   :effects/load-portfolio-optimizer-history ::portfolio-optimizer-history-load-args
   :effects/load-portfolio-optimizer-history-discovery ::common/no-args
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
	   :effects/refresh-portfolio-optimizer-open-orders ::common/no-args
	   :effects/refresh-portfolio-optimizer-tracking ::common/no-args
	   :effects/refresh-portfolio-optimizer-rebalance-slippage-snapshots ::common/no-args
	   :effects/enable-portfolio-optimizer-manual-tracking ::common/no-args
	   :effects/save-portfolio-optimizer-constraint-default ::common/no-args
	   :effects/load-portfolio-optimizer-constraint-profiles ::common/no-args
	   :effects/load-portfolio-optimizer-view-library ::common/no-args
	   :effects/sync-portfolio-optimizer-view-library ::portfolio-optimizer-view-library-sync-args
	   :effects/download-portfolio-optimizer-return-views-file ::download-portfolio-optimizer-return-views-file-args
	   :effects/pick-portfolio-optimizer-return-views-file ::common/no-args
	   :effects/download-portfolio-optimizer-history-assumptions-file ::download-portfolio-optimizer-history-assumptions-file-args
	   :effects/pick-portfolio-optimizer-history-assumptions-file ::common/no-args
	   :effects/load-portfolio-optimizer-assumption-library ::common/no-args
	   :effects/sync-portfolio-optimizer-assumption-library ::portfolio-optimizer-assumption-library-sync-args
	   :effects/restore-portfolio-optimizer-draft ::common/no-args
	   :effects/reset-portfolio-optimizer-draft ::common/no-args})
