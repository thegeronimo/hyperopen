(ns hyperopen.views.portfolio.vm.performance-helpers-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.views.portfolio.vm.metrics-bridge :as vm-metrics-bridge]
            [hyperopen.views.portfolio.vm.performance :as vm-performance]))

(deftest build-metrics-request-data-derives-strategy-daily-and-benchmark-requests-test
  (let [strategy-cumulative-rows [[1 0]
                                  [2 5]]
        benchmark-cumulative-rows-by-coin {"SPY" [[1 0]
                                                  [2 3]]
                                           "QQQ" [[1 0]
                                                  [2 4]]}]
    (with-redefs [portfolio-metrics/daily-compounded-returns (fn [rows]
                                                               (mapv (fn [[time-ms value]]
                                                                       {:time-ms time-ms
                                                                        :value value})
                                                                     rows))]
      (is (= {:portfolio-request {:strategy-cumulative-rows strategy-cumulative-rows
                                  :strategy-daily-rows [{:time-ms 1 :value 0}
                                                        {:time-ms 2 :value 5}]}
              :benchmark-requests [{:coin "SPY"
                                    :request {:strategy-cumulative-rows [[1 0]
                                                                         [2 3]]}}
                                   {:coin "QQQ"
                                    :request {:strategy-cumulative-rows [[1 0]
                                                                         [2 4]]}}]}
             (vm-performance/build-metrics-request-data strategy-cumulative-rows
                                                        benchmark-cumulative-rows-by-coin
                                                        ["SPY" "QQQ"]))))))

(deftest performance-row-helpers-hide-suppressed-rows-and-enrich-benchmark-columns-test
  (let [groups [{:title "Returns"
                 :rows [{:key :sharpe
                         :label "Sharpe"}
                        {:key :time-in-market
                         :label "Time In Market"}]}]
        portfolio-values {:sharpe 1.5
                          :metric-status {:sharpe :ok
                                          :time-in-market :suppressed}
                          :metric-reason {:time-in-market :core-gate-failed}}
        benchmark-columns [{:coin "SPY"
                            :values {:sharpe 1.2
                                     :metric-status {:sharpe :ok}
                                     :metric-reason {}}}
                           {:coin "QQQ"
                            :values {:sharpe 0.9
                                     :metric-status {:sharpe :warning}
                                     :metric-reason {:sharpe :coverage-gate}}}]
        rows (-> groups
                 vm-performance/remove-hidden-portfolio-metric-rows
                 (vm-performance/with-performance-metric-columns portfolio-values benchmark-columns)
                 first
                 :rows)]
    (is (= [{:key :sharpe
             :label "Sharpe"
             :portfolio-value 1.5
             :portfolio-status :ok
             :portfolio-reason nil
             :benchmark-value 1.2
             :benchmark-status :ok
             :benchmark-reason nil
             :benchmark-values {"SPY" 1.2
                                "QQQ" 0.9}
             :benchmark-statuses {"SPY" :ok
                                  "QQQ" :warning}
             :benchmark-reasons {"SPY" nil
                                 "QQQ" :coverage-gate}}]
           rows))))

