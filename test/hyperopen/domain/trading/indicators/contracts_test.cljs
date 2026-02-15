(ns hyperopen.domain.trading.indicators.contracts-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.domain.trading.indicators.contracts :as contracts]))

(deftest valid-indicator-input-test
  (let [ohlc-candle {:time 1 :open 100 :high 110 :low 95 :close 105}
        ohlcv-candle (assoc ohlc-candle :volume 1200)]
    (is (true? (contracts/valid-indicator-input? :sma [ohlc-candle] {})))
    (is (true? (contracts/valid-indicator-input? :sma [ohlc-candle] {:period 14})))
    (is (true? (contracts/valid-indicator-input? :sma [ohlc-candle] {:period "14"})))
    (is (true? (contracts/valid-indicator-input? :sma [ohlc-candle] {:period " 14.0 "})))
    (is (true? (contracts/valid-indicator-input? :sma [ohlc-candle] {:unknown 123})))
    (is (true? (contracts/valid-indicator-input? :on-balance-volume [ohlcv-candle] {})))
    (is (false? (contracts/valid-indicator-input? :unknown-indicator [ohlc-candle] {})))
    (is (false? (contracts/valid-indicator-input? [] [ohlc-candle] {})))
    (is (false? (contracts/valid-indicator-input? :sma nil {})))
    (is (false? (contracts/valid-indicator-input? :sma [1 2] {})))
    (is (false? (contracts/valid-indicator-input? :sma [ohlc-candle] [])))
    (is (false? (contracts/valid-indicator-input? :sma [ohlc-candle] {:period "abc"})))
    (is (false? (contracts/valid-indicator-input? :sma [ohlc-candle] {:period 10000})))
    (is (false? (contracts/valid-indicator-input? :atr [ohlc-candle] {:period 1})))
    (is (false? (contracts/valid-indicator-input? :on-balance-volume [ohlc-candle] {})))))

(deftest valid-indicator-result-test
  (let [ok-result {:type :supertrend
                   :pane :overlay
                   :series [{:id :up
                             :series-type :line
                             :values [nil 1.0]}
                            {:id :down
                             :series-type :line
                             :values [2.0 nil]}]}
        bad-type (assoc ok-result :type :other)
        bad-pane (assoc ok-result :pane :main)
        bad-series-shape (assoc-in ok-result [:series 0 :values] '(1.0))
        bad-series-length (assoc-in ok-result [:series 0 :values] [1.0])
        bad-series-non-finite (assoc-in ok-result [:series 0 :values] [js/Number.POSITIVE_INFINITY 1.0])
        bad-series-duplicate-ids (assoc ok-result :series [{:id :up
                                                            :series-type :line
                                                            :values [nil 1.0]}
                                                           {:id :up
                                                            :series-type :line
                                                            :values [2.0 nil]}])
        semantic-markers (assoc ok-result :markers [{:id "fractal-high-1"
                                                     :time 1
                                                     :kind :fractal-high
                                                     :price 123.4}
                                                    {:id "fractal-low-2"
                                                     :time 2
                                                     :kind :fractal-low
                                                     :price 122.1}])
        rendered-markers (assoc ok-result :markers [{:id "marker-1"
                                                     :time 1
                                                     :position "aboveBar"
                                                     :shape "arrowDown"}])
        invalid-markers (assoc ok-result :markers [{:id "x"
                                                    :time 1
                                                    :kind :unknown-kind}])]
    (is (true? (contracts/valid-indicator-result? ok-result :supertrend 2)))
    (is (true? (contracts/valid-indicator-result? semantic-markers :supertrend 2)))
    (is (false? (contracts/valid-indicator-result? rendered-markers :supertrend 2)))
    (is (nil? (contracts/enforce-indicator-result :supertrend 2 bad-type)))
    (is (false? (contracts/valid-indicator-result? bad-pane :supertrend 2)))
    (is (false? (contracts/valid-indicator-result? bad-series-shape :supertrend 2)))
    (is (false? (contracts/valid-indicator-result? bad-series-length :supertrend 2)))
    (is (false? (contracts/valid-indicator-result? bad-series-non-finite :supertrend 2)))
    (is (false? (contracts/valid-indicator-result? bad-series-duplicate-ids :supertrend 2)))
    (is (false? (contracts/valid-indicator-result? ok-result :unknown-indicator 2)))
    (is (false? (contracts/valid-indicator-result? invalid-markers :supertrend 2)))))
