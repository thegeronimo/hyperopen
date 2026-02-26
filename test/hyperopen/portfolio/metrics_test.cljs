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

(deftest returns-history-rows-adjusts-for-cashflows-test
  (let [state {:wallet {:address "0xabc"}
               :portfolio {:ledger-updates [{:time 2
                                             :hash "0xflow"
                                             :delta {:type "deposit"
                                                     :usdc "201"}}]}
               :orders {:ledger []}}
        summary {:accountValueHistory [[1 4]
                                       [2 205]
                                       [3 204]
                                       [4 205]]}
        rows (metrics/returns-history-rows state summary :all)
        values (mapv second rows)]
    (is (= [1 2 3 4]
           (mapv first rows)))
    (is (approx= 0 (nth values 0) 1e-12))
    (is (approx= 0 (nth values 1) 1e-12))
    (is (approx= -0.48780487804878053 (nth values 2) 1e-12))
    (is (approx= 0 (nth values 3) 1e-12))))

(deftest returns-history-rows-treats-account-class-transfer-as-perps-flow-test
  (let [state {:wallet {:address "0xabc"}
               :portfolio {:ledger-updates [{:time 2
                                             :hash "0xperp"
                                             :delta {:type "accountClassTransfer"
                                                     :usdc "50"
                                                     :toPerp true}}]}
               :orders {:ledger []}}
        summary {:accountValueHistory [[1 100]
                                       [2 150]
                                       [3 150]]}
        rows (metrics/returns-history-rows state summary :perps)]
    (is (= [0 0 0]
           (mapv second rows)))))

(deftest returns-history-rows-keeps-distinct-same-hash-ledger-events-test
  (let [state {:wallet {:address "0xabc"}
               :portfolio {:ledger-updates [{:time 2
                                             :hash "0xshared"
                                             :delta {:type "deposit"
                                                     :usdc "100"}}]}
               :orders {:ledger [{:time 2
                                  :hash "0xshared"
                                  :delta {:type "withdraw"
                                          :usdc "50"
                                          :fee "0"}}]}}
        summary {:accountValueHistory [[1 100]
                                       [2 200]]}
        rows (metrics/returns-history-rows state summary :all)]
    (is (= [[1 0]
            [2 50]]
           rows))))

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
                   :periods-per-year 252
                   :compounded true})]
    (is (approx= (:cumulative-return metrics*) 0.05288342670115531 1e-12))
    (is (approx= (:volatility-ann metrics*) 0.22328278034814958 1e-12))
    (is (approx= (:sharpe metrics*) 3.9877683295221424 1e-9))
    (is (approx= (:sortino metrics*) 7.572348494713835 1e-9))
    (is (approx= (:r2 metrics*) 0.9564942673385227 1e-12))
    (is (approx= (:information-ratio metrics*) 0.3050013278569594 1e-12))
    (is (nil? (:gain-pain-1m metrics*)))
    (is (approx= (:mtd metrics*) 0.05288342670115531 1e-12))
    (is (approx= (:ytd metrics*) 0.05288342670115531 1e-12))))
