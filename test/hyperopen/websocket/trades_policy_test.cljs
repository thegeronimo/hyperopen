(ns hyperopen.websocket.trades-policy-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.websocket.trades-policy :as policy]))

(deftest parse-number-test
  (is (= 10 (policy/parse-number 10)))
  (is (= 10.25 (policy/parse-number "10.25")))
  (is (nil? (policy/parse-number "x"))))

(deftest time->ms-test
  (is (= 1700000000000 (policy/time->ms 1700000000000)))
  (is (= 1700000000000 (policy/time->ms 1700000000)))
  (is (= 1700000000000 (policy/time->ms "1700000000")))
  (is (nil? (policy/time->ms "nope"))))

(deftest normalize-trade-test
  (testing "normalizes mixed exchange key names"
    (is (= {:time-ms 1700000000000
            :price 101.5
            :size 0.75
            :coin "BTC"}
           (policy/normalize-trade {:t 1700000000
                                    :px "101.5"
                                    :sz "0.75"
                                    :coin "BTC"}))))
  (testing "size defaults to zero when missing"
    (is (= 0 (:size (policy/normalize-trade {:time 1700000000
                                             :price "100"}))))))

(deftest update-candle-test
  (let [candle {:t 1000 :o 100 :h 102 :l 99 :c 101 :v 10}
        updated (policy/update-candle candle 103 1.5)]
    (is (= 103 (:c updated)))
    (is (= 103 (:h updated)))
    (is (= 99 (:l updated)))
    (is (= 11.5 (:v updated)))))

(deftest upsert-candle-test
  (let [interval-ms 60000
        base [{:t 600000 :o 100 :h 101 :l 99 :c 100 :v 2}]]
    (testing "updates the current bucket candle"
      (let [updated (policy/upsert-candle base interval-ms {:time-ms 659999 :price 103 :size 1} 100)]
        (is (= 1 (count updated)))
        (is (= 103 (-> updated last :c)))
        (is (= 3 (-> updated last :v)))))
    (testing "appends next bucket and trims by max-count"
      (let [candles [{:t 600000 :o 100 :h 100 :l 100 :c 100 :v 1}
                     {:t 660000 :o 101 :h 101 :l 101 :c 101 :v 1}]
            updated (policy/upsert-candle candles interval-ms {:time-ms 720001 :price 102 :size 1} 2)]
        (is (= 2 (count updated)))
        (is (= 660000 (-> updated first :t)))
        (is (= 720000 (-> updated last :t)))))
    (testing "ignores out-of-order updates"
      (is (= base
             (policy/upsert-candle base interval-ms {:time-ms 540000 :price 90 :size 1} 100))))
    (testing "invalid input returns current candles"
      (is (= base
             (policy/upsert-candle base interval-ms {:time-ms nil :price 10 :size 1} 100))))))
