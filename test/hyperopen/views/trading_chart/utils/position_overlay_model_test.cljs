(ns hyperopen.views.trading-chart.utils.position-overlay-model-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.utils.position-overlay-model :as position-overlay-model]))

(deftest build-position-overlay-long-infers-entry-time-and-marker-test
  (let [overlay (position-overlay-model/build-position-overlay
                 {:active-asset "ETH"
                  :position {:coin "ETH"
                             :szi "1.2"
                             :entryPx "2500.25"
                             :unrealizedPnl "42.5"
                             :liquidationPx "1800.5"}
                  :fills [{:coin "ETH" :startPosition "-1" :side "B" :sz "1" :time 1700000000000}
                          {:coin "ETH" :startPosition "0" :side "B" :sz "1.2" :time 1700000123456}
                          {:coin "ETH" :startPosition "1.2" :side "B" :sz "0.3" :time 1700001000000}]
                  :market-by-key {}
                  :selected-timeframe :1h
                  :candle-data [{:time 1699995600}
                                {:time 1700003600}]})]
    (is (= :long (:side overlay)))
    (is (= 1.2 (:size overlay)))
    (is (= 1.2 (:abs-size overlay)))
    (is (= 2500.25 (:entry-price overlay)))
    (is (= 42.5 (:unrealized-pnl overlay)))
    (is (= 1800.5 (:liquidation-price overlay)))
    (is (= 1700003600 (:latest-time overlay)))
    (is (= 1700000123456 (:entry-time-ms overlay)))
    (is (= 1699999200 (:entry-time overlay)))
    (is (= {:time 1699999200
            :position "belowBar"
            :shape "circle"
            :color "#26a69a"
            :text "L"}
           (:entry-marker overlay)))))

(deftest build-position-overlay-short-prefers-latest-transition-fill-from-unsorted-input-test
  (let [overlay (position-overlay-model/build-position-overlay
                 {:active-asset "SOL"
                  :position {:coin "SOL"
                             :szi "-2"
                             :entryPx "99.5"
                             :unrealizedPnl "-5.0"
                             :liquidationPx "130.75"}
                  :fills [{:coin "SOL" :dir "Open Short Market" :side "A" :sz "1.5" :time 1700600000000}
                          {:coin "SOL" :startPosition "-2" :side "A" :sz "0.4" :time 1700700000000}
                          {:coin "SOL" :startPosition "0" :side "A" :sz "1" :time 1700500000000}]
                  :selected-timeframe :1d
                  :candle-data [{:time 1700438400}
                                {:time 1700697600}]})]
    (is (= :short (:side overlay)))
    (is (= -2 (:size overlay)))
    (is (= 2 (:abs-size overlay)))
    (is (= 1700600000000 (:entry-time-ms overlay)))
    (is (= 1700524800 (:entry-time overlay)))
    (is (= {:time 1700524800
            :position "aboveBar"
            :shape "circle"
            :color "#ef5350"
            :text "S"}
           (:entry-marker overlay)))))

(deftest build-position-overlay-omits-entry-marker-when-entry-fill-is-unknown-test
  (let [overlay (position-overlay-model/build-position-overlay
                 {:active-asset "BTC"
                  :position {:coin "BTC"
                             :szi "0.8"
                             :entryPx "60000"
                             :unrealizedPnl "150"}
                  :fills []
                  :selected-timeframe :5m
                  :candle-data [{:time 1700000000}]})]
    (is (= :long (:side overlay)))
    (is (nil? (:entry-time overlay)))
    (is (nil? (:entry-time-ms overlay)))
    (is (nil? (:entry-marker overlay)))))

(deftest build-position-overlay-returns-nil-for-flat-or-invalid-position-test
  (is (nil? (position-overlay-model/build-position-overlay
             {:active-asset "BTC"
              :position {:coin "BTC"
                         :szi "0"
                         :entryPx "60000"}})))
  (is (nil? (position-overlay-model/build-position-overlay
             {:active-asset "BTC"
              :position {:coin "BTC"
                         :szi "1"
                         :entryPx nil}}))))

(deftest build-position-overlay-includes-active-asset-fill-markers-when-enabled-test
  (let [overlay (position-overlay-model/build-position-overlay
                 {:active-asset "BTC"
                  :position {:coin "BTC"
                             :szi "1"
                             :entryPx "60000"}
                  :fills [{:coin "BTC" :side "B" :sz "1" :price "61000" :time 1700000000000}
                          {:coin "ETH" :side "A" :sz "2" :price "3200" :time 1700000060000}
                          {:coin "BTC" :side "A" :sz "0.5" :price "62000" :time 1700000120000}]
                  :market-by-key {}
                  :selected-timeframe :1h
                  :candle-data [{:time 1700000000}
                                {:time 1700003600}]
                  :show-fill-markers? true})
        markers (:fill-markers overlay)]
    (is (= 2 (count markers)))
    (is (every? #(= "BTC" (:coin %)) markers))
    (is (every? some? (map :time markers)))))

(deftest build-position-overlay-fill-markers-accept-websocket-runtime-fill-variants-test
  (let [overlay (position-overlay-model/build-position-overlay
                 {:active-asset "BTC"
                  :position {:coin "BTC"
                             :szi "1"
                             :entryPx "60000"}
                  :fills [{:coin "BTC"
                           :direction "Buy Market"
                           :filledSz "1"
                           :fillPx "61000"
                           :time 1700000000000}
                          {:coin "BTC"
                           :direction "Buy Market"
                           :filledSz "1"
                           :fillPx "61000"
                           :time 1700000000000}
                          {:coin "BTC"
                           :direction "Close Long"
                           :filled "0.5"
                           :avgPx "62000"
                           :time 1700000120000}]
                  :market-by-key {}
                  :selected-timeframe :1h
                  :candle-data [{:time 1700000000}
                                {:time 1700003600}]
                  :show-fill-markers? true})
        markers (:fill-markers overlay)]
    (is (= 2 (count markers)))
    (is (= ["B" "S"] (mapv :text markers)))
    (is (= ["arrowUp" "arrowDown"] (mapv :shape markers)))
    (is (= [1699999200 1699999200] (mapv :time markers)))))
