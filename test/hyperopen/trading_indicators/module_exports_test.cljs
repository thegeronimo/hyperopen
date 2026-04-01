(ns hyperopen.trading-indicators.module-exports-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.indicators-module]))

(deftest trading-indicators-module-exports-are-available-test
  (let [module (aget js/globalThis "hyperopen" "views" "trading_chart" "indicators_module")]
    (is (fn? (aget module "calculateIndicator")))))
