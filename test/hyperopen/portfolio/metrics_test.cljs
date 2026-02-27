(ns hyperopen.portfolio.metrics-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.metrics :as metrics]))

(defn- approx=
  [left right tolerance]
  (and (number? left)
       (number? right)
       (<= (js/Math.abs (- left right)) tolerance)))

(def ^:private quantstats-returns
  [0.01
   -0.02
   0.015
   0
   -0.005
   0.03
   -0.01
   0.02
   -0.015
   0.005
   0.012
   -0.008
   0.004
   -0.003
   0.018])

(def ^:private quantstats-benchmark
  [0.008
   -0.015
   0.01
   -0.002
   0.001
   0.02
   -0.012
   0.011
   -0.01
   0.004
   0.009
   -0.006
   0.003
   -0.002
   0.012])

(def ^:private day-ms
  (* 24 60 60 1000))

(def ^:private fixture-start-ms
  (.getTime (js/Date. "2024-01-01T00:00:00.000Z")))

(defn- fixture-daily-rows
  [returns]
  (mapv (fn [idx return]
          (let [time-ms (+ fixture-start-ms (* idx day-ms))]
            {:day (subs (.toISOString (js/Date. time-ms)) 0 10)
             :time-ms time-ms
             :return return}))
        (range (count returns))
        returns))

(defn- day->ms
  [day]
  (.getTime (js/Date. (str day "T00:00:00.000Z"))))

(deftest returns-history-rows-implies-cashflows-from-account-and-pnl-deltas-test
  (let [summary {:accountValueHistory [[1 4]
                                       [2 205]
                                       [3 204]
                                       [4 205]]
                 :pnlHistory [[1 0]
                              [2 0]
                              [3 -1]
                              [4 0]]}
        rows (metrics/returns-history-rows {} summary :all)
        values (mapv second rows)]
    (is (= [1 2 3 4]
           (mapv first rows)))
    (is (approx= 0 (nth values 0) 1e-12))
    (is (approx= 0 (nth values 1) 1e-12))
    (is (approx= -0.48780487804878053 (nth values 2) 1e-12))
    (is (approx= 0 (nth values 3) 1e-12))))

(deftest returns-history-rows-uses-shared-account-and-pnl-timestamps-test
  (let [summary {:accountValueHistory [[1 100]
                                       [2 120]
                                       [4 140]]
                 :pnlHistory [[1 0]
                              [3 10]
                              [4 20]]}
        rows (metrics/returns-history-rows {} summary :all)]
    (is (= [1 4]
           (mapv first rows)))
    (is (approx= 0 (second (first rows)) 1e-12))
    (is (approx= 18.181818181818183 (second (second rows)) 1e-12))))

(deftest returns-history-rows-guards-invalid-dietz-denominator-test
  (let [summary {:accountValueHistory [[1 10]
                                       [2 1]
                                       [3 2]]
                 :pnlHistory [[1 0]
                              [2 20]
                              [3 21]]}
        rows (metrics/returns-history-rows {} summary :all)]
    (is (= [1 2 3]
           (mapv first rows)))
    (is (= [0 0 100]
           (mapv second rows)))))

(deftest daily-compounded-returns-builds-canonical-daily-series-test
  (let [rows [[1000 0]
              [2000 10]
              [3000 21]]
        interval-returns (metrics/cumulative-percent-rows->interval-returns rows)
        daily-returns (metrics/daily-compounded-returns rows)]
    (testing "interval return extraction"
      (is (= [2000 3000]
             (mapv :time-ms interval-returns)))
      (is (approx= 0.1 (get-in interval-returns [0 :return]) 1e-12))
      (is (approx= 0.1 (get-in interval-returns [1 :return]) 1e-12)))
    (testing "daily compounding"
      (is (= 1 (count daily-returns)))
      (is (= "1970-01-01"
             (get-in daily-returns [0 :day])))
      (is (approx= 0.21 (get-in daily-returns [0 :return]) 1e-12)))))

(deftest cagr-years-override-matches-compounded-growth-over-explicit-span-test
  (let [returns [0.10 -0.05 -0.20]
        cumulative (metrics/comp returns)]
    (is (approx= cumulative -0.164 1e-12))
    (is (approx= (metrics/cagr returns {:periods-per-year 365
                                        :compounded true
                                        :years 1})
                 cumulative
                 1e-12))))

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

