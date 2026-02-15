(ns hyperopen.domain.trading.indicators.volatility-family-coverage-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.indicators.support :as support]
            [hyperopen.domain.trading.indicators.volatility :as volatility]))

(deftest volatility-family-supported-indicators-produce-results-test
  (let [candles support/sample-candles
        params support/default-indicator-params]
    (doseq [indicator-id (volatility/supported-volatility-indicator-ids)]
      (testing (str "volatility indicator " indicator-id)
        (let [result (volatility/calculate-volatility-indicator indicator-id candles params)]
          (is (map? result))
          (is (= indicator-id (:type result))))))))

(deftest volatility-family-unknown-indicator-returns-nil-test
  (is (nil? (volatility/calculate-volatility-indicator :not-a-volatility-indicator
                                                       support/sample-candles
                                                       support/default-indicator-params))))
