(ns hyperopen.domain.trading.indicators.family-parity-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.indicators.flow :as flow]
            [hyperopen.domain.trading.indicators.oscillators :as oscillators]
            [hyperopen.domain.trading.indicators.price :as price]
            [hyperopen.domain.trading.indicators.structure :as structure]
            [hyperopen.domain.trading.indicators.trend :as trend]
            [hyperopen.domain.trading.indicators.volatility :as volatility]))

(defn- ids-from-definitions
  [definitions]
  (set (map :id definitions)))

(deftest family-catalog-and-calculator-parity-test
  (testing "trend parity"
    (is (= (ids-from-definitions (trend/get-trend-indicators))
           (trend/supported-trend-indicator-ids))))
  (testing "structure parity"
    (is (= (ids-from-definitions (structure/get-structure-indicators))
           (structure/supported-structure-indicator-ids))))
  (testing "oscillator parity"
    (is (= (ids-from-definitions (oscillators/get-oscillator-indicators))
           (oscillators/supported-oscillator-indicator-ids))))
  (testing "volatility parity"
    (is (= (ids-from-definitions (volatility/get-volatility-indicators))
           (volatility/supported-volatility-indicator-ids))))
  (testing "flow parity"
    (is (= (ids-from-definitions (flow/get-flow-indicators))
           (flow/supported-flow-indicator-ids))))
  (testing "price parity"
    (is (= (ids-from-definitions (price/get-price-indicators))
           (price/supported-price-indicator-ids)))))
