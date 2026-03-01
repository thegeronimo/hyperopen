(ns hyperopen.portfolio.metrics.history-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.metrics :as metrics]
            [hyperopen.portfolio.metrics.test-utils :refer [approx=]]))

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
    (is (= [0 200 500]
           (mapv second rows)))))

(deftest returns-history-rows-summary-helper-is-compatible-test
  (let [summary {:accountValueHistory [[1 100]
                                       [2 105]
                                       [3 110]]
                 :pnlHistory [[1 0]
                              [2 5]
                              [3 10]]}]
    (is (= (metrics/returns-history-rows-from-summary summary)
           (metrics/returns-history-rows {} summary :all)))))

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