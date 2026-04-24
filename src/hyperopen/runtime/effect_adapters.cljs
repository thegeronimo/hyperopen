(ns hyperopen.runtime.effect-adapters
  (:require [nexus.registry :as nxr]
            [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.api-wallets.effects :as api-wallets-effects]
            [hyperopen.account.history.effects :as account-history-effects]
            [hyperopen.runtime.effect-adapters.asset-selector :as asset-adapters]
            [hyperopen.runtime.effect-adapters.common :as common]
            [hyperopen.runtime.effect-adapters.funding :as funding-adapters]
            [hyperopen.runtime.effect-adapters.leaderboard :as leaderboard-adapters]
            [hyperopen.runtime.effect-adapters.order :as order-adapters]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer :as portfolio-optimizer-adapters]
            [hyperopen.runtime.effect-adapters.route-query :as route-query-adapters]
            [hyperopen.runtime.effect-adapters.staking :as staking-adapters]
            [hyperopen.runtime.effect-adapters.vaults :as vault-adapters]
            [hyperopen.runtime.effect-adapters.wallet :as wallet-adapters]
            [hyperopen.runtime.effect-adapters.websocket :as ws-adapters]
            [hyperopen.startup.runtime :as startup-runtime]
            [hyperopen.route-modules :as route-modules]
            [hyperopen.surface-modules :as surface-modules]
            [hyperopen.trade-modules :as trade-modules]
            [hyperopen.trading-crypto-modules :as trading-crypto-modules]
            [hyperopen.trading-indicators-modules :as trading-indicators-modules]
            [hyperopen.runtime.api-effects :as api-effects]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.platform :as platform]
            [hyperopen.wallet.agent-runtime :as agent-runtime]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.websocket.user :as user-ws]
            [hyperopen.websocket.client :as ws-client]))

(def append-diagnostics-event! ws-adapters/append-diagnostics-event!)

(def sync-websocket-health-with-runtime! ws-adapters/sync-websocket-health-with-runtime!)

(def sync-websocket-health! ws-adapters/sync-websocket-health!)

(def save common/save)

(def save-many common/save-many)

(def local-storage-set common/local-storage-set)

(def local-storage-set-json common/local-storage-set-json)

(def schedule-animation-frame! common/schedule-animation-frame!)

(defn flush-queued-asset-icon-statuses!
  ([store]
   (flush-queued-asset-icon-statuses! runtime-state/runtime store))
  ([runtime store]
   (asset-adapters/flush-queued-asset-icon-statuses!
    {:store store
     :runtime runtime
     :save-many! (fn [runtime-store path-values]
                   (save-many nil runtime-store path-values))})))

(defn queue-asset-icon-status
  ([_ store payload]
   (queue-asset-icon-status runtime-state/runtime nil store payload))
  ([runtime _ store payload]
   (asset-adapters/queue-asset-icon-status!
    {:store store
     :runtime runtime
     :payload payload
     :schedule-animation-frame! schedule-animation-frame!
     :flush-queued-asset-icon-statuses! (fn [runtime-store]
                                          (flush-queued-asset-icon-statuses! runtime runtime-store))})))

(defn make-queue-asset-icon-status
  [runtime]
  (fn [ctx store payload]
    (queue-asset-icon-status runtime ctx store payload)))

(def push-state common/push-state)

(def replace-state common/replace-state)

(def replace-shareable-route-query route-query-adapters/replace-shareable-route-query)

(defn load-route-module-effect
  [_ store path]
  (route-modules/load-route-module! store path))

(defn load-surface-module-effect
  [_ store surface-id]
  (surface-modules/load-surface-module! store surface-id))

(defn load-trade-chart-module-effect
  [_ store]
  (trade-modules/load-trade-chart-module! store))

(defn load-trading-indicators-module-effect
  [_ store]
  (trading-indicators-modules/load-trading-indicators-module! store))

(def make-fetch-candle-snapshot ws-adapters/make-fetch-candle-snapshot)

(def fetch-candle-snapshot ws-adapters/fetch-candle-snapshot)

(defn sync-active-candle-subscription
  [_ store & {:as opts}]
  (ws-adapters/sync-active-candle-subscription store opts))

(def make-init-websocket ws-adapters/make-init-websocket)

(def init-websocket ws-adapters/init-websocket)

(def persist-asset-selector-markets-cache! asset-adapters/persist-asset-selector-markets-cache!)

(defn restore-asset-selector-markets-cache! [store]
  (asset-adapters/restore-asset-selector-markets-cache! {:store store}))

(defn restore-leaderboard-preferences! [store]
  (leaderboard-adapters/restore-leaderboard-preferences! store))

