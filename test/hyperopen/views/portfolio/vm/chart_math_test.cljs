(ns hyperopen.views.portfolio.vm.chart-math-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.portfolio.vm.chart-math :as vm-chart-math]))

(deftest portfolio-vm-chart-line-path-uses-direct-segments-test
  (let [points [{:x-ratio 0
                 :y-ratio 1}
                {:x-ratio 0.5
                 :y-ratio 0.25}
                {:x-ratio 1
                 :y-ratio 0}]
        path (vm-chart-math/chart-line-path points)]
    (is (= "M 0 100 L 50 25 L 100 0" path))))

(deftest portfolio-vm-chart-line-path-extends-single-point-to-right-edge-test
  (let [points [{:x-ratio 0
                 :y-ratio 0.4}]
        path (vm-chart-math/chart-line-path points)]
    (is (= "M 0 40 L 100 40" path))))

(deftest portfolio-vm-chart-y-axis-uses-readable-step-ticks-test
  (let [domain-min -0.05
        domain-max 0.15
        ticks (vm-chart-math/chart-y-ticks {:min domain-min :max domain-max :step nil})]
    (is (= 4 (count ticks)))
    (is (= 0.15 (get-in ticks [0 :value])))
    (is (< (js/Math.abs (- 0.08333333333333334 (get-in ticks [1 :value]))) 1e-12))
    (is (< (js/Math.abs (- 0.016666666666666677 (get-in ticks [2 :value]))) 1e-12))
    (is (= -0.05 (get-in ticks [3 :value])))
    (is (= [0 (/ 1 3) (/ 2 3) 1]
           (mapv :y-ratio ticks)))))