(deftest compute-performance-metrics-suppresses-daily-horizon-metrics-when-coverage-gates-fail-test
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
    (is (nil? (:omega metrics*)))
    (is (nil? (:gain-pain-ratio metrics*)))
    (is (nil? (:prob-sharpe-ratio metrics*)))
    (is (= :suppressed (get-in metrics* [:metric-status :omega])))
    (is (= :daily-coverage-gate-failed (get-in metrics* [:metric-reason :omega])))))

(deftest quantstats-base-case-coverage-alignment-test
  (testing "single-return and empty-input behavior"
    (is (approx= (metrics/comp [0.01]) 0.01 1e-12))
    (is (nil? (metrics/sharpe []))))
  (testing "time in market and win rate extremes"
    (is (approx= (metrics/time-in-market [0.01 -0.01 0 0.02]) 0.75 1e-12))
    (is (approx= (metrics/time-in-market [0 0 0]) 0 1e-12))
    (is (approx= (metrics/win-rate [0.01 0.02 0.03]) 1 1e-12))
    (is (approx= (metrics/win-rate [-0.01 -0.02 -0.03]) 0 1e-12)))
  (testing "risk ratios and tail risk relationships"
    (let [sharpe-no-rf (metrics/sharpe quantstats-returns {:rf 0
                                                            :periods-per-year 252
                                                            :annualize false})
          sharpe-with-rf (metrics/sharpe quantstats-returns {:rf 0.02
                                                              :periods-per-year 252
                                                              :annualize false})
          sortino* (metrics/sortino quantstats-returns {:rf 0
                                                        :periods-per-year 252
                                                        :annualize false})
          var* (metrics/value-at-risk quantstats-returns {:sigma 1
                                                          :confidence 0.95})
          cvar* (metrics/expected-shortfall quantstats-returns {:sigma 1
                                                                :confidence 0.95})]
      (is (number? sharpe-no-rf))
      (is (number? sharpe-with-rf))
      (is (< sharpe-with-rf sharpe-no-rf))
      (is (not= sharpe-no-rf sortino*))
      (is (neg? var*))
      (is (<= cvar* var*))))
  (testing "benchmark and drawdown constraints"
    (let [r2 (metrics/r-squared quantstats-returns quantstats-benchmark)
          drawdowns (metrics/to-drawdown-series quantstats-returns)]
      (is (number? r2))
      (is (<= 0 r2 1))
      (is (= (count quantstats-returns) (count drawdowns)))
      (is (every? (fn [value]
                    (and (number? value)
                         (<= value 0)))
                  drawdowns)))))

(deftest quantstats-ratio-parity-test
  (let [returns quantstats-returns]
    (is (approx= (metrics/comp returns) 0.05288342670115531 1e-12))
    (is (approx= (metrics/time-in-market returns) 0.94 1e-12))
    (is (approx= (metrics/cagr returns {:periods-per-year 252
                                        :compounded true})
                 1.3767793455299793
                 1e-12))
    (is (approx= (metrics/volatility returns {:periods-per-year 252
                                              :annualize false})
                 0.014065493067720565
                 1e-12))
    (is (approx= (metrics/volatility returns {:periods-per-year 252
                                              :annualize true})
                 0.22328278034814958
                 1e-12))
    (is (approx= (metrics/sharpe returns {:rf 0
                                          :periods-per-year 252
                                          :annualize true})
                 3.9877683295221424
                 1e-9))
    (is (approx= (metrics/smart-sharpe returns {:rf 0
                                                :periods-per-year 252
                                                :annualize true})
                 1.9340613359749181
                 1e-9))
    (is (approx= (metrics/sortino returns {:rf 0
                                           :periods-per-year 252
                                           :annualize true})
                 7.572348494713835
                 1e-9))
    (is (approx= (metrics/smart-sortino returns {:rf 0
                                                 :periods-per-year 252
                                                 :annualize true})
                 3.6725770496073102
                 1e-9))
    (is (approx= (metrics/probabilistic-sharpe-ratio returns {:rf 0
                                                              :periods-per-year 252
                                                              :annualize false})
                 0.8333382188139211
                 1e-8))
    (is (approx= (metrics/omega returns {:rf 0
                                         :required-return 0
                                         :periods-per-year 252})
                 1.8688524590163935
                 1e-12))))

