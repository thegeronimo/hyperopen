(ns hyperopen.app.actions
  (:require [hyperopen.api-wallets.actions :as api-wallets-actions]
            [hyperopen.runtime.action-adapters :as action-adapters]
            [hyperopen.runtime.collaborators :as runtime-collaborators]))

(defn- runtime-action-overrides
  []
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
   :vaults {:load-vault-route action-adapters/load-vault-route-action}
   :funding-comparison {:load-funding-comparison-route action-adapters/load-funding-comparison-route-action}
   :staking {:load-staking-route action-adapters/load-staking-route-action}
   :portfolio-optimizer {:run-portfolio-optimizer action-adapters/run-portfolio-optimizer-action
                         :set-portfolio-optimizer-objective-kind action-adapters/set-portfolio-optimizer-objective-kind-action
                         :set-portfolio-optimizer-return-model-kind action-adapters/set-portfolio-optimizer-return-model-kind-action
                         :set-portfolio-optimizer-risk-model-kind action-adapters/set-portfolio-optimizer-risk-model-kind-action
                         :set-portfolio-optimizer-constraint action-adapters/set-portfolio-optimizer-constraint-action
                         :set-portfolio-optimizer-objective-parameter
                         action-adapters/set-portfolio-optimizer-objective-parameter-action
                         :set-portfolio-optimizer-execution-assumption
                         action-adapters/set-portfolio-optimizer-execution-assumption-action
                         :set-portfolio-optimizer-instrument-filter
                         action-adapters/set-portfolio-optimizer-instrument-filter-action
                         :set-portfolio-optimizer-asset-override
                         action-adapters/set-portfolio-optimizer-asset-override-action
                         :set-portfolio-optimizer-universe-from-current
                         action-adapters/set-portfolio-optimizer-universe-from-current-action
                         :load-portfolio-optimizer-history-from-draft
                         action-adapters/load-portfolio-optimizer-history-from-draft-action
                         :save-portfolio-optimizer-scenario-from-current
                         action-adapters/save-portfolio-optimizer-scenario-from-current-action
                         :run-portfolio-optimizer-from-draft
                         action-adapters/run-portfolio-optimizer-from-draft-action}
   :api-wallets {:load-api-wallet-route action-adapters/load-api-wallet-route-action
                 :set-api-wallet-form-field api-wallets-actions/set-api-wallet-form-field
                 :set-api-wallet-sort api-wallets-actions/set-api-wallet-sort
                 :generate-api-wallet api-wallets-actions/generate-api-wallet
                 :open-api-wallet-authorize-modal api-wallets-actions/open-api-wallet-authorize-modal
                 :open-api-wallet-remove-modal api-wallets-actions/open-api-wallet-remove-modal
                 :close-api-wallet-modal api-wallets-actions/close-api-wallet-modal
                 :confirm-api-wallet-modal api-wallets-actions/confirm-api-wallet-modal}
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
                :start-spectate-mode-watchlist-address action-adapters/start-spectate-mode-watchlist-address}
   :orders {:load-user-data action-adapters/load-user-data
            :set-funding-modal action-adapters/set-funding-modal}})

(defn runtime-action-deps
  []
  (runtime-collaborators/runtime-action-deps
   (runtime-action-overrides)))