(def persist-active-market-display! asset-adapters/persist-active-market-display!)

(def load-active-market-display asset-adapters/load-active-market-display)

(def persist-leaderboard-preferences-effect
  leaderboard-adapters/persist-leaderboard-preferences-effect)

(defn subscribe-active-asset [_ store coin]
  (ws-adapters/subscribe-active-asset
   store
   coin
   {:persist-active-market-display-fn persist-active-market-display!
    :fetch-candle-snapshot-fn (fn [selected-timeframe]
                                (fetch-candle-snapshot nil store :interval selected-timeframe))}))

(defn unsubscribe-active-asset [_ store coin]
  (ws-adapters/unsubscribe-active-asset store coin))

(defn subscribe-orderbook [_ store coin]
  (ws-adapters/subscribe-orderbook store coin))

(defn subscribe-trades [_ store coin]
  (ws-adapters/subscribe-trades coin))

(defn unsubscribe-orderbook [_ store coin]
  (ws-adapters/unsubscribe-orderbook store coin))

(defn unsubscribe-trades [_ store coin]
  (ws-adapters/unsubscribe-trades coin))

(defn subscribe-webdata2 [_ store address]
  (ws-adapters/subscribe-webdata2 address))

(defn unsubscribe-webdata2 [_ store address]
  (ws-adapters/unsubscribe-webdata2 address))

(defn clear-disconnected-account-lifecycle
  ([_ store address]
   (clear-disconnected-account-lifecycle runtime-state/runtime nil store address))
  ([runtime _ store address]
   (startup-runtime/clear-disconnected-account-state!
    {:runtime runtime
     :store store
     :address address
     :unsubscribe-user! user-ws/unsubscribe-user!
     :unsubscribe-webdata2! ws-adapters/unsubscribe-webdata2})))

(def sync-asset-selector-active-ctx-subscriptions
  asset-adapters/sync-asset-selector-active-ctx-subscriptions)

(def connect-wallet wallet-adapters/connect-wallet)

(def ^:private clear-order-feedback-toast! order-adapters/clear-order-feedback-toast!)

(def ^:private clear-order-feedback-toast-timeout! order-adapters/clear-order-feedback-toast-timeout!)

(def ^:private show-order-feedback-toast! order-adapters/show-order-feedback-toast!)

(def make-clear-order-feedback-toast-timeout order-adapters/make-clear-order-feedback-toast-timeout)

(defn disconnect-wallet
  ([_ store]
   (disconnect-wallet runtime-state/runtime nil store))
  ([runtime _ store]
   (wallet-adapters/disconnect-wallet
    runtime
    store
    {:clear-order-feedback-toast-timeout! clear-order-feedback-toast-timeout!
     :clear-order-feedback-toast! clear-order-feedback-toast!})))

(defn make-disconnect-wallet
  [runtime]
  (fn [ctx store]
    (disconnect-wallet runtime ctx store)))

(def set-agent-storage-mode wallet-adapters/set-agent-storage-mode)

(def set-agent-local-protection-mode wallet-adapters/set-agent-local-protection-mode)

(def unlock-agent-trading wallet-adapters/unlock-agent-trading)

(def copy-wallet-address wallet-adapters/copy-wallet-address)

(def make-copy-wallet-address wallet-adapters/make-copy-wallet-address)

(def copy-spectate-link wallet-adapters/copy-spectate-link)

(def make-copy-spectate-link wallet-adapters/make-copy-spectate-link)

(def make-reconnect-websocket ws-adapters/make-reconnect-websocket)

(def reconnect-websocket ws-adapters/reconnect-websocket)

(def refresh-websocket-health ws-adapters/refresh-websocket-health)

(def make-refresh-websocket-health ws-adapters/make-refresh-websocket-health)

(def ws-reset-subscriptions ws-adapters/ws-reset-subscriptions)

(def confirm-ws-diagnostics-reveal ws-adapters/confirm-ws-diagnostics-reveal)

(def copy-websocket-diagnostics ws-adapters/copy-websocket-diagnostics)

(defn restore-active-asset! [store]
  (ws-adapters/restore-active-asset!
   {:store store
    :dispatch! nxr/dispatch
    :connected?-fn ws-client/connected?
    :load-active-market-display-fn load-active-market-display}))

(def api-submit-order order-adapters/api-submit-order)

(def confirm-api-submit-order order-adapters/confirm-api-submit-order)

(def api-cancel-order order-adapters/api-cancel-order)

(def api-submit-position-tpsl order-adapters/api-submit-position-tpsl)

(def api-submit-position-margin order-adapters/api-submit-position-margin)

