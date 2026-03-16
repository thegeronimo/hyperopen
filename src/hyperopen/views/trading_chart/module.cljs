(ns hyperopen.views.trading-chart.module
  (:require [hyperopen.views.trading-chart.core :as trading-chart-core]))

(defn ^:export trade-chart-view
  [state]
  (trading-chart-core/trading-chart-view state))

(goog/exportSymbol "hyperopen.views.trading_chart.module.trade_chart_view" trade-chart-view)
