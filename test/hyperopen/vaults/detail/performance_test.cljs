(ns hyperopen.vaults.detail.performance-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.vaults.detail.metrics-bridge :as metrics-bridge]
            [hyperopen.vaults.detail.performance :as performance]))

(deftest snapshot-value-by-range-normalizes-monthly-snapshot-values-test
  (let [row {:snapshot-by-key {:month [0.1 0.2]
                               :all-time [0.5]}}]
    (is (= 20 (performance/snapshot-value-by-range row :month 200)))
    (is (= 50 (performance/snapshot-value-by-range row :all-time 200)))
    (is (nil? (performance/snapshot-value-by-range row :week 200)))))

(deftest portfolio-summary-derives-window-from-all-time-when-slice-missing-test
  (let [end-time-ms 1738306800000
        old-time-ms 1702006800000
        inside-window-start-ms 1712386800000
        details {:portfolio {:all-time {:accountValueHistory [[old-time-ms 100]
                                                              [inside-window-start-ms 120]
                                                              [end-time-ms 150]]
                                        :pnlHistory [[old-time-ms 0]
                                                     [inside-window-start-ms 10]
                                                     [end-time-ms 20]]}}}
        summary (performance/portfolio-summary details :one-year)]
    (is (= [inside-window-start-ms end-time-ms]
           (mapv first (:accountValueHistory summary))))
    (is (= [0 10]
           (mapv second (:pnlHistory summary))))))

(deftest chart-series-data-normalizes-mixed-history-row-shapes-test
  (with-redefs [portfolio-metrics/returns-history-rows (fn [_state _summary _scope]
                                                         [{:time-ms 10 :value -0.0001}
                                                          {:timestamp "11" :pnl "2.345"}
                                                          ["bad" 4]])]
    (let [summary {:accountValueHistory [{:time-ms 3 :accountValue "15"}
                                         [1 "5"]
                                         {:t 2 :pnl "10"}
                                         {:time-ms js/NaN :value 4}
                                         ["bad" 1]]
                   :pnlHistory [[3 "6"]
                                {:timestamp 1 :pnl "2"}
                                {:t 2 :pnl "4"}
                                {:time-ms "bad" :value 8}]}
          series (performance/chart-series-data {} summary)]
      (is (= [{:time-ms 1 :value 5}
              {:time-ms 2 :value 10}
              {:time-ms 3 :value 15}]
             (:account-value series)))
      (is (= [{:time-ms 1 :value 2}
              {:time-ms 2 :value 4}
              {:time-ms 3 :value 6}]
             (:pnl series)))
      (is (= [{:index 0 :time-ms 10 :value 0}
              {:index 1 :time-ms 11 :value 2.35}]
             (:returns series))))))

(deftest performance-metrics-model-builds-benchmark-columns-test
  (let [selector {:selected-coins ["BTC"]
                  :label-by-coin {"BTC" "BTC (HL PERP)"}}
        model (performance/performance-metrics-model
               selector
               [[1 0] [2 10] [3 20]]
               {"BTC" [[1 0] [2 5] [3 7]]})]
    (is (true? (:benchmark-selected? model)))
    (is (= ["BTC"] (:benchmark-coins model)))
    (is (= "BTC (HL PERP)" (:benchmark-label model)))
    (is (seq (:groups model)))))

(deftest performance-metrics-model-skips-request-build-when-worker-signature-is-unchanged-test
  (let [request-signature {:summary-time-range :month
                           :selected-benchmark-coins ["BTC"]
                           :strategy-source-version 101
                           :benchmark-source-versions [["BTC" 201]]}
        request-state (atom {:signature request-signature})
        request-build-count (atom 0)
        request-dispatch-count (atom 0)
        selector {:selected-coins ["BTC"]
                  :label-by-coin {"BTC" "BTC (HL PERP)"}}
        benchmark-context {:strategy-cumulative-rows [[1 0] [2 10] [3 20]]
                           :benchmark-cumulative-rows-by-coin {"BTC" [[1 0] [2 5] [3 7]]}
                           :strategy-source-version 101
                           :benchmark-source-version-map {"BTC" 201}}
        state {:vaults-ui {:detail-performance-metrics-loading? false
                           :detail-performance-metrics-result {:portfolio-values {:metric-status {}
                                                                                :metric-reason {}}
                                                               :benchmark-values-by-coin {"BTC" {:metric-status {}
                                                                                                  :metric-reason {}}}}}}]
    (with-redefs [performance/*metrics-worker* (delay #js {:postMessage (fn [_payload] nil)})
                  performance/*last-metrics-request* request-state
                  performance/*build-metrics-request-data* (fn [& _]
                                                             (swap! request-build-count inc)
                                                             {})
                  performance/*request-metrics-computation!* (fn [& _]
                                                               (swap! request-dispatch-count inc))
                  performance/*metrics-request-signature* metrics-bridge/metrics-request-signature]
      (let [model (performance/performance-metrics-model state
                                                         :month
                                                         selector
                                                         benchmark-context)]
        (is (= 0 @request-build-count))
        (is (= 0 @request-dispatch-count))
        (is (= ["BTC"] (:benchmark-coins model)))
        (is (= "BTC (HL PERP)" (:benchmark-label model)))))))
