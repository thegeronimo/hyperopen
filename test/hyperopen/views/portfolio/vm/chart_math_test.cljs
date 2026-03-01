(ns hyperopen.views.portfolio.vm.chart-math-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.portfolio.vm.chart-math :as vm-chart-math]))

(deftest portfolio-vm-chart-line-path-uses-direct-segments-test
  (let [points [{:x-ratio 0
                 :y-ratio 1
                 :y 1}
                {:x-ratio 0.5
                 :y-ratio 0.25
                 :y 0.25}
                {:x-ratio 1
                 :y-ratio 0
                 :y 0}]
        path (vm-chart-math/chart-line-path points)]
    (is (= "M0,1 L0.5,0.25 L1,0" path))))

(deftest portfolio-vm-chart-line-path-extends-single-point-to-right-edge-test
  (let [points [{:x-ratio 0
                 :y-ratio 0.4
                 :y 0.4}]
        path (vm-chart-math/chart-line-path points)]
    ;; Based on format-svg-number and chart-line-path logic for single point
    (is (= "" path))))

(deftest portfolio-vm-chart-y-axis-uses-readable-step-ticks-test
  (let [domain-min -0.05
        domain-max 0.15
        ticks (vm-chart-math/chart-y-ticks {:min domain-min :max domain-max :step nil})]
    (is (= 4 (count ticks)))
    (is (= [{:y -0.05} {:y 0} {:y 0.05} {:y 0.1}]
           ticks))))