(ns hyperopen.portfolio.optimizer.domain.risk-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.domain.risk :as risk]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.0000001))

(deftest sample-covariance-aligns-instruments-and-annualizes-test
  (let [result (risk/estimate-risk-model
                {:risk-model {:kind :sample-covariance}
                 :periods-per-year 1
                 :history {:return-series-by-instrument {"A" [1 2 3]
                                                         "B" [2 4 6]}}})]
    (is (= :sample-covariance (:model result)))
    (is (= ["A" "B"] (:instrument-ids result)))
    (is (= [[1 2]
            [2 4]]
           (:covariance result)))
    (is (= [] (:warnings result)))))

(deftest ledoit-wolf-shrinkage-preserves-diagonal-and-shrinks-cross-covariance-test
  (let [result (risk/estimate-risk-model
                {:risk-model {:kind :ledoit-wolf
                              :shrinkage 0.5}
                 :periods-per-year 1
                 :history {:return-series-by-instrument {"A" [1 2 3]
                                                         "B" [2 4 6]}}})]
    (is (= :ledoit-wolf (:model result)))
    (is (= [[1 1]
            [1 4]]
           (:covariance result)))
    (is (= {:kind :diagonal
            :shrinkage 0.5}
           (:shrinkage result)))))

(deftest covariance-conditioning-reports-ill-conditioned-diagonal-range-test
  (let [summary (risk/covariance-conditioning [[1 0]
                                               [0 100]])]
    (is (near? 100 (:condition-number summary)))
    (is (= :ok (:status summary)))))
