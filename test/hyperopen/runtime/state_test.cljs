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
  (is (= 1500 runtime-state/wallet-copy-feedback-duration-ms))
  (is (= 3500 runtime-state/order-feedback-toast-duration-ms))
  (is (= 1200 runtime-state/deferred-bootstrap-delay-ms))
  (is (= 120 runtime-state/per-dex-stagger-ms))
  (is (= 5000 runtime-state/startup-summary-delay-ms)))

(deftest runtime-state-exposes-runtime-atoms-test
  (is (map? @runtime-state/websocket-health-projection-state))
  (is (contains? @runtime-state/websocket-health-projection-state :fingerprint))
  (is (map? @runtime-state/websocket-health-sync-stats))
  (is (number? (:writes @runtime-state/websocket-health-sync-stats)))
  (is (map? @runtime-state/pending-asset-icon-statuses))
  (is (nil? @runtime-state/asset-icon-status-flush-handle)))
