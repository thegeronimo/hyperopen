(ns hyperopen.portfolio.metrics.builder-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.metrics :as metrics]
            [hyperopen.portfolio.metrics.test-utils :refer [approx= fixture-daily-rows day->ms quantstats-returns quantstats-benchmark]]))

(deftest metric-rows-propagates-status-and-reason-metadata-test
  (let [rows (->> (metrics/metric-rows {:cagr 0.123
                                        :metric-status {:cagr :ok
                                                        :sharpe :suppressed}
                                        :metric-reason {:sharpe :core-gate-failed}})
                  (mapcat :rows)
                  vec)
        cagr-row (first (filter #(= :cagr (:key %)) rows))
        sharpe-row (first (filter #(= :sharpe (:key %)) rows))]
    (is (= 0.123 (:value cagr-row)))
    (is (= :ok (:status cagr-row)))
    (is (nil? (:reason cagr-row)))
    (is (= :suppressed (:status sharpe-row)))
    (is (= :core-gate-failed (:reason sharpe-row)))))

(deftest compute-performance-metrics-cagr-uses-period-count-annualization-test
  (let [returns (vec (concat (repeat 20 0.01)
                             (repeat 20 -0.005)))
        rows (fixture-daily-rows returns)
        total-return (metrics/comp returns)
        t-years (/ (count returns) 365.2425)
        expected-cagr (- (js/Math.pow (+ 1 total-return)
                                      (/ 1 t-years))
                         1)
        metrics* (metrics/compute-performance-metrics {:strategy-daily-rows rows
                                                       :rf 0
                                                       :periods-per-year 365})
        cumulative (:cumulative-return metrics*)
        cagr (:cagr metrics*)]
    (is (approx= cumulative total-return 1e-12))
    (is (approx= cagr expected-cagr 1e-12))
    (is (not (approx= cagr cumulative 1e-12)))
    (is (#{:ok :low-confidence} (get-in metrics* [:metric-status :cagr])))))

(deftest compute-performance-metrics-ignores-cagr-years-override-key-test
  (let [rows [{:day "2023-01-01"
               :time-ms (day->ms "2023-01-01")
               :return 0.10}
              {:day "2023-01-15"
               :time-ms (day->ms "2023-01-15")
               :return -0.05}
              {:day "2023-02-01"
               :time-ms (day->ms "2023-02-01")
               :return -0.20}]
        baseline (metrics/compute-performance-metrics {:strategy-daily-rows rows
                                                       :rf 0
                                                       :periods-per-year 365})
        with-override (metrics/compute-performance-metrics {:strategy-daily-rows rows
                                                            :rf 0
                                                            :periods-per-year 365
                                                            :cagr-years 1})]
    (is (nil? (:cagr baseline)))
    (is (nil? (:cagr with-override)))
    (is (= :suppressed (get-in baseline [:metric-status :cagr])))
    (is (= :core-gate-failed (get-in baseline [:metric-reason :cagr])))))

(deftest compute-performance-metrics-window-boundaries-use-timestamp-anchors-test
  (let [rows [{:day "2024-03-30"
               :time-ms (.getTime (js/Date. "2024-03-30T18:00:00.000Z"))
               :return 0.10}
              {:day "2024-06-30"
               :time-ms (.getTime (js/Date. "2024-06-30T12:00:00.000Z"))
               :return 0.05}]
        metrics* (metrics/compute-performance-metrics {:strategy-daily-rows rows
                                                       :rf 0
                                                       :periods-per-year 365})]
    (is (approx= (:m3 metrics*) 0.155 1e-12))))

(deftest compute-performance-metrics-var-and-cvar-follow-quantstats-report-sign-test
  (let [returns [0.01 0.012 0.011 0.013 0.009]
        metrics* (metrics/compute-performance-metrics {:strategy-daily-rows (fixture-daily-rows returns)
                                                       :rf 0
                                                       :periods-per-year 365
                                                       :quality-gates {:daily-min-points 1
                                                                       :daily-min-coverage 0
                                                                       :daily-max-missing-streak 999
                                                                       :core-min-intervals 1
                                                                       :core-min-span-days 0}})
        raw-var (metrics/value-at-risk returns)
        raw-cvar (metrics/expected-shortfall returns)]
    (is (number? raw-var))
    (is (number? raw-cvar))
    (is (approx= (:daily-var metrics*) (- (js/Math.abs raw-var)) 1e-12))
    (is (approx= (:expected-shortfall metrics*) (- (js/Math.abs raw-cvar)) 1e-12))
    (is (neg? (:daily-var metrics*)))
    (is (neg? (:expected-shortfall metrics*)))))

(deftest compute-performance-metrics-emits-quality-diagnostics-and-gates-test
  (let [returns (vec (concat (repeat 14 0.01)
                             (repeat 14 -0.005)))
        metrics* (metrics/compute-performance-metrics {:strategy-daily-rows (fixture-daily-rows returns)
                                                       :rf 0
                                                       :periods-per-year 365})
        quality (:quality metrics*)]
    (is (map? quality))
    (is (number? (:interval-count quality)))
    (is (number? (:span-days quality)))
    (is (contains? quality :core-min?))
    (is (contains? quality :daily-min?))
    (is (contains? quality :drawdown-reliable?))))

(deftest compute-performance-metrics-keeps-daily-horizon-metrics-as-low-confidence-when-coverage-gates-fail-test
  (let [rows [[(day->ms "2024-01-01") 0]
              [(day->ms "2024-01-15") 5]
              [(day->ms "2024-01-29") 3]
              [(day->ms "2024-02-12") 9]
              [(day->ms "2024-02-26") 8]
              [(day->ms "2024-03-11") 12]
              [(day->ms "2024-03-25") 10]
              [(day->ms "2024-04-08") 15]
              [(day->ms "2024-04-22") 12]
              [(day->ms "2024-05-06") 18]
              [(day->ms "2024-05-20") 16]]
        metrics* (metrics/compute-performance-metrics {:strategy-cumulative-rows rows
                                                       :rf 0
                                                       :periods-per-year 365})]
    (is (number? (:omega metrics*)))
    (is (number? (:gain-pain-ratio metrics*)))
    (is (number? (:prob-sharpe-ratio metrics*)))
    (is (= :low-confidence (get-in metrics* [:metric-status :omega])))
    (is (= :daily-coverage-gate-failed (get-in metrics* [:metric-reason :omega])))))

(deftest compute-performance-metrics-parity-test
  (let [metrics* (metrics/compute-performance-metrics
                  {:strategy-daily-rows (fixture-daily-rows quantstats-returns)
                   :benchmark-daily-rows (fixture-daily-rows quantstats-benchmark)
                   :rf 0
                   :periods-per-year 252})]
    (is (approx= (:cumulative-return metrics*) 0.05288342670115531 1e-12))
    (is (nil? (:volatility-ann metrics*)))
    (is (nil? (:sharpe metrics*)))
    (is (nil? (:sortino metrics*)))
    (is (nil? (:r2 metrics*)))
    (is (nil? (:information-ratio metrics*)))
    (is (= :suppressed (get-in metrics* [:metric-status :volatility-ann])))
    (is (= :core-gate-failed (get-in metrics* [:metric-reason :volatility-ann])))
    (is (= :suppressed (get-in metrics* [:metric-status :r2])))
    (is (= :benchmark-coverage-gate-failed (get-in metrics* [:metric-reason :r2])))
    (is (approx= (:mtd metrics*) 0.05288342670115531 1e-12))
    (is (approx= (:ytd metrics*) 0.05288342670115531 1e-12))))
