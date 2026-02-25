(ns hyperopen.runtime.state-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.config :as app-config]
            [hyperopen.runtime.state :as runtime-state]))

(deftest runtime-state-exposes-default-config-values-test
  (let [cfg app-config/config]
    (is (= (get-in cfg [:diagnostics :timeline-limit]) runtime-state/diagnostics-timeline-limit))
    (is (= (get-in cfg [:cooldowns :reconnect-ms]) runtime-state/reconnect-cooldown-ms))
    (is (= (get-in cfg [:cooldowns :reset-subscriptions-ms]) runtime-state/reset-subscriptions-cooldown-ms))
    (is (= (get-in cfg [:cooldowns :auto-recover-severe-threshold-ms]) runtime-state/auto-recover-severe-threshold-ms))
    (is (= (get-in cfg [:cooldowns :auto-recover-cooldown-ms]) runtime-state/auto-recover-cooldown-ms))
    (is (= (:icon-service-worker-path cfg) runtime-state/icon-service-worker-path))
    (is (= (:app-version cfg) runtime-state/app-version))
    (is (= (:ws-url cfg) runtime-state/websocket-url))
    (is (= (get-in cfg [:ui :wallet-copy-feedback-ms]) runtime-state/wallet-copy-feedback-duration-ms))
    (is (= (get-in cfg [:ui :order-toast-ms]) runtime-state/order-feedback-toast-duration-ms))
    (is (= (get-in cfg [:startup :deferred-bootstrap-delay-ms]) runtime-state/deferred-bootstrap-delay-ms))
    (is (= (get-in cfg [:startup :per-dex-stagger-ms]) runtime-state/per-dex-stagger-ms))
    (is (= (get-in cfg [:startup :startup-summary-delay-ms]) runtime-state/startup-summary-delay-ms))))

(deftest runtime-state-exposes-single-runtime-atom-test
  (let [runtime (runtime-state/make-runtime-state)]
    (is (map? @runtime))
    (is (map? (get-in @runtime [:asset-icons :pending])))
    (is (nil? (get-in @runtime [:asset-icons :flush-handle])))
    (is (false? (runtime-state/app-started? runtime)))
    (is (false? (runtime-state/runtime-bootstrapped? runtime)))))

(deftest runtime-state-mark-app-started-updates-once-test
  (let [runtime (runtime-state/make-runtime-state)]
    (is (true? (runtime-state/mark-app-started! runtime)))
    (is (true? (runtime-state/app-started? runtime)))
    (is (false? (runtime-state/mark-app-started! runtime))))
  (let [runtime (runtime-state/make-runtime-state)]
    (swap! runtime assoc :app-started? true)
    (is (true? (runtime-state/app-started? runtime)))))

(deftest runtime-state-mark-runtime-bootstrapped-updates-once-test
  (let [runtime (runtime-state/make-runtime-state)]
    (is (true? (runtime-state/mark-runtime-bootstrapped! runtime)))
    (is (true? (runtime-state/runtime-bootstrapped? runtime)))
    (is (false? (runtime-state/mark-runtime-bootstrapped! runtime)))))
