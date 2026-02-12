(ns hyperopen.runtime.wiring-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.runtime.action-adapters :as action-adapters]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.wiring :as wiring]))

(deftest runtime-effect-deps-uses-extracted-effect-adapter-overrides-test
  (let [deps (wiring/runtime-effect-deps)]
    (is (identical? effect-adapters/save
                    (:save deps)))
    (is (identical? effect-adapters/fetch-candle-snapshot
                    (:fetch-candle-snapshot deps)))
    (is (identical? effect-adapters/ws-reset-subscriptions
                    (:ws-reset-subscriptions deps)))
    (is (identical? action-adapters/enable-agent-trading
                    (:enable-agent-trading deps)))))

(deftest runtime-action-deps-uses-extracted-action-adapter-overrides-test
  (let [deps (wiring/runtime-action-deps)]
    (is (identical? action-adapters/init-websockets
                    (:init-websockets deps)))
    (is (identical? action-adapters/reconnect-websocket-action
                    (:reconnect-websocket-action deps)))
    (is (identical? action-adapters/refresh-asset-markets
                    (:refresh-asset-markets deps)))
    (is (identical? action-adapters/navigate
                    (:navigate deps)))))

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
