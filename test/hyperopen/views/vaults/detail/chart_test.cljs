(ns hyperopen.views.vaults.detail.chart-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.vaults.detail.chart :as chart]))

(deftest build-chart-model-expands-degenerate-domain-test
  (let [model (chart/build-chart-model {:selected-series :returns
                                        :raw-series [{:id :strategy
                                                      :raw-points [{:time-ms 1 :value 42}
                                                                   {:time-ms 2 :value 42}]}]})
        domain (:domain model)
        y-ticks (:y-ticks model)]
    (is (number? (:min domain)))
    (is (number? (:max domain)))
    (is (< (:min domain) (:max domain)))
    (is (= 4 (count y-ticks)))
    (is (= 0 (get-in y-ticks [0 :y-ratio])))
    (is (= 1 (get-in y-ticks [3 :y-ratio])))))

(deftest build-chart-model-keeps-single-point-series-normalized-test
  (let [model (chart/build-chart-model {:selected-series :returns
                                        :raw-series [{:id :strategy
                                                      :raw-points [{:time-ms 1 :value 5}]}]})]
    (is (= 1 (count (:points model))))
    (is (= 0 (get-in model [:points 0 :x-ratio])))))

(deftest build-chart-model-adds-pnl-zero-baseline-area-metadata-test
  (let [model (chart/build-chart-model {:selected-series :pnl
                                        :raw-series [{:id :strategy
                                                      :raw-points [{:time-ms 1 :value -2}
                                                                   {:time-ms 2 :value 2}]}]})
        strategy-series (:strategy-series model)]
    (is (= "rgba(22, 214, 161, 0.24)" (:area-positive-fill strategy-series)))
    (is (= "rgba(237, 112, 136, 0.24)" (:area-negative-fill strategy-series)))
    (is (= 0.5 (:zero-y-ratio strategy-series)))))
