(ns hyperopen.domain.trading.indicators.contracts-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.domain.trading.indicators.contracts :as contracts]))

(deftest valid-indicator-input-test
  (is (true? (contracts/valid-indicator-input? [] {})))
  (is (true? (contracts/valid-indicator-input? [{:time 1}] {:period 14})))
  (is (false? (contracts/valid-indicator-input? nil {})))
  (is (false? (contracts/valid-indicator-input? [1 2] {})))
  (is (false? (contracts/valid-indicator-input? [] []))))

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
        bad-series-shape (assoc-in ok-result [:series 0 :values] '(1.0))]
    (is (true? (contracts/valid-indicator-result? ok-result :supertrend 2)))
    (is (nil? (contracts/enforce-indicator-result :supertrend 2 bad-type)))
    (is (false? (contracts/valid-indicator-result? bad-pane :supertrend 2)))
    (is (false? (contracts/valid-indicator-result? bad-series-shape :supertrend 2)))))
