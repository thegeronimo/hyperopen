(ns hyperopen.views.trading-chart.utils.chart-interop.transforms-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.utils.chart-interop :as chart-interop]))

(deftest transform-data-for-heikin-ashi-computes-deterministic-candles-test
  (let [raw-candles [{:time 1 :open 10 :high 15 :low 8 :close 12}
                     {:time 2 :open 12 :high 16 :low 11 :close 14}]
        transformed (chart-interop/transform-data-for-heikin-ashi raw-candles)]
    (is (= 2 (count transformed)))
    (let [first-candle (first transformed)
          second-candle (second transformed)]
      (is (= {:time 1
              :open 11
              :high 15
              :low 8
              :close 11.25}
             first-candle))
      (is (= 11.125 (:open second-candle)))
      (is (= 13.25 (:close second-candle)))
      (is (= 16 (:high second-candle)))
      (is (= 11 (:low second-candle))))))

(deftest transform-data-for-columns-adds-directional-color-test
  (let [raw-candles [{:time 1 :open 10 :high 11 :low 9 :close 12}
                     {:time 2 :open 12 :high 13 :low 11 :close 10}]
        transformed (vec (chart-interop/transform-data-for-columns raw-candles))]
    (is (= [{:time 1 :value 12 :color "#26a69a"}
            {:time 2 :value 10 :color "#ef5350"}]
           transformed))))

(deftest transform-data-for-high-low-builds-floating-range-bars-test
  (let [raw-candles [{:time 1 :open 10 :high 16 :low 8 :close 12}
                     {:time 2 :open 12 :high 18 :low 10 :close 16}]
        transformed (vec (chart-interop/transform-data-for-high-low raw-candles))]
    (is (= [{:time 1 :open 8 :high 16 :low 8 :close 16}
            {:time 2 :open 10 :high 18 :low 10 :close 18}]
           transformed))))