(deftest performance-row-helpers-render-relative-metrics-under-benchmark-columns-test
  (let [groups [{:title "Benchmark Relative"
                 :rows [{:key :r2
                         :label "R^2"}
                        {:key :information-ratio
                         :label "Information Ratio"}
                        {:key :beta
                         :label "Beta"}
                        {:key :alpha
                         :label "Alpha"}
                        {:key :cagr
                         :label "CAGR"}]}]
        portfolio-values {:r2 0.99
                          :information-ratio 1.4
                          :beta 1.4
                          :alpha 0.12
                          :cagr 0.2
                          :metric-status {:r2 :low-confidence
                                          :information-ratio :low-confidence
                                          :beta :ok
                                          :alpha :ok
                                          :cagr :ok}
                          :metric-reason {:r2 :benchmark-sparse-intervals
                                          :information-ratio :benchmark-sparse-intervals}}
        benchmark-columns [{:coin "BTC"
                            :values {:r2 0.7
                                     :information-ratio 0.2
                                     :beta 1.4
                                     :alpha 0.12
                                     :cagr 0.1
                                     :metric-status {:r2 :low-confidence
                                                     :information-ratio :low-confidence
                                                     :beta :low-confidence
                                                     :alpha :ok
                                                     :cagr :ok}
                                     :metric-reason {:r2 :benchmark-sparse-intervals
                                                     :information-ratio :benchmark-sparse-intervals
                                                     :beta :benchmark-sparse-intervals}}}
                           {:coin "ETH"
                            :values {:r2 0.4
                                     :information-ratio -0.1
                                     :beta 0.9
                                     :alpha 0.08
                                     :cagr 0.15
                                     :metric-status {:r2 :low-confidence
                                                     :information-ratio :low-confidence
                                                     :beta :ok
                                                     :alpha :ok
                                                     :cagr :ok}
                                     :metric-reason {:r2 :benchmark-sparse-intervals
                                                     :information-ratio :benchmark-sparse-intervals}}}]
        rows (-> groups
                 (vm-performance/with-performance-metric-columns portfolio-values benchmark-columns)
                 first
                 :rows)
        rows-by-key (into {} (map (juxt :key identity)) rows)]
    (is (= {:portfolio-value nil
            :portfolio-status nil
            :portfolio-reason nil
             :benchmark-values {"BTC" 1.4
                                "ETH" 0.9}
             :benchmark-statuses {"BTC" :low-confidence
                                  "ETH" :ok}
             :benchmark-reasons {"BTC" :benchmark-sparse-intervals
                                 "ETH" nil}}
            (select-keys (get rows-by-key :beta)
                         [:portfolio-value
                         :portfolio-status
                         :portfolio-reason
                          :benchmark-values
                          :benchmark-statuses
                          :benchmark-reasons])))
    (is (nil? (get-in rows-by-key [:r2 :portfolio-value])))
    (is (= 0.7 (get-in rows-by-key [:r2 :benchmark-values "BTC"])))
    (is (= 0.4 (get-in rows-by-key [:r2 :benchmark-values "ETH"])))
    (is (nil? (get-in rows-by-key [:information-ratio :portfolio-value])))
    (is (= 0.2 (get-in rows-by-key [:information-ratio :benchmark-values "BTC"])))
    (is (= -0.1 (get-in rows-by-key [:information-ratio :benchmark-values "ETH"])))
    (is (= 0.2 (:portfolio-value (get rows-by-key :cagr))))))

