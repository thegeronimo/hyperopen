(ns hyperopen.domain.trading.indicators.oscillators-family-coverage-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.indicators.oscillators :as oscillators]
            [hyperopen.domain.trading.indicators.support :as support]))

(deftest oscillator-family-supported-indicators-produce-results-test
  (let [candles support/sample-candles
        params support/default-indicator-params]
    (doseq [indicator-id (oscillators/supported-oscillator-indicator-ids)]
      (testing (str "oscillator indicator " indicator-id)
        (let [result (oscillators/calculate-oscillator-indicator indicator-id candles params)]
          (is (map? result))
          (is (= indicator-id (:type result))))))))

(deftest oscillator-family-unknown-indicator-returns-nil-test
  (is (nil? (oscillators/calculate-oscillator-indicator :not-an-oscillator
                                                        support/sample-candles
                                                        support/default-indicator-params))))
