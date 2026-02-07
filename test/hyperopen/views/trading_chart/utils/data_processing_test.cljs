(ns hyperopen.views.trading-chart.utils.data-processing-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.trading-chart.utils.data-processing :as dp]))

(deftest process-candle-data-test
  (let [raw [{:t 2000 :o "10" :h "12" :l "9" :c "11" :v "5"}
             {:t 1000 :o "9" :h "11" :l "8" :c "10" :v "4"}
             {:bad "row"}]
        processed (dp/process-candle-data raw)]
    (testing "filters invalid rows and sorts by time"
      (is (= 2 (count processed)))
      (is (= [1 2] (mapv :time processed))))
    (testing "parses OHLCV numeric fields"
      (is (= 9 (-> processed first :open)))
      (is (= 11 (-> processed first :high)))
      (is (= 8 (-> processed first :low)))
      (is (= 10 (-> processed first :close)))
      (is (= 4 (-> processed first :volume))))))

(deftest process-volume-data-test
  (let [volume-data (dp/process-volume-data [{:time 1 :open 10 :close 12 :volume 100}
                                             {:time 2 :open 12 :close 11 :volume 90}])]
    (is (= [{:time 1 :value 100 :color "#10b981"}
            {:time 2 :value 90 :color "#ef4444"}]
           (vec volume-data)))))

(deftest update-last-candle-test
  (testing "updates existing candle in same bucket"
    (let [candles [{:time 10 :open 100 :high 101 :low 99 :close 100 :volume 5}]
          updated (dp/update-last-candle candles 10 103 2)]
      (is (= 1 (count updated)))
      (is (= 103 (-> updated last :close)))
      (is (= 103 (-> updated last :high)))
      (is (= 99 (-> updated last :low)))
      (is (= 7 (-> updated last :volume)))))
  (testing "creates new candle for new bucket"
    (let [candles [{:time 10 :open 100 :high 101 :low 99 :close 100 :volume 5}]
          updated (dp/update-last-candle candles 11 105 1)]
      (is (= 2 (count updated)))
      (is (= 11 (-> updated last :time)))
      (is (= 105 (-> updated last :open))))))

(deftest formatters-test
  (is (= "12.34" (dp/format-price 12.34)))
  (is (= "0.123400" (dp/format-price 0.1234)))
  (is (= "999" (dp/format-volume 999)))
  (is (= "1.5K" (dp/format-volume 1500)))
  (is (= "2.0M" (dp/format-volume 2000000)))
  (is (= "3.0B" (dp/format-volume 3000000000))))
