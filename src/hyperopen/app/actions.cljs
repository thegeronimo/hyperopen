(ns hyperopen.app.actions
  (:require [clojure.string :as str]
            [hyperopen.api-wallets.actions :as api-wallets-actions]
            [hyperopen.portfolio.optimizer.actions :as portfolio-optimizer-actions]
            [hyperopen.pnl-share.actions :as pnl-share-actions]
            [hyperopen.referrals.actions :as referrals-actions]
            [hyperopen.route-modules :as route-modules]
            [hyperopen.runtime.action-adapters :as action-adapters]
            [hyperopen.runtime.collaborators :as runtime-collaborators]
            [hyperopen.schema.runtime-registration.portfolio :as portfolio-registration]
            [hyperopen.schema.runtime-registration.vaults :as vault-registration]
            [hyperopen.subaccounts.actions :as subaccounts-actions]
            [hyperopen.vaults.actions :as vault-actions]))

(def ^:private eager-vault-action-keys
  #{:load-vault-route
    :load-vaults
    :load-vault-detail})

(def ^:private eager-portfolio-optimizer-action-keys
  #{:load-portfolio-optimizer-route
    :restore-or-preseed-portfolio-optimizer-draft})

(defn- optimizer-handler-key?
  [handler-key]
  (str/includes? (name handler-key) "portfolio-optimizer"))

(def ^:private lazy-portfolio-optimizer-action-keys
  (->> portfolio-registration/action-binding-rows
       (map second)
       (filter optimizer-handler-key?)
       (remove eager-portfolio-optimizer-action-keys)
       vec))

(def ^:private lazy-vault-action-keys
  (->> vault-registration/action-binding-rows
       (map second)
       (remove eager-vault-action-keys)
       vec))

