(ns hyperopen.runtime.wiring
  (:require [hyperopen.registry.runtime :as runtime-registry]
            [hyperopen.runtime.action-adapters :as action-adapters]
            [hyperopen.runtime.collaborators :as runtime-collaborators]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.runtime.registry-composition :as registry-composition]))

(defn- runtime-effect-overrides
  [runtime]
  {:save effect-adapters/save
   :save-many effect-adapters/save-many
   :local-storage-set effect-adapters/local-storage-set
   :local-storage-set-json effect-adapters/local-storage-set-json
   :queue-asset-icon-status (fn [ctx store payload]
                              (effect-adapters/queue-asset-icon-status runtime ctx store payload))
   :push-state effect-adapters/push-state
   :replace-state effect-adapters/replace-state
   :init-websocket effect-adapters/init-websocket
   :subscribe-active-asset effect-adapters/subscribe-active-asset
   :subscribe-orderbook effect-adapters/subscribe-orderbook
   :subscribe-trades effect-adapters/subscribe-trades
   :subscribe-webdata2 effect-adapters/subscribe-webdata2
   :fetch-candle-snapshot effect-adapters/fetch-candle-snapshot
   :unsubscribe-active-asset effect-adapters/unsubscribe-active-asset
   :unsubscribe-orderbook effect-adapters/unsubscribe-orderbook
   :unsubscribe-trades effect-adapters/unsubscribe-trades
   :unsubscribe-webdata2 effect-adapters/unsubscribe-webdata2
   :connect-wallet effect-adapters/connect-wallet
   :disconnect-wallet (fn [ctx store]
                        (effect-adapters/disconnect-wallet runtime ctx store))
   :enable-agent-trading action-adapters/enable-agent-trading
   :set-agent-storage-mode effect-adapters/set-agent-storage-mode
   :copy-wallet-address (fn [ctx store address]
                          (effect-adapters/copy-wallet-address runtime ctx store address))
   :reconnect-websocket effect-adapters/reconnect-websocket
   :refresh-websocket-health (fn [ctx store]
                               (effect-adapters/refresh-websocket-health runtime ctx store))
   :confirm-ws-diagnostics-reveal effect-adapters/confirm-ws-diagnostics-reveal
   :copy-websocket-diagnostics effect-adapters/copy-websocket-diagnostics
   :ws-reset-subscriptions effect-adapters/ws-reset-subscriptions
   :fetch-asset-selector-markets effect-adapters/fetch-asset-selector-markets-effect
   :api-submit-order (fn [ctx store request]
                       (effect-adapters/api-submit-order runtime ctx store request))
   :api-cancel-order (fn [ctx store request]
                       (effect-adapters/api-cancel-order runtime ctx store request))
   :api-load-user-data effect-adapters/api-load-user-data-effect})

(defn runtime-effect-deps
  ([] (runtime-effect-deps runtime-state/runtime))
  ([runtime]
  (runtime-collaborators/runtime-effect-deps
   (runtime-effect-overrides runtime))))

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

(defn runtime-registration-deps
  ([] (runtime-registration-deps runtime-state/runtime))
  ([runtime]
  (registry-composition/runtime-registration-deps
   {:register-effects! runtime-registry/register-effects!
    :register-actions! runtime-registry/register-actions!
    :register-system-state! runtime-registry/register-system-state!
    :register-placeholders! runtime-registry/register-placeholders!}
   {:effect-deps (runtime-effect-deps runtime)
    :action-deps (runtime-action-deps)})))