(def make-api-submit-order order-adapters/make-api-submit-order)

(def make-api-cancel-order order-adapters/make-api-cancel-order)

(def make-api-submit-position-tpsl order-adapters/make-api-submit-position-tpsl)

(def make-api-submit-position-margin order-adapters/make-api-submit-position-margin)

(def sync-active-asset-funding-predictability
  funding-adapters/sync-active-asset-funding-predictability)

(def restore-dialog-focus-effect
  funding-adapters/restore-dialog-focus-effect)

(defn fetch-asset-selector-markets-effect
  [_ store & [opts]]
  (api-effects/fetch-asset-selector-markets!
   {:store store
    :opts opts
    :request-asset-selector-markets-fn api/request-asset-selector-markets!
    :begin-asset-selector-load api-projections/begin-asset-selector-load
    :apply-asset-selector-success api-projections/apply-asset-selector-success
    :apply-asset-selector-error api-projections/apply-asset-selector-error
    :after-asset-selector-success! (fn [runtime-store _phase _market-state]
                                     (sync-asset-selector-active-ctx-subscriptions nil runtime-store))}))

(defn api-load-user-data-effect
  [_ store address]
  (api-effects/load-user-data!
   {:store store
    :address address
    :request-frontend-open-orders! api/request-frontend-open-orders!
    :request-user-fills! api/request-user-fills!
    :apply-open-orders-success api-projections/apply-open-orders-success
    :apply-open-orders-error api-projections/apply-open-orders-error
    :apply-user-fills-success api-projections/apply-user-fills-success
    :apply-user-fills-error api-projections/apply-user-fills-error
    :fetch-and-merge-funding-history! account-history-effects/fetch-and-merge-funding-history!}))

(def api-fetch-predicted-fundings-effect
  funding-adapters/api-fetch-predicted-fundings-effect)

(def api-fetch-leaderboard-effect
  leaderboard-adapters/api-fetch-leaderboard-effect)

(def api-fetch-vault-index-effect
  vault-adapters/api-fetch-vault-index-effect)

(def api-fetch-vault-index-with-cache-effect
  vault-adapters/api-fetch-vault-index-with-cache-effect)

(def api-fetch-vault-summaries-effect
  vault-adapters/api-fetch-vault-summaries-effect)

(def api-fetch-user-vault-equities-effect
  vault-adapters/api-fetch-user-vault-equities-effect)

(def api-fetch-vault-details-effect
  vault-adapters/api-fetch-vault-details-effect)

(def api-fetch-vault-benchmark-details-effect
  vault-adapters/api-fetch-vault-benchmark-details-effect)

(def api-fetch-vault-webdata2-effect
  vault-adapters/api-fetch-vault-webdata2-effect)

(def api-fetch-vault-fills-effect
  vault-adapters/api-fetch-vault-fills-effect)

(def api-fetch-vault-funding-history-effect
  vault-adapters/api-fetch-vault-funding-history-effect)

(def api-fetch-vault-order-history-effect
  vault-adapters/api-fetch-vault-order-history-effect)

(def api-fetch-vault-ledger-updates-effect
  vault-adapters/api-fetch-vault-ledger-updates-effect)

(def api-fetch-staking-validator-summaries-effect
  staking-adapters/api-fetch-staking-validator-summaries-effect)

(def api-fetch-staking-delegator-summary-effect
  staking-adapters/api-fetch-staking-delegator-summary-effect)

(def api-fetch-staking-delegations-effect
  staking-adapters/api-fetch-staking-delegations-effect)

(def api-fetch-staking-rewards-effect
  staking-adapters/api-fetch-staking-rewards-effect)

(def api-fetch-staking-history-effect
  staking-adapters/api-fetch-staking-history-effect)

(def api-fetch-staking-spot-state-effect
  staking-adapters/api-fetch-staking-spot-state-effect)

(defn- api-wallets-load-deps
  [store]
  {:store store
   :request-extra-agents! api/request-extra-agents!
   :request-user-webdata2! api/request-user-webdata2!
   :apply-api-wallets-extra-agents-success api-projections/apply-api-wallets-extra-agents-success
   :apply-api-wallets-extra-agents-error api-projections/apply-api-wallets-extra-agents-error
   :apply-api-wallets-default-agent-success api-projections/apply-api-wallets-default-agent-success
   :apply-api-wallets-default-agent-error api-projections/apply-api-wallets-default-agent-error
   :clear-api-wallets-errors api-projections/clear-api-wallets-errors
   :reset-api-wallets api-projections/reset-api-wallets
   :now-ms-fn platform/now-ms})

