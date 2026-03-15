(ns hyperopen.views.chart.d3.model-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.chart.d3.model :as model]))

(deftest points->pixel-points-maps-normalized-points-to-host-size-test
  (is (= [{:time-ms 1
           :value 5
           :x-ratio 0
           :y-ratio 1
           :x 0
           :y 180}
          {:time-ms 2
           :value 10
           :x-ratio 0.25
           :y-ratio 0.75
           :x 100
           :y 135}
          {:time-ms 3
           :value 15
           :x-ratio 1
           :y-ratio 0
           :x 400
           :y 0}]
         (model/points->pixel-points 400
                                     180
                                     [{:time-ms 1 :value 5 :x-ratio 0 :y-ratio 1}
                                      {:time-ms 2 :value 10 :x-ratio 0.25 :y-ratio 0.75}
                                      {:time-ms 3 :value 15 :x-ratio 1 :y-ratio 0}]))))

(deftest hover-index-clamps-to-the-nearest-valid-point-test
  (is (= 0 (model/hover-index 90 100 320 5)))
  (is (= 2 (model/hover-index 260 100 320 5)))
  (is (= 4 (model/hover-index 999 100 320 5)))
  (is (= 0 (model/hover-index 120 100 320 1)))
  (is (nil? (model/hover-index 120 100 0 5))))

(deftest tooltip-layout-switches-sides-and-clamps-vertical-position-test
  (is (= {:left-px 80
          :top-px 19.2
          :right-side? false}
         (model/tooltip-layout 400 240 {:x-ratio 0.2
                                        :y-ratio 0.01})))
  (is (= {:left-px 320
          :top-px 218.4
          :right-side? true}
         (model/tooltip-layout 400 240 {:x-ratio 0.8
                                        :y-ratio 0.99}))))

(deftest split-area-helpers-clamp-baseline-and-detect-area-modes-test
  (is (= 0 (model/positive-clip-height 240 -1)))
  (is (= 120 (model/positive-clip-height 240 0.5)))
  (is (= 240 (model/positive-clip-height 240 4)))
  (is (= :none (model/area-type {})))
  (is (= :solid (model/area-type {:area-fill "rgba(1, 2, 3, 0.2)"})))
  (is (= :split-zero
         (model/area-type {:area-positive-fill "rgba(1, 2, 3, 0.2)"
                           :area-negative-fill "rgba(4, 5, 6, 0.2)"
                           :zero-y-ratio 0.4}))))