(deftest performance-metrics-model-skips-request-build-when-worker-signature-is-unchanged-test
  (let [strategy-cumulative-rows [[1 0]
                                  [2 11]
                                  [3 19]]
        benchmark-cumulative-rows-by-coin {"SPY" [[1 0]
                                                  [2 4]
                                                  [3 8]]
                                           "QQQ" [[1 0]
                                                  [2 2]
                                                  [3 5]]}
        selected-benchmark-coins ["SPY" "QQQ"]
        summary-time-range :month
        benchmark-context {:strategy-cumulative-rows strategy-cumulative-rows
                           :benchmark-cumulative-rows-by-coin benchmark-cumulative-rows-by-coin
                           :strategy-source-version 101
                           :benchmark-source-version-map {"SPY" 201
                                                          "QQQ" 301}}
        request-signature (vm-metrics-bridge/metrics-request-signature summary-time-range
                                                                       selected-benchmark-coins
                                                                       (:strategy-source-version benchmark-context)
                                                                       (:benchmark-source-version-map benchmark-context))
        state {:portfolio-ui {:metrics-loading? false
                              :metrics-result {:portfolio-values {:metric-status {}
                                                                  :metric-reason {}}
                                               :benchmark-values-by-coin {"SPY" {:metric-status {}
                                                                                  :metric-reason {}}
                                                                          "QQQ" {:metric-status {}
                                                                                  :metric-reason {}}}}}}
        benchmark-selector {:selected-coins selected-benchmark-coins
                            :label-by-coin {"SPY" "SPY (SPOT)"
                                            "QQQ" "QQQ (SPOT)"}}
        request-build-count (atom 0)
        request-dispatch-count (atom 0)]
    (binding [vm-performance/*metrics-worker* (delay #js {:postMessage (fn [_payload] nil)})
              vm-performance/*last-metrics-request* (atom {:signature request-signature})
              vm-performance/*build-metrics-request-data* (fn [& _]
                                                            (swap! request-build-count inc)
                                                            {})
              vm-performance/*request-metrics-computation!* (fn [& _]
                                                              (swap! request-dispatch-count inc))]
      (with-redefs [portfolio-metrics/metric-rows (fn [_]
                                                    [])]
        (let [model (vm-performance/performance-metrics-model state
                                                              summary-time-range
                                                              benchmark-selector
                                                              benchmark-context)]
          (is (= 0 @request-build-count))
          (is (= 0 @request-dispatch-count))
          (is (= ["SPY" "QQQ"] (:benchmark-coins model)))
          (is (= [{:coin "SPY" :label "SPY (SPOT)"}
                  {:coin "QQQ" :label "QQQ (SPOT)"}]
                 (:benchmark-columns model))))))))

(deftest performance-metrics-model-waits-for-benchmark-rows-before-posting-a-worker-job-test
  ;; A timeframe switch changes the candle INTERVAL, so the new interval's store
  ;; slot is empty on the first render after the click. Posting then produced a
  ;; worker job whose benchmark side was `[]`; its reply was applied, and the
  ;; six benchmark-relative metrics plus their labelled group vanished from the
  ;; tearsheet and reappeared once the real candles landed. The model must wait.
  (let [strategy-cumulative-rows [[1 0] [2 11] [3 19]]
        selected-benchmark-coins ["BTC"]
        summary-time-range :two-year
        empty-context {:strategy-cumulative-rows strategy-cumulative-rows
                       :benchmark-cumulative-rows-by-coin {"BTC" []}
                       :strategy-source-version 101
                       :benchmark-source-version-map {"BTC" 0}}
        ready-context {:strategy-cumulative-rows strategy-cumulative-rows
                       :benchmark-cumulative-rows-by-coin {"BTC" [[1 0] [2 4] [3 8]]}
                       :strategy-source-version 101
                       :benchmark-source-version-map {"BTC" 201}}
        state {:portfolio-ui {:metrics-loading? false
                              :metrics-result {:portfolio-values {:metric-status {}
                                                                  :metric-reason {}}}}}
        benchmark-selector {:selected-coins selected-benchmark-coins
                            :label-by-coin {"BTC" "BTC"}}
        dispatched (atom [])]
    (binding [vm-performance/*metrics-worker* (delay #js {:postMessage (fn [_] nil)})
              vm-performance/*last-metrics-request* (atom nil)
              vm-performance/*build-metrics-request-data* (fn [_strategy by-coin coins]
                                                            {:benchmark-requests
                                                             (mapv (fn [coin]
                                                                     {:coin coin
                                                                      :request {:strategy-cumulative-rows
                                                                                (get by-coin coin [])}})
                                                                   coins)})
              vm-performance/*request-metrics-computation!* (fn [request-data signature]
                                                              (swap! dispatched conj [request-data signature]))]
      (with-redefs [portfolio-metrics/metric-rows (fn [_] [])]
        (let [waiting (vm-performance/performance-metrics-model state
                                                                summary-time-range
                                                                benchmark-selector
                                                                empty-context)]
          (is (= [] @dispatched)
              "no job is posted while the benchmark series is still empty")
          (is (true? (:stale? waiting))
              "and the numbers still on screen are labelled as belonging to another window"))
        (let [_ (vm-performance/performance-metrics-model state
                                                          summary-time-range
                                                          benchmark-selector
                                                          ready-context)]
          (is (= 1 (count @dispatched))
              "exactly one job is posted, once the candles have landed")
          (is (= [[[1 0] [2 4] [3 8]]]
                 (mapv (comp :strategy-cumulative-rows :request)
                       (:benchmark-requests (ffirst @dispatched))))
              "and it carries the real benchmark series, never an empty one"))))))

(deftest performance-metrics-model-clears-stale-once-the-applied-signature-matches-test
  (let [strategy-cumulative-rows [[1 0] [2 11]]
        selected-benchmark-coins ["BTC"]
        summary-time-range :two-year
        benchmark-context {:strategy-cumulative-rows strategy-cumulative-rows
                           :benchmark-cumulative-rows-by-coin {"BTC" [[1 0] [2 4]]}
                           :strategy-source-version 101
                           :benchmark-source-version-map {"BTC" 201}}
        request-signature (vm-metrics-bridge/metrics-request-signature summary-time-range
                                                                       selected-benchmark-coins
                                                                       101
                                                                       {"BTC" 201})
        state {:portfolio-ui {:metrics-loading? false
                              :metrics-result {:portfolio-values {:metric-status {}
                                                                  :metric-reason {}}}
                              :metrics-result-signature request-signature}}
        benchmark-selector {:selected-coins selected-benchmark-coins
                            :label-by-coin {"BTC" "BTC"}}]
    (binding [vm-performance/*metrics-worker* (delay #js {:postMessage (fn [_] nil)})
              vm-performance/*last-metrics-request* (atom {:signature request-signature})
              vm-performance/*build-metrics-request-data* (fn [& _] {})
              vm-performance/*request-metrics-computation!* (fn [& _] nil)]
      (with-redefs [portfolio-metrics/metric-rows (fn [_] [])]
        (let [model (vm-performance/performance-metrics-model state
                                                              summary-time-range
                                                              benchmark-selector
                                                              benchmark-context)]
          (is (false? (:stale? model))
              "numbers computed for the window on screen are not labelled stale"))))))

(deftest performance-metrics-model-stale-tracks-the-question-not-data-freshness-test
  ;; `:stale?` must mean "these numbers answer a different question", not "these
  ;; numbers are a few seconds old". Keying it on the whole request signature made
  ;; the badge blink on and off every few hundred milliseconds on an active
  ;; account, because `strategy-source-version` and the per-coin benchmark
  ;; versions move on every live data refresh while nothing the trader chose has
  ;; changed. Measured in a real browser: 26 layout-shift entries in 7 s, all
  ;; attributed to the badge toggling inside the tearsheet header row.
  (let [coins ["BTC"]
        context (fn [strategy-version benchmark-version]
                  {:strategy-cumulative-rows [[1 0] [2 11]]
                   :benchmark-cumulative-rows-by-coin {"BTC" [[1 0] [2 4]]}
                   :strategy-source-version strategy-version
                   :benchmark-source-version-map {"BTC" benchmark-version}})
        applied (vm-metrics-bridge/metrics-request-signature :two-year coins 101 {"BTC" 201})
        state {:portfolio-ui {:metrics-loading? false
                              :metrics-result {:portfolio-values {:metric-status {}
                                                                  :metric-reason {}}}
                              :metrics-result-signature applied}}
        selector {:selected-coins coins :label-by-coin {"BTC" "BTC"}}
        stale-for (fn [range strategy-version benchmark-version]
                    (binding [vm-performance/*metrics-worker* (delay #js {:postMessage (fn [_] nil)})
                              vm-performance/*last-metrics-request* (atom {:signature applied})
                              vm-performance/*build-metrics-request-data* (fn [& _] {})
                              vm-performance/*request-metrics-computation!* (fn [& _] nil)]
                      (with-redefs [portfolio-metrics/metric-rows (fn [_] [])]
                        (:stale? (vm-performance/performance-metrics-model
                                  state range selector
                                  (context strategy-version benchmark-version))))))]
    (is (false? (stale-for :two-year 101 201))
        "same window, same data — not stale")
    (is (false? (stale-for :two-year 999 888))
        "same window, fresher data — still not stale, and the badge must not blink")
    (is (true? (stale-for :month 101 201))
        "a different window IS stale")))

(deftest performance-metrics-model-stale-tracks-benchmark-selection-test
  (let [applied (vm-metrics-bridge/metrics-request-signature :two-year ["BTC"] 101 {"BTC" 201})
        state {:portfolio-ui {:metrics-loading? false
                              :metrics-result {:portfolio-values {:metric-status {}
                                                                  :metric-reason {}}}
                              :metrics-result-signature applied}}
        context {:strategy-cumulative-rows [[1 0] [2 11]]
                 :benchmark-cumulative-rows-by-coin {"BTC" [[1 0] [2 4]]
                                                     "ETH" [[1 0] [2 3]]}
                 :strategy-source-version 101
                 :benchmark-source-version-map {"BTC" 201 "ETH" 202}}]
    (binding [vm-performance/*metrics-worker* (delay #js {:postMessage (fn [_] nil)})
              vm-performance/*last-metrics-request* (atom {:signature applied})
              vm-performance/*build-metrics-request-data* (fn [& _] {})
              vm-performance/*request-metrics-computation!* (fn [& _] nil)]
      (with-redefs [portfolio-metrics/metric-rows (fn [_] [])]
        (is (true? (:stale? (vm-performance/performance-metrics-model
                             state :two-year
                             {:selected-coins ["BTC" "ETH"]
                              :label-by-coin {"BTC" "BTC" "ETH" "ETH"}}
                             context)))
            "adding a benchmark changes the question, so the numbers are stale")))))
