(ns hyperopen.views.trading-chart.utils.indicators-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.trading-chart.utils.indicators :as indicators]))

(deftest calculate-sma-test
  (let [candles [{:time 1 :close 10}
                 {:time 2 :close 20}
                 {:time 3 :close 30}
                 {:time 4 :close 40}]
        result (vec (indicators/calculate-sma candles 2))]
    (testing "warmup entries are whitespace points"
      (is (= {:time 1} (nth result 0)))
      (is (= {:time 2} (nth result 1))))
    (testing "later entries include SMA values"
      (is (= {:time 3 :value 25} (nth result 2)))
      (is (= {:time 4 :value 35} (nth result 3))))))

(deftest available-indicators-test
  (is (= [{:id :sma
           :name "Simple Moving Average"
           :short-name "SMA"
           :description "Simple Moving Average indicator"
           :default-period 20
           :min-period 2
           :max-period 200}]
         (indicators/get-available-indicators))))

(deftest calculate-indicator-test
  (let [candles [{:time 1 :close 1}
                 {:time 2 :close 2}
                 {:time 3 :close 3}]]
    (is (= (indicators/calculate-sma candles 2)
           (indicators/calculate-indicator :sma candles {:period 2})))
    (is (nil? (indicators/calculate-indicator :unknown candles {})))))
