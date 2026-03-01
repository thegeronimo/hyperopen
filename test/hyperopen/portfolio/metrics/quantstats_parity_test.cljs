(ns hyperopen.portfolio.metrics.quantstats-parity-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.metrics :as metrics]
            [hyperopen.portfolio.metrics.test-utils :refer [approx= fixture-daily-rows quantstats-returns quantstats-benchmark]]))

(deftest cagr-years-override-matches-compounded-growth-over-explicit-span-test
  (let [returns [0.10 -0.05 -0.20]
        cumulative (metrics/comp returns)]
    (is (approx= cumulative -0.164 1e-12))
    (is (approx= (metrics/cagr returns {:periods-per-year 365
                                        :compounded true
                                        :years 1})
                 cumulative
                 1e-12))))

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