(ns hyperopen.app.actions
  (:require [hyperopen.runtime.action-adapters :as action-adapters]
            [hyperopen.runtime.collaborators :as runtime-collaborators]))

(defn- runtime-action-overrides
  []
  {:init-websockets action-adapters/init-websockets
   :subscribe-to-asset action-adapters/subscribe-to-asset
   :subscribe-to-webdata2 action-adapters/subscribe-to-webdata2
   :enable-agent-trading-action action-adapters/enable-agent-trading-action
   :set-agent-storage-mode-action action-adapters/set-agent-storage-mode-action
   :reconnect-websocket-action action-adapters/reconnect-websocket-action
   :toggle-ws-diagnostics action-adapters/toggle-ws-diagnostics
   :close-ws-diagnostics action-adapters/close-ws-diagnostics
   :toggle-ws-diagnostics-sensitive action-adapters/toggle-ws-diagnostics-sensitive
   :ws-diagnostics-reconnect-now action-adapters/ws-diagnostics-reconnect-now
   :ws-diagnostics-copy action-adapters/ws-diagnostics-copy
   :set-show-surface-freshness-cues action-adapters/set-show-surface-freshness-cues
   :toggle-show-surface-freshness-cues action-adapters/toggle-show-surface-freshness-cues
   :ws-diagnostics-reset-market-subscriptions action-adapters/ws-diagnostics-reset-market-subscriptions
   :ws-diagnostics-reset-orders-subscriptions action-adapters/ws-diagnostics-reset-orders-subscriptions
   :ws-diagnostics-reset-all-subscriptions action-adapters/ws-diagnostics-reset-all-subscriptions
   :refresh-asset-markets action-adapters/refresh-asset-markets
   :load-user-data action-adapters/load-user-data
   :set-funding-modal action-adapters/set-funding-modal
   :navigate action-adapters/navigate})

(defn runtime-action-deps
  []
  (runtime-collaborators/runtime-action-deps
   (runtime-action-overrides)))
