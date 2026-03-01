(ns hyperopen.views.portfolio.vm.metrics-bridge-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.views.portfolio.vm.metrics-bridge :as vm-metrics-bridge]))

(deftest portfolio-vm-metrics-request-signature-captures-time-range-coins-and-source-versions-test
  (let [signature-a (vm-metrics-bridge/metrics-request-signature [{:time-ms 101}]
                                                                 [{:time-ms 201}]
                                                                 0)
        signature-b (vm-metrics-bridge/metrics-request-signature [{:time-ms 101}]
                                                                 [{:time-ms 201}]
                                                                 0.05)
        signature-c (vm-metrics-bridge/metrics-request-signature [{:time-ms 102}]
                                                                 [{:time-ms 201}]
                                                                 0)
        signature-d (vm-metrics-bridge/metrics-request-signature [{:time-ms 101}]
                                                                 [{:time-ms 202}]
                                                                 0)]
    (is (string? signature-a))
    (is (not= signature-a signature-b))
    (is (not= signature-a signature-c))
    (is (not= signature-a signature-d))))

(deftest normalize-worker-metrics-result-deserializes-nested-status-maps-test
  (let [raw-metrics-result {:cumulative-return 0.1
                            :time-in-market 0.9
                            :cagr nil
                            :metric-status {:time-in-market "ok"
                                            :cagr "suppressed"
                                            :r2 "suppressed"}
                            :metric-reason {:cagr "core-gate-failed"
                                            :r2 "benchmark-coverage-gate-failed"}}
        worker-result {:values raw-metrics-result
                       :rows []}
        deserialized (-> worker-result
                         clj->js
                         (js->clj :keywordize-keys true)
                         (vm-metrics-bridge/normalize-worker-metrics-result))]
    (is (= "ok" (get-in deserialized [:values :metric-status :time-in-market]))
        "Metric status keywords survive worker structured-clone round-trip as strings unless specifically parsed, but structure remains intact")
    (is (= "suppressed" (get-in deserialized [:values :metric-status :r2])))
    (is (= "benchmark-coverage-gate-failed" (get-in deserialized [:values :metric-reason :r2])))))