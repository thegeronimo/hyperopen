(ns hyperopen.views.portfolio.vm.benchmark-chart-path-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.views.account-equity-view :as account-equity-view]
            [hyperopen.views.portfolio.vm :as vm]))

(defn- approx=
  [left right]
  (< (js/Math.abs (- left right)) 1e-9))

(deftest portfolio-vm-returns-benchmark-series-preserves-dense-market-path-over-shared-time-domain-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [t0 (.getTime (js/Date. "2025-04-14T00:00:00.000Z"))
          t1 (.getTime (js/Date. "2025-07-14T00:00:00.000Z"))
          t2 (.getTime (js/Date. "2025-10-14T00:00:00.000Z"))
          t3 (.getTime (js/Date. "2026-01-14T00:00:00.000Z"))
          t4 (.getTime (js/Date. "2026-04-14T00:00:00.000Z"))
          state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :one-year
                                :chart-tab :returns
                                :returns-benchmark-coin "SPY"}
                 :asset-selector {:markets [{:coin "SPY"
                                             :symbol "SPY"
                                             :market-type :spot
                                             :cache-order 1}]}
                 :portfolio {:summary-by-key {:one-year {:pnlHistory [[t3 0] [t4 0]]
                                                         :accountValueHistory [[t3 100] [t4 110]]
                                                         :vlm 10}}}
                 :candles {"SPY" {:12h [{:t t0 :c 100}
                                        {:t t1 :c 94}
                                        {:t t2 :c 90}
                                        {:t t3 :c 86}
                                        {:t t4 :c 88}]}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}}
                 :borrow-lend {:total-supplied-usd 0}}]
      (with-redefs [portfolio-metrics/returns-history-rows (fn [_state _summary _scope]
                                                             [[t3 0] [t4 10]])]
        (let [view-model (vm/portfolio-vm state)
              series (get-in view-model [:chart :series])
              strategy-series (first series)
              benchmark-series (second series)]
          (is (= [:strategy :benchmark-0]
                 (mapv :id series)))
          (is (= [t3 t4]
                 (mapv :time-ms (:points strategy-series))))
          (is (= [t0 t1 t2 t3 t4]
                 (mapv :time-ms (:points benchmark-series))))
          (is (> (count (:points benchmark-series))
                 (count (:points strategy-series))))
          (is (= [0 -6 -10 -14 -12]
                 (mapv :value (:points benchmark-series))))
          (is (every? true?
                      (map approx=
                           [0.7534246575342466 1]
                           (mapv :x-ratio (:points strategy-series)))))
          (is (every? true?
                      (map approx=
                           [0 0.2493150684931507 0.5013698630136987 0.7534246575342466 1]
                           (mapv :x-ratio (:points benchmark-series)))))
          (is (= "SPY (SPOT)"
                 (:label benchmark-series))))))))

(deftest portfolio-vm-returns-series-reconstructs-bounded-window-from-all-time-history-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [t0 (.getTime (js/Date. "2025-04-14T00:00:00.000Z"))
          t1 (.getTime (js/Date. "2025-07-14T00:00:00.000Z"))
          t2 (.getTime (js/Date. "2025-10-14T00:00:00.000Z"))
          t3 (.getTime (js/Date. "2026-01-14T00:00:00.000Z"))
          t4 (.getTime (js/Date. "2026-04-14T00:00:00.000Z"))
          full-summary {:pnlHistory [[t0 0] [t1 10] [t2 20] [t3 30] [t4 40]]
                        :accountValueHistory [[t0 100] [t1 110] [t2 120] [t3 130] [t4 140]]
                        :vlm 10}
          tail-summary {:pnlHistory [[t3 30] [t4 40]]
                        :accountValueHistory [[t3 130] [t4 140]]
                        :vlm 10}
          state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :one-year
                                :chart-tab :returns
                                :returns-benchmark-coin "SPY"}
                 :asset-selector {:markets [{:coin "SPY"
                                             :symbol "SPY"
                                             :market-type :spot
                                             :cache-order 1}]}
                 :portfolio {:summary-by-key {:one-year tail-summary
                                              :all-time full-summary}}
                 :candles {"SPY" {:12h [{:t t0 :c 100}
                                        {:t t1 :c 94}
                                        {:t t2 :c 90}
                                        {:t t3 :c 86}
                                        {:t t4 :c 88}]}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)
          series (get-in view-model [:chart :series])
          strategy-series (first series)
          benchmark-series (second series)
          strategy-points (:points strategy-series)
          benchmark-points (:points benchmark-series)]
      (is (= [t0 t1 t2 t3 t4]
             (mapv :time-ms strategy-points)))
      (is (= [t0 t1 t2 t3 t4]
             (mapv :time-ms benchmark-points)))
      (is (= 5 (count strategy-points)))
      (is (= 5 (count benchmark-points)))
      (is (every? #(< 1000000000000 %)
                  (mapcat (fn [points]
                            (map :time-ms points))
                          [strategy-points benchmark-points])))
      (is (every? true?
                  (map approx=
                       [0 1]
                       [(apply min (map :x-ratio strategy-points))
                        (apply max (map :x-ratio strategy-points))])))
      (is (every? true?
                  (map approx=
                       [0 1]
                       [(apply min (map :x-ratio benchmark-points))
                        (apply max (map :x-ratio benchmark-points))])))
      (is (= {:point-count 5
              :first-time-ms t0
              :last-time-ms t4
              :complete-window? true}
             (select-keys (get-in view-model [:chart :strategy-window])
                          [:point-count :first-time-ms :last-time-ms :complete-window?]))))))

(deftest portfolio-vm-returns-series-exposes-incomplete-window-metadata-when-earlier-history-is-missing-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [t0 (.getTime (js/Date. "2025-04-14T00:00:00.000Z"))
          t3 (.getTime (js/Date. "2026-01-14T00:00:00.000Z"))
          t4 (.getTime (js/Date. "2026-04-14T00:00:00.000Z"))
          tail-summary {:pnlHistory [[t3 30] [t4 40]]
                        :accountValueHistory [[t3 130] [t4 140]]
                        :vlm 10}
          state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :one-year
                                :chart-tab :returns}
                 :portfolio {:summary-by-key {:one-year tail-summary}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)]
      (is (= [t3 t4]
             (mapv :time-ms (get-in view-model [:chart :points]))))
      (is (= {:point-count 2
              :first-time-ms t3
              :last-time-ms t4
              :window-start-ms t3
              :complete-window? false}
             (select-keys (get-in view-model [:chart :strategy-window])
                          [:point-count :first-time-ms :last-time-ms :window-start-ms :complete-window?])))
      (is (= t0
             (get-in view-model [:chart :strategy-window :cutoff-ms]))))))