(defn- runtime-action-overrides
  []
  (let [lazy-portfolio-optimizer-action-deps
        (:portfolio-optimizer
         (route-modules/lazy-route-action-leaf-deps
          :portfolio
          :portfolio-optimizer
          lazy-portfolio-optimizer-action-keys))
        lazy-vault-action-deps
        (:vaults
         (route-modules/lazy-route-action-leaf-deps
          :vaults
          :vaults
          lazy-vault-action-keys))]
    {:core {:init-websockets action-adapters/init-websockets
            :subscribe-to-asset action-adapters/subscribe-to-asset
            :subscribe-to-webdata2 action-adapters/subscribe-to-webdata2
            :reconnect-websocket-action action-adapters/reconnect-websocket-action
            :navigate action-adapters/navigate}
     :wallet {:enable-agent-trading-action action-adapters/enable-agent-trading-action
              :unlock-agent-trading-action action-adapters/unlock-agent-trading-action
              :set-agent-storage-mode-action action-adapters/set-agent-storage-mode-action
              :set-agent-local-protection-mode-action
              action-adapters/set-agent-local-protection-mode-action}
     :diagnostics {:toggle-ws-diagnostics action-adapters/toggle-ws-diagnostics
                   :close-ws-diagnostics action-adapters/close-ws-diagnostics
                   :handle-ws-diagnostics-keydown action-adapters/handle-ws-diagnostics-keydown
                   :toggle-ws-diagnostics-sensitive action-adapters/toggle-ws-diagnostics-sensitive
                   :ws-diagnostics-reconnect-now action-adapters/ws-diagnostics-reconnect-now
                   :ws-diagnostics-copy action-adapters/ws-diagnostics-copy
                   :set-show-surface-freshness-cues action-adapters/set-show-surface-freshness-cues
                   :toggle-show-surface-freshness-cues action-adapters/toggle-show-surface-freshness-cues
                   :ws-diagnostics-reset-market-subscriptions action-adapters/ws-diagnostics-reset-market-subscriptions
                   :ws-diagnostics-reset-orders-subscriptions action-adapters/ws-diagnostics-reset-orders-subscriptions
                   :ws-diagnostics-reset-all-subscriptions action-adapters/ws-diagnostics-reset-all-subscriptions}
     :asset-selector {:refresh-asset-markets action-adapters/refresh-asset-markets}
     :leaderboard {:load-leaderboard-route action-adapters/load-leaderboard-route-action}
     :vaults (merge {:load-vault-route vault-actions/load-vault-route
                     :load-vaults vault-actions/load-vaults
                     :load-vault-detail vault-actions/load-vault-detail}
                    lazy-vault-action-deps)
     :funding-comparison {:load-funding-comparison-route action-adapters/load-funding-comparison-route-action}
     :staking {:load-staking-route action-adapters/load-staking-route-action}
     :pnl-share {:open-pnl-share-card pnl-share-actions/open-pnl-share-card
                 :close-pnl-share-card pnl-share-actions/close-pnl-share-card
                 :set-pnl-share-option pnl-share-actions/set-pnl-share-option
                 :save-pnl-share-card-image pnl-share-actions/save-pnl-share-card-image
                 :copy-pnl-share-link pnl-share-actions/copy-pnl-share-link
                 :handle-pnl-share-card-keydown pnl-share-actions/handle-pnl-share-card-keydown}
     :referrals {:load-referrals-route action-adapters/load-referrals-route-action
                 :set-referrals-active-tab referrals-actions/set-active-tab
                 :set-referrals-form-field referrals-actions/set-form-field
                 :set-referrals-sort referrals-actions/set-referrals-sort
                 :open-referrals-modal referrals-actions/open-referrals-modal
                 :close-referrals-modal referrals-actions/close-referrals-modal
                 :submit-set-referrer referrals-actions/submit-set-referrer
                 :submit-register-referrer referrals-actions/submit-register-referrer
                 :submit-claim-referral-rewards referrals-actions/submit-claim-rewards}
     :api-wallets {:load-api-wallet-route action-adapters/load-api-wallet-route-action
                   :set-api-wallet-form-field api-wallets-actions/set-api-wallet-form-field
                   :set-api-wallet-sort api-wallets-actions/set-api-wallet-sort
                   :generate-api-wallet api-wallets-actions/generate-api-wallet
                   :open-api-wallet-authorize-modal api-wallets-actions/open-api-wallet-authorize-modal
                   :open-api-wallet-remove-modal api-wallets-actions/open-api-wallet-remove-modal
                   :close-api-wallet-modal api-wallets-actions/close-api-wallet-modal
                   :confirm-api-wallet-modal api-wallets-actions/confirm-api-wallet-modal}
     :subaccounts {:load-subaccounts-route action-adapters/load-subaccounts-route-action
                   :refresh-subaccounts subaccounts-actions/refresh-subaccounts
                   :select-subaccount subaccounts-actions/select-subaccount
                   :select-master-account subaccounts-actions/select-master-account
                   :set-subaccount-form-field subaccounts-actions/set-subaccount-form-field
                   :toggle-transfer-direction subaccounts-actions/toggle-transfer-direction
                   :open-subaccount-create-popover subaccounts-actions/open-create-popover
                   :close-subaccount-create-popover subaccounts-actions/close-create-popover
                   :copy-subaccount-address subaccounts-actions/copy-subaccount-address
                   :submit-create-subaccount subaccounts-actions/submit-create-subaccount
                   :start-rename-subaccount subaccounts-actions/start-rename-subaccount
                   :cancel-rename-subaccount subaccounts-actions/cancel-rename-subaccount
                   :submit-rename-subaccount subaccounts-actions/submit-rename-subaccount
                   :start-transfer-subaccount subaccounts-actions/start-transfer-subaccount
                   :cancel-transfer-subaccount subaccounts-actions/cancel-transfer-subaccount
                   :submit-transfer-subaccount subaccounts-actions/submit-transfer-subaccount}
     :spectate-mode {:open-spectate-mode-modal action-adapters/open-spectate-mode-modal
                     :close-spectate-mode-modal action-adapters/close-spectate-mode-modal
                     :set-spectate-mode-search action-adapters/set-spectate-mode-search
                     :set-spectate-mode-label action-adapters/set-spectate-mode-label
                     :start-spectate-mode action-adapters/start-spectate-mode
                     :stop-spectate-mode action-adapters/stop-spectate-mode
                     :add-spectate-mode-watchlist-address action-adapters/add-spectate-mode-watchlist-address
                     :remove-spectate-mode-watchlist-address action-adapters/remove-spectate-mode-watchlist-address
                     :edit-spectate-mode-watchlist-address action-adapters/edit-spectate-mode-watchlist-address
                     :clear-spectate-mode-watchlist-edit action-adapters/clear-spectate-mode-watchlist-edit
                     :copy-spectate-mode-watchlist-address action-adapters/copy-spectate-mode-watchlist-address
                     :copy-spectate-mode-watchlist-link action-adapters/copy-spectate-mode-watchlist-link
                     :start-spectate-mode-watchlist-address action-adapters/start-spectate-mode-watchlist-address
                     :export-spectate-mode-watchlist action-adapters/export-spectate-mode-watchlist
                     :import-spectate-mode-watchlist action-adapters/import-spectate-mode-watchlist
                     :apply-imported-spectate-watchlist action-adapters/apply-imported-spectate-watchlist}
     :orders {:load-user-data action-adapters/load-user-data
              :set-funding-modal action-adapters/set-funding-modal}
     :portfolio-optimizer
     (merge {:load-portfolio-optimizer-route
             portfolio-optimizer-actions/load-portfolio-optimizer-route
             :restore-or-preseed-portfolio-optimizer-draft
             portfolio-optimizer-actions/restore-or-preseed-portfolio-optimizer-draft}
            lazy-portfolio-optimizer-action-deps)}))

(defn runtime-action-deps
  []
  (runtime-collaborators/runtime-action-deps
   (runtime-action-overrides)))
