(ns hyperopen.views.vaults.detail.chart-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.vaults.detail.chart :as chart]))

(deftest build-chart-model-expands-degenerate-domain-test
  (let [model (chart/build-chart-model {:selected-series :returns
                                        :raw-series [{:id :strategy
                                                      :raw-points [{:time-ms 1 :value 42}
                                                                   {:time-ms 2 :value 42}]}]
                                        :hover-index nil})
        domain (:domain model)
        y-ticks (:y-ticks model)]
    (is (number? (:min domain)))
    (is (number? (:max domain)))
    (is (< (:min domain) (:max domain)))
    (is (= 4 (count y-ticks)))
    (is (= 0 (get-in y-ticks [0 :y-ratio])))
    (is (= 1 (get-in y-ticks [3 :y-ratio])))))

(deftest build-chart-model-extends-single-point-line-path-test
  (let [model (chart/build-chart-model {:selected-series :returns
                                        :raw-series [{:id :strategy
                                                      :raw-points [{:time-ms 1 :value 5}]}]
                                        :hover-index 0})
        path (:path model)]
    (is (= 1 (count (:points model))))
    (is (string? path))
    (is (str/includes? path "L 100 "))))

(deftest build-chart-model-adds-pnl-zero-baseline-area-metadata-test
  (let [model (chart/build-chart-model {:selected-series :pnl
                                        :raw-series [{:id :strategy
                                                      :raw-points [{:time-ms 1 :value -2}
                                                                   {:time-ms 2 :value 2}]}]
                                        :hover-index 1})
        strategy-series (:strategy-series model)]
    (is (seq (:area-path strategy-series)))
    (is (= "rgba(22, 214, 161, 0.24)" (:area-positive-fill strategy-series)))
    (is (= "rgba(237, 112, 136, 0.24)" (:area-negative-fill strategy-series)))
    (is (= 0.5 (:zero-y-ratio strategy-series)))))

(deftest build-chart-model-clamps-hover-index-test
  (let [base-input {:selected-series :returns
                    :raw-series [{:id :strategy
                                  :raw-points [{:time-ms 1 :value 0}
                                               {:time-ms 2 :value 10}
                                               {:time-ms 3 :value 20}]}]}
        high-hover (chart/build-chart-model (assoc base-input :hover-index 99))
        low-hover (chart/build-chart-model (assoc base-input :hover-index -10))]
    (is (= 2 (get-in high-hover [:hover :index])))
    (is (= 20 (get-in high-hover [:hover :point :value])))
    (is (= 0 (get-in low-hover [:hover :index])))
    (is (= 0 (get-in low-hover [:hover :point :value])))))

(deftest build-chart-model-can-omit-svg-paths-while-preserving-d3-area-metadata-test
  (let [model (chart/build-chart-model {:selected-series :pnl
                                        :raw-series [{:id :strategy
                                                      :raw-points [{:time-ms 1 :value -2}
                                                                   {:time-ms 2 :value 2}]}]
                                        :hover-index 1
                                        :include-svg-paths? false})
        strategy-series (:strategy-series model)]
    (is (nil? (:path model)))
    (is (nil? (:path strategy-series)))
    (is (nil? (:area-path strategy-series)))
    (is (= "rgba(22, 214, 161, 0.24)" (:area-positive-fill strategy-series)))
    (is (= "rgba(237, 112, 136, 0.24)" (:area-negative-fill strategy-series)))
    (is (= 0.5 (:zero-y-ratio strategy-series)))))
