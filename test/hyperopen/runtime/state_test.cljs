(ns hyperopen.runtime.state-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.runtime.state :as runtime-state]))

(deftest runtime-state-exposes-default-config-values-test
  (is (= 50 runtime-state/diagnostics-timeline-limit))
  (is (= 5000 runtime-state/reconnect-cooldown-ms))
  (is (= 5000 runtime-state/reset-subscriptions-cooldown-ms))
  (is (= 30000 runtime-state/auto-recover-severe-threshold-ms))
  (is (= 300000 runtime-state/auto-recover-cooldown-ms))
  (is (= "/sw.js" runtime-state/icon-service-worker-path))
  (is (= "0.1.0" runtime-state/app-version))
  (is (= "wss://api.hyperliquid.xyz/ws" runtime-state/websocket-url))
  (is (= 1500 runtime-state/wallet-copy-feedback-duration-ms))
  (is (= 3500 runtime-state/order-feedback-toast-duration-ms))
  (is (= 1200 runtime-state/deferred-bootstrap-delay-ms))
  (is (= 120 runtime-state/per-dex-stagger-ms))
  (is (= 5000 runtime-state/startup-summary-delay-ms)))

(deftest runtime-state-exposes-single-runtime-atom-test
  (let [runtime (runtime-state/make-runtime-state)]
    (is (map? @runtime))
    (is (contains? (:websocket-health @runtime) :fingerprint))
    (is (number? (get-in @runtime [:websocket-health :writes])))
    (is (map? (get-in @runtime [:asset-icons :pending])))
    (is (nil? (get-in @runtime [:asset-icons :flush-handle])))
    (is (false? (runtime-state/runtime-bootstrapped? runtime)))))

(deftest runtime-state-mark-runtime-bootstrapped-updates-once-test
  (let [runtime (runtime-state/make-runtime-state)]
    (is (true? (runtime-state/mark-runtime-bootstrapped! runtime)))
    (is (true? (runtime-state/runtime-bootstrapped? runtime)))
    (is (false? (runtime-state/mark-runtime-bootstrapped! runtime)))))
