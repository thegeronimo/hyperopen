(ns hyperopen.views.trading-chart.indicators-module
  (:require [hyperopen.views.trading-chart.utils.indicators :as indicators]))

(defn ^:export calculateIndicator
  [indicator-type data params]
  (indicators/calculate-indicator indicator-type data params))

(goog/exportSymbol "hyperopen.views.trading_chart.indicators_module.calculateIndicator" calculateIndicator)
