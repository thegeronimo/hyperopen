(ns hyperopen.config-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.config :as app-config]))

(deftest config-exposes-centralized-runtime-parameters-test
  (let [cfg app-config/config]
    (is (= "wss://api.hyperliquid.xyz/ws" (:ws-url cfg)))
    (is (= "/sw.js" (:icon-service-worker-path cfg)))
    (is (string? (:app-version cfg)))
    (is (seq (:app-version cfg)))
    (is (= 5000 (get-in cfg [:cooldowns :reconnect-ms])))
    (is (= 5000 (get-in cfg [:cooldowns :reset-subscriptions-ms])))
    (is (= 30000 (get-in cfg [:cooldowns :auto-recover-severe-threshold-ms])))
    (is (= 300000 (get-in cfg [:cooldowns :auto-recover-cooldown-ms])))
    (is (= 1500 (get-in cfg [:ui :wallet-copy-feedback-ms])))
    (is (= 3500 (get-in cfg [:ui :order-toast-ms])))
    (is (= :d3 (get-in cfg [:ui :performance-chart-renderer :portfolio])))
    (is (= :d3 (get-in cfg [:ui :performance-chart-renderer :vaults])))
    (is (= 1200 (get-in cfg [:startup :deferred-bootstrap-delay-ms])))
    (is (= 120 (get-in cfg [:startup :per-dex-stagger-ms])))
    (is (= 5000 (get-in cfg [:startup :startup-summary-delay-ms])))
    (is (= 50 (get-in cfg [:diagnostics :timeline-limit])))))
