(ns hyperopen.runtime.wiring-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.runtime.action-adapters :as action-adapters]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.wiring :as wiring]))

(deftest runtime-effect-deps-uses-extracted-effect-adapter-overrides-test
  (let [deps (wiring/runtime-effect-deps)]
    (is (identical? effect-adapters/save
                    (get-in deps [:storage :save])))
    (is (identical? effect-adapters/fetch-candle-snapshot
                    (get-in deps [:websocket :fetch-candle-snapshot])))
    (is (identical? effect-adapters/ws-reset-subscriptions
                    (get-in deps [:diagnostics :ws-reset-subscriptions])))
    (is (identical? effect-adapters/api-fetch-vault-index-effect
                    (get-in deps [:api :api-fetch-vault-index])))
    (is (identical? effect-adapters/api-fetch-vault-ledger-updates-effect
                    (get-in deps [:api :api-fetch-vault-ledger-updates])))
    (is (identical? action-adapters/enable-agent-trading
                    (get-in deps [:wallet :enable-agent-trading])))))

(deftest runtime-action-deps-uses-extracted-action-adapter-overrides-test
  (let [deps (wiring/runtime-action-deps)]
    (is (identical? action-adapters/init-websockets
                    (get-in deps [:core :init-websockets])))
    (is (identical? action-adapters/reconnect-websocket-action
                    (get-in deps [:core :reconnect-websocket-action])))
    (is (identical? action-adapters/refresh-asset-markets
                    (get-in deps [:asset-selector :refresh-asset-markets])))
    (is (identical? action-adapters/load-vault-route-action
                    (get-in deps [:vaults :load-vault-route])))
    (is (identical? action-adapters/navigate
                    (get-in deps [:core :navigate])))))

(deftest runtime-registration-deps-builds-effect-and-action-handlers-test
  (let [deps (wiring/runtime-registration-deps)]
    (is (fn? (:register-effects! deps)))
    (is (fn? (:register-actions! deps)))
    (is (fn? (:register-system-state! deps)))
    (is (fn? (:register-placeholders! deps)))
    (is (identical? action-adapters/navigate
                    (get-in deps [:action-handlers :navigate])))
    (is (identical? effect-adapters/save
                    (get-in deps [:effect-handlers :save])))))
