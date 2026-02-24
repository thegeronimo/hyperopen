(ns hyperopen.app.effects
  (:require [hyperopen.runtime.action-adapters :as action-adapters]
            [hyperopen.runtime.collaborators :as runtime-collaborators]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.state :as runtime-state]))

(defn- runtime-effect-overrides
  [runtime]
  {:storage {:save effect-adapters/save
             :save-many effect-adapters/save-many
             :local-storage-set effect-adapters/local-storage-set
             :local-storage-set-json effect-adapters/local-storage-set-json}
   :asset-selector {:queue-asset-icon-status (effect-adapters/make-queue-asset-icon-status runtime)}
   :navigation {:push-state effect-adapters/push-state
                :replace-state effect-adapters/replace-state}
   :websocket {:init-websocket effect-adapters/init-websocket
               :subscribe-active-asset effect-adapters/subscribe-active-asset
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
            :copy-wallet-address (effect-adapters/make-copy-wallet-address runtime)}
   :diagnostics {:confirm-ws-diagnostics-reveal effect-adapters/confirm-ws-diagnostics-reveal
                 :copy-websocket-diagnostics effect-adapters/copy-websocket-diagnostics
                 :ws-reset-subscriptions effect-adapters/ws-reset-subscriptions}
   :orders {:api-submit-order (effect-adapters/make-api-submit-order runtime)
            :api-cancel-order (effect-adapters/make-api-cancel-order runtime)
            :api-submit-position-tpsl (effect-adapters/make-api-submit-position-tpsl runtime)}
   :api {:fetch-asset-selector-markets effect-adapters/fetch-asset-selector-markets-effect
         :api-load-user-data effect-adapters/api-load-user-data-effect}})

(defn runtime-effect-deps
  ([] (runtime-effect-deps runtime-state/runtime))
  ([runtime]
   (runtime-collaborators/runtime-effect-deps
    (runtime-effect-overrides runtime))))
