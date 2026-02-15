(ns hyperopen.domain.trading.indicators.flow-family-coverage-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.indicators.flow :as flow]
            [hyperopen.domain.trading.indicators.support :as support]))

(deftest flow-family-supported-indicators-produce-results-test
  (let [candles support/sample-candles
        params support/default-indicator-params]
    (doseq [indicator-id (flow/supported-flow-indicator-ids)]
      (testing (str "flow indicator " indicator-id)
        (let [result (flow/calculate-flow-indicator indicator-id candles params)]
          (is (map? result))
          (is (= indicator-id (:type result))))))))

(deftest flow-family-unknown-indicator-returns-nil-test
  (is (nil? (flow/calculate-flow-indicator :not-a-flow-indicator
                                           support/sample-candles
                                           support/default-indicator-params))))
