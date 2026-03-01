(ns hyperopen.views.portfolio.vm.chart-math-additional-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio.vm.chart-math :as vm-chart-math]))

(deftest chart-domain-and-normalization-helpers-cover-degenerate-branches-test
  (is (= 1 (vm-chart-math/non-zero-span 0)))
  (is (= 3 (vm-chart-math/non-zero-span 3)))
  (is (= [-1 1]
         (vm-chart-math/normalize-degenerate-domain 0 0)))
  (is (= [90 110]
         (vm-chart-math/normalize-degenerate-domain 100 100)))
  (is (= [-1 1]
         (vm-chart-math/chart-domain [])))
  (is (= [4.5 5.5]
         (vm-chart-math/chart-domain [5 5])))
  (is (= [1 3]
         (vm-chart-math/chart-domain [1 2 3]))))

(deftest chart-y-ticks-format-and-axis-kind-cover-branches-test
  (is (= [{:y 0} {:y 1} {:y 2} {:y 3} {:y 4}]
         (vm-chart-math/chart-y-ticks {:min 0 :max 4 :step 1})))
  (is (= [{:y 0} {:y 0.25} {:y 0.5} {:y 0.75} {:y 1}]
         (vm-chart-math/chart-y-ticks {:min 0 :max 1 :step nil})))
  (is (= "1.25" (vm-chart-math/format-svg-number 1.25)))
  (is (= "2" (vm-chart-math/format-svg-number 2.0000)))
  (is (= :percent (vm-chart-math/chart-axis-kind :returns)))
  (is (= :currency (vm-chart-math/chart-axis-kind :account-value)))
  (is (= :currency (vm-chart-math/chart-axis-kind :pnl)))
  (is (= :currency (vm-chart-math/chart-axis-kind :anything-else))))

(deftest normalize-chart-points-and-hover-index-cover-clamp-paths-test
  (is (= [{:time-ms 1 :value 10 :y 1}
          {:time-ms 2 :value 20 :y 0}]
         (vm-chart-math/normalize-chart-points [{:time-ms 1 :value 10}
                                                {:time-ms 2 :value 20}]
                                               {:min 10 :max 20})))
  (is (= [{:time-ms 1 :value 5 :y 1}
          {:time-ms 2 :value 5 :y 1}]
         (vm-chart-math/normalize-chart-points [{:time-ms 1 :value 5}
                                                {:time-ms 2 :value 5}]
                                               {:min 5 :max 5})))
  (is (nil? (vm-chart-math/normalize-hover-index nil 4)))
  (is (nil? (vm-chart-math/normalize-hover-index 0.5 0)))
  (is (= 0 (vm-chart-math/normalize-hover-index -1 4)))
  (is (= 2 (vm-chart-math/normalize-hover-index 0.6 4)))
  (is (= 3 (vm-chart-math/normalize-hover-index 2 4))))
