(ns hyperopen.runtime.action-adapters
  (:require [hyperopen.funding.actions :as funding-actions]
            [hyperopen.portfolio.optimizer.actions :as portfolio-optimizer-actions]
            [hyperopen.runtime.action-adapters.leaderboard :as leaderboard-adapters]
            [hyperopen.runtime.action-adapters.navigation :as navigation-adapters]
            [hyperopen.runtime.action-adapters.spectate-mode :as spectate-mode-adapters]
            [hyperopen.runtime.action-adapters.wallet :as wallet-adapters]
            [hyperopen.runtime.action-adapters.websocket :as websocket-adapters]
            [hyperopen.runtime.action-adapters.ws-diagnostics :as ws-diagnostics-adapters]))

(def init-websockets websocket-adapters/init-websockets)

(def subscribe-to-asset websocket-adapters/subscribe-to-asset)

(def subscribe-to-webdata2 websocket-adapters/subscribe-to-webdata2)

(def refresh-asset-markets websocket-adapters/refresh-asset-markets)

(def load-user-data websocket-adapters/load-user-data)

(def set-funding-modal funding-actions/set-funding-modal-compat)

(def open-spectate-mode-modal spectate-mode-adapters/open-spectate-mode-modal)

(def close-spectate-mode-modal spectate-mode-adapters/close-spectate-mode-modal)

(def set-spectate-mode-search spectate-mode-adapters/set-spectate-mode-search)

(def set-spectate-mode-label spectate-mode-adapters/set-spectate-mode-label)

(def start-spectate-mode spectate-mode-adapters/start-spectate-mode)

(def stop-spectate-mode spectate-mode-adapters/stop-spectate-mode)

(def add-spectate-mode-watchlist-address
  spectate-mode-adapters/add-spectate-mode-watchlist-address)

(def remove-spectate-mode-watchlist-address
  spectate-mode-adapters/remove-spectate-mode-watchlist-address)

(def edit-spectate-mode-watchlist-address
  spectate-mode-adapters/edit-spectate-mode-watchlist-address)

(def clear-spectate-mode-watchlist-edit
  spectate-mode-adapters/clear-spectate-mode-watchlist-edit)

(def copy-spectate-mode-watchlist-address
  spectate-mode-adapters/copy-spectate-mode-watchlist-address)

(def copy-spectate-mode-watchlist-link
  spectate-mode-adapters/copy-spectate-mode-watchlist-link)

(def start-spectate-mode-watchlist-address
  spectate-mode-adapters/start-spectate-mode-watchlist-address)

(def navigate navigation-adapters/navigate)

(def load-vault-route-action navigation-adapters/load-vault-route-action)

(def load-leaderboard-route-action leaderboard-adapters/load-leaderboard-route-action)

(def set-leaderboard-query-action leaderboard-adapters/set-leaderboard-query-action)

(def set-leaderboard-timeframe-action leaderboard-adapters/set-leaderboard-timeframe-action)

(def set-leaderboard-sort-action leaderboard-adapters/set-leaderboard-sort-action)

(def set-leaderboard-page-size-action leaderboard-adapters/set-leaderboard-page-size-action)

(def toggle-leaderboard-page-size-dropdown-action
  leaderboard-adapters/toggle-leaderboard-page-size-dropdown-action)

(def close-leaderboard-page-size-dropdown-action
  leaderboard-adapters/close-leaderboard-page-size-dropdown-action)

(def set-leaderboard-page-action leaderboard-adapters/set-leaderboard-page-action)

(def next-leaderboard-page-action leaderboard-adapters/next-leaderboard-page-action)

(def prev-leaderboard-page-action leaderboard-adapters/prev-leaderboard-page-action)

(def load-funding-comparison-route-action
  navigation-adapters/load-funding-comparison-route-action)

(def load-staking-route-action navigation-adapters/load-staking-route-action)

(def load-api-wallet-route-action navigation-adapters/load-api-wallet-route-action)

(def connect-wallet-action wallet-adapters/connect-wallet-action)

(def disconnect-wallet-action wallet-adapters/disconnect-wallet-action)

(def should-auto-enable-agent-trading? wallet-adapters/should-auto-enable-agent-trading?)

(def handle-wallet-connected wallet-adapters/handle-wallet-connected)

(def enable-agent-trading wallet-adapters/enable-agent-trading)

(def enable-agent-trading-action wallet-adapters/enable-agent-trading-action)

(def unlock-agent-trading wallet-adapters/unlock-agent-trading)

(def unlock-agent-trading-action wallet-adapters/unlock-agent-trading-action)

(def set-agent-storage-mode-action wallet-adapters/set-agent-storage-mode-action)

(def set-agent-local-protection-mode-action
  wallet-adapters/set-agent-local-protection-mode-action)

(def copy-wallet-address-action wallet-adapters/copy-wallet-address-action)

(def reconnect-websocket-action websocket-adapters/reconnect-websocket-action)

(def toggle-ws-diagnostics ws-diagnostics-adapters/toggle-ws-diagnostics)

(def close-ws-diagnostics ws-diagnostics-adapters/close-ws-diagnostics)

(def handle-ws-diagnostics-keydown
  ws-diagnostics-adapters/handle-ws-diagnostics-keydown)

(def toggle-ws-diagnostics-sensitive
  ws-diagnostics-adapters/toggle-ws-diagnostics-sensitive)

(def ws-diagnostics-reconnect-now ws-diagnostics-adapters/ws-diagnostics-reconnect-now)

(def ws-diagnostics-copy ws-diagnostics-adapters/ws-diagnostics-copy)

(def set-show-surface-freshness-cues
  ws-diagnostics-adapters/set-show-surface-freshness-cues)

(def toggle-show-surface-freshness-cues
  ws-diagnostics-adapters/toggle-show-surface-freshness-cues)

(def ws-diagnostics-reset-market-subscriptions
  ws-diagnostics-adapters/ws-diagnostics-reset-market-subscriptions)

(def ws-diagnostics-reset-orders-subscriptions
  ws-diagnostics-adapters/ws-diagnostics-reset-orders-subscriptions)

(def ws-diagnostics-reset-all-subscriptions
  ws-diagnostics-adapters/ws-diagnostics-reset-all-subscriptions)

(def run-portfolio-optimizer-action
  portfolio-optimizer-actions/run-portfolio-optimizer)

(def set-portfolio-optimizer-objective-kind-action
  portfolio-optimizer-actions/set-portfolio-optimizer-objective-kind)

(def set-portfolio-optimizer-return-model-kind-action
  portfolio-optimizer-actions/set-portfolio-optimizer-return-model-kind)

(def set-portfolio-optimizer-risk-model-kind-action
  portfolio-optimizer-actions/set-portfolio-optimizer-risk-model-kind)

(def set-portfolio-optimizer-constraint-action
  portfolio-optimizer-actions/set-portfolio-optimizer-constraint)

(def set-portfolio-optimizer-universe-from-current-action
  portfolio-optimizer-actions/set-portfolio-optimizer-universe-from-current)

(def run-portfolio-optimizer-from-draft-action
  portfolio-optimizer-actions/run-portfolio-optimizer-from-draft)
