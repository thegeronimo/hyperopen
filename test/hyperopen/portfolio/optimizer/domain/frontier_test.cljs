(ns hyperopen.portfolio.optimizer.domain.frontier-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.domain.frontier :as frontier]))

(deftest select-frontier-point-supports-v1-objectives-test
  (let [points [{:id :low-risk
                 :expected-return 0.05
                 :volatility 0.1}
                {:id :balanced
                 :expected-return 0.12
                 :volatility 0.2}
                {:id :high-return
                 :expected-return 0.2
                 :volatility 0.5}]]
    (is (= :low-risk
           (:id (frontier/select-frontier-point points
                                                {:kind :minimum-variance}))))
    (is (= :balanced
           (:id (frontier/select-frontier-point points
                                                {:kind :max-sharpe
                                                 :risk-free-rate 0.02}))))
    (is (= :balanced
           (:id (frontier/select-frontier-point points
                                                {:kind :target-return
                                                 :target-return 0.1}))))
    (is (= :balanced
           (:id (frontier/select-frontier-point points
                                                {:kind :target-volatility
                                                 :target-volatility 0.22}))))))

(deftest efficient-frontier-sorts-points-by-volatility-and-removes-dominated-points-test
  (let [points (frontier/efficient-frontier
                [{:id :dominated
                  :expected-return 0.04
                  :volatility 0.2}
                 {:id :low-risk
                  :expected-return 0.05
                  :volatility 0.1}
                 {:id :balanced
                  :expected-return 0.12
                  :volatility 0.2}])]
    (is (= [:low-risk :balanced] (mapv :id points)))))