(deftest quantstats-risk-and-distribution-parity-test
  (let [returns quantstats-returns
        daily-rows (fixture-daily-rows returns)]
    (is (approx= (metrics/max-drawdown returns) -0.020000000000000018 1e-12))
    (is (approx= (metrics/calmar returns {:periods-per-year 252})
                 68.8389672764989
                 1e-9))
    (is (approx= (metrics/skew returns) 0.1203718222915359 1e-12))
    (is (approx= (metrics/kurtosis returns) -0.6376206695631859 1e-12))
    (is (approx= (metrics/expected-return daily-rows {:period :day
                                                      :compounded true})
                 0.0034414095078989515
                 1e-12))
    (is (approx= (metrics/expected-return daily-rows {:period :month
                                                      :compounded true})
                 0.05288342670115531
                 1e-12))
    (is (approx= (metrics/expected-return daily-rows {:period :year
                                                      :compounded true})
                 0.05288342670115531
                 1e-12))
    (is (approx= (metrics/kelly-criterion returns)
                 0.26566416040100244
                 1e-12))
    (is (approx= (metrics/risk-of-ruin returns)
                 3.4350142529375444e-9
                 1e-18))
    (is (approx= (metrics/value-at-risk returns {:sigma 1
                                                 :confidence 0.95})
                 -0.019602343953967625
                 1e-9))
    (is (approx= (metrics/expected-shortfall returns {:sigma 1
                                                      :confidence 0.95})
                 -0.02
                 1e-12))))

(deftest quantstats-trade-shape-parity-test
  (let [returns quantstats-returns
        daily-rows (fixture-daily-rows returns)]
    (is (= 2 (metrics/consecutive-wins returns)))
    (is (= 1 (metrics/consecutive-losses returns)))
    (is (approx= (metrics/gain-to-pain-ratio daily-rows :day)
                 0.8688524590163932
                 1e-12))
    (is (nil? (metrics/gain-to-pain-ratio daily-rows :month)))
    (is (approx= (metrics/payoff-ratio returns)
                 1.4016393442622952
                 1e-12))
    (is (approx= (metrics/profit-factor returns)
                 1.8688524590163933
                 1e-12))
    (is (approx= (metrics/common-sense-ratio returns)
                 2.605067064083457
                 1e-12))
    (is (approx= (metrics/cpc-index returns)
                 1.4968326486735515
                 1e-12))
    (is (approx= (metrics/tail-ratio returns)
                 1.3939393939393936
                 1e-12))
    (is (approx= (metrics/outlier-win-ratio returns)
                 2.257894736842105
                 1e-12))
    (is (approx= (metrics/outlier-loss-ratio returns)
                 1.8983606557377048
                 1e-12))))

(deftest quantstats-benchmark-parity-test
  (is (approx= (metrics/r-squared quantstats-returns quantstats-benchmark)
               0.9564942673385227
               1e-12))
  (is (approx= (metrics/information-ratio quantstats-returns quantstats-benchmark)
               0.3050013278569594
               1e-12)))

(deftest drawdown-details-parity-test
  (let [details (metrics/drawdown-details (fixture-daily-rows quantstats-returns))
        stats (metrics/max-drawdown-stats (fixture-daily-rows quantstats-returns))]
    (is (= 4 (count details)))
    (is (= {:start "2024-01-02"
            :valley "2024-01-02"
            :end "2024-01-05"
            :days 4}
           (select-keys (first details) [:start :valley :end :days])))
    (is (approx= (:max-drawdown (first details))
                 -2.0000000000000018
                 1e-12))
    (is (= "2024-01-02" (:max-dd-date stats)))
    (is (= "2024-01-02" (:max-dd-period-start stats)))
    (is (= "2024-01-05" (:max-dd-period-end stats)))
    (is (= 4 (:longest-dd-days stats)))
    (is (approx= (:max-drawdown stats)
                 -0.020000000000000018
                 1e-12))))

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
