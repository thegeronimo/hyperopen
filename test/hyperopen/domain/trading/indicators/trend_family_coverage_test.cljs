(ns hyperopen.domain.trading.indicators.trend-family-coverage-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.indicators.support :as support]
            [hyperopen.domain.trading.indicators.trend :as trend]))

(deftest trend-family-supported-indicators-produce-results-test
  (let [candles support/sample-candles
        params support/default-indicator-params]
    (doseq [indicator-id (trend/supported-trend-indicator-ids)]
      (testing (str "trend indicator " indicator-id)
        (let [result (trend/calculate-trend-indicator indicator-id candles params)]
          (is (map? result))
          (is (= indicator-id (:type result))))))))

(deftest trend-family-unknown-indicator-returns-nil-test
  (is (nil? (trend/calculate-trend-indicator :not-a-trend-indicator
                                             support/sample-candles
                                             support/default-indicator-params))))
