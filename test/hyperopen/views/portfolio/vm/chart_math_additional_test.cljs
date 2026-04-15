(ns hyperopen.views.portfolio.vm.chart-math-additional-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio.vm.chart-math :as vm-chart-math]))

(defn- approx=
  [left right]
  (< (js/Math.abs (- left right)) 1e-12))

(deftest chart-domain-and-normalization-helpers-cover-degenerate-branches-test
  (is (= 1 (vm-chart-math/non-zero-span 0 0)))
  (is (= 3 (vm-chart-math/non-zero-span 1 4)))
  (is (= [-1 1]
         (vm-chart-math/normalize-degenerate-domain 0 0)))
  (is (= [95 105]
         (vm-chart-math/normalize-degenerate-domain 100 100)))
  (is (= {:min 0
          :max 3
          :step 1}
         (vm-chart-math/chart-domain [])))
  (is (= {:min 4
          :max 6
          :step (/ 2 3)}
         (vm-chart-math/chart-domain [5 5])))
  (is (= {:min 1
          :max 3
          :step (/ 2 3)}
         (vm-chart-math/chart-domain [1 2 3]))))

(deftest chart-y-ticks-format-and-axis-kind-cover-branches-test
  (is (= [{:value 4 :y-ratio 0}
          {:value 3 :y-ratio 0.25}
          {:value 2 :y-ratio 0.5}
          {:value 0 :y-ratio 1}]
         (vm-chart-math/chart-y-ticks {:min 0 :max 4 :step 1})))
  (let [ticks (vm-chart-math/chart-y-ticks {:min 0 :max 1 :step nil})]
    (is (= 4 (count ticks)))
    (is (= 1 (get-in ticks [0 :value])))
    (is (approx= (/ 2 3) (get-in ticks [1 :value])))
    (is (approx= (/ 1 3) (get-in ticks [2 :value])))
    (is (= 0 (get-in ticks [3 :value])))
    (is (= 0 (get-in ticks [0 :y-ratio])))
    (is (approx= (/ 1 3) (get-in ticks [1 :y-ratio])))
    (is (approx= (/ 2 3) (get-in ticks [2 :y-ratio])))
    (is (= 1 (get-in ticks [3 :y-ratio]))))
  (is (= 1.25 (vm-chart-math/format-svg-number 1.25)))
  (is (= 2 (vm-chart-math/format-svg-number 2.0000)))
  (is (= :percent (vm-chart-math/chart-axis-kind :returns)))
  (is (= :number (vm-chart-math/chart-axis-kind :account-value)))
  (is (= :number (vm-chart-math/chart-axis-kind :pnl)))
  (is (= :number (vm-chart-math/chart-axis-kind :anything-else))))

(deftest normalize-chart-points-and-hover-index-cover-clamp-paths-test
  (is (= [{:time-ms 1 :value 10 :x-ratio 0 :y-ratio 1}
          {:time-ms 2 :value 20 :x-ratio 1 :y-ratio 0}]
         (vm-chart-math/normalize-chart-points [{:time-ms 1 :value 10}
                                                {:time-ms 2 :value 20}]
                                               {:min 10 :max 20})))
  (is (= [{:time-ms 1 :value 5 :x-ratio 0 :y-ratio 0}
          {:time-ms 2 :value 5 :x-ratio 1 :y-ratio 0}]
         (vm-chart-math/normalize-chart-points [{:time-ms 1 :value 5}
                                                {:time-ms 2 :value 5}]
                                               {:min 5 :max 5})))
  (is (nil? (vm-chart-math/normalize-hover-index nil 4)))
  (is (nil? (vm-chart-math/normalize-hover-index 0.5 0)))
  (is (= 0 (vm-chart-math/normalize-hover-index -1 4)))
  (is (= 0 (vm-chart-math/normalize-hover-index 0.6 4)))
  (is (= 2 (vm-chart-math/normalize-hover-index 2 4)))
  (is (= 3 (vm-chart-math/normalize-hover-index 99 4))))

(deftest normalize-chart-points-uses-shared-time-domain-when-series-have-real-timestamps-test
  (let [time-domain {:min 1000
                     :max 5000}
        strategy-points (vm-chart-math/normalize-chart-points [{:time-ms 4000
                                                                :has-time-ms? true
                                                                :value 10}
                                                               {:time-ms 5000
                                                                :has-time-ms? true
                                                                :value 20}]
                                                              {:min 10 :max 20}
                                                              time-domain)
        benchmark-points (vm-chart-math/normalize-chart-points [{:time-ms 1000
                                                                 :has-time-ms? true
                                                                 :value 0}
                                                                {:time-ms 3000
                                                                 :has-time-ms? true
                                                                 :value 5}
                                                                {:time-ms 5000
                                                                 :has-time-ms? true
                                                                 :value 10}]
                                                               {:min 0 :max 10}
                                                               time-domain)]
    (is (= [0.75 1]
           (mapv :x-ratio strategy-points)))
    (is (= [0 0.5 1]
           (mapv :x-ratio benchmark-points)))))