(defn- api-wallets-approval-deps
  [store]
  (merge (api-wallets-load-deps store)
         {:store store
          :approve-agent-request! agent-runtime/approve-agent-request!
          :clear-agent-session-by-mode! agent-session/clear-agent-session-by-mode!
          :default-agent-state agent-session/default-agent-state
          :now-ms-fn platform/now-ms
          :normalize-storage-mode agent-session/normalize-storage-mode
          :default-signature-chain-id-for-environment agent-session/default-signature-chain-id-for-environment
          :build-approve-agent-action agent-session/build-approve-agent-action
          :format-agent-name-with-valid-until agent-session/format-agent-name-with-valid-until
          :approve-agent! trading-api/approve-agent!
          :persist-agent-session-by-mode! agent-session/persist-agent-session-by-mode!
          :runtime-error-message agent-runtime/runtime-error-message
          :exchange-response-error agent-runtime/exchange-response-error
          :load-api-wallets! (fn [opts]
                               (api-wallets-effects/load-api-wallets!
                                (merge (api-wallets-load-deps store)
                                       opts)))}))

(defn api-load-api-wallets-effect
  [_ store]
  (api-wallets-effects/api-load-api-wallets!
   (api-wallets-load-deps store)))

(defn generate-api-wallet-effect
  [_ store]
  (letfn [(generate-with-crypto! [crypto]
            (api-wallets-effects/generate-api-wallet!
             {:store store
              :create-agent-credentials! (:create-agent-credentials! crypto)
              :runtime-error-message agent-runtime/runtime-error-message}))]
    (if-let [crypto (trading-crypto-modules/resolved-trading-crypto)]
      (generate-with-crypto! crypto)
      (-> (trading-crypto-modules/load-trading-crypto-module!)
          (.then generate-with-crypto!)
          (.catch (fn [err]
                    (api-wallets-effects/generate-api-wallet!
                     {:store store
                      :create-agent-credentials! (fn []
                                                   (throw err))
                      :runtime-error-message agent-runtime/runtime-error-message})))))))

(defn api-authorize-api-wallet-effect
  [_ store]
  (api-wallets-effects/api-authorize-api-wallet!
   (api-wallets-approval-deps store)))

(defn api-remove-api-wallet-effect
  [_ store]
  (api-wallets-effects/api-remove-api-wallet!
   (api-wallets-approval-deps store)))

(defn api-submit-vault-transfer-effect
  [_ store request]
  (apply vault-adapters/api-submit-vault-transfer-effect
         [nil
          store
          request
          {:show-toast! show-order-feedback-toast!}]))

(defn api-submit-staking-deposit-effect
  [_ store request]
  (apply staking-adapters/api-submit-staking-deposit-effect
         [nil
          store
          request
          {:show-toast! show-order-feedback-toast!}]))

(defn api-submit-staking-withdraw-effect
  [_ store request]
  (apply staking-adapters/api-submit-staking-withdraw-effect
         [nil
          store
          request
          {:show-toast! show-order-feedback-toast!}]))

(defn api-submit-staking-delegate-effect
  [_ store request]
  (apply staking-adapters/api-submit-staking-delegate-effect
         [nil
          store
          request
          {:show-toast! show-order-feedback-toast!}]))

(defn api-submit-staking-undelegate-effect
  [_ store request]
  (apply staking-adapters/api-submit-staking-undelegate-effect
         [nil
          store
          request
          {:show-toast! show-order-feedback-toast!}]))

(def api-fetch-hyperunit-fee-estimate-effect
  funding-adapters/api-fetch-hyperunit-fee-estimate-effect)

(def api-fetch-hyperunit-withdrawal-queue-effect
  funding-adapters/api-fetch-hyperunit-withdrawal-queue-effect)

(defn api-submit-funding-transfer-effect
  [_ store request]
  (apply funding-adapters/api-submit-funding-transfer-effect
         [nil
          store
          request
          {:show-toast! show-order-feedback-toast!}]))

(defn api-submit-funding-send-effect
  [_ store request]
  (apply funding-adapters/api-submit-funding-send-effect
         [nil
          store
          request
          {:show-toast! show-order-feedback-toast!}]))

(defn api-submit-funding-withdraw-effect
  [_ store request]
  (apply funding-adapters/api-submit-funding-withdraw-effect
         [nil
          store
          request
          {:show-toast! show-order-feedback-toast!}]))

(defn api-submit-funding-deposit-effect
  [_ store request]
  (apply funding-adapters/api-submit-funding-deposit-effect
         [nil
          store
          request
          {:show-toast! show-order-feedback-toast!}]))

(def run-portfolio-optimizer-effect
  portfolio-optimizer-adapters/run-portfolio-optimizer-effect)
