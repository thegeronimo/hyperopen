(ns hyperopen.views.trading-chart.utils.chart-interop.overlay-format-sync-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.test-support.series :as series-support]
            [hyperopen.views.trading-chart.utils.chart-interop :as chart-interop]))

(def ^:private candles
  [{:time 1 :open 0.2 :high 0.21 :low 0.19 :close 0.2091}
   {:time 2 :open 0.2091 :high 0.22 :low 0.2 :close 0.20875}])

(defn- overlay-recorder
  [formats]
  #js {:applyOptions (fn [options]
                       (swap! formats conj (aget options "priceFormat")))})

(deftest overlay-indicators-follow-main-series-format-updates-test
  (let [main (series-support/make-series :apply-options (fn [_] nil) :set-data (fn [_] nil))
        overlay-formats (atom [])
        separate-formats (atom [])
        chart-obj #js {:mainSeries main
                       :indicatorSeries #js [#js {:series (overlay-recorder overlay-formats) :paneIndex 0}
                                             #js {:series (overlay-recorder separate-formats) :paneIndex 1}]}]
    (chart-interop/set-series-data! main candles :candlestick {:price-decimals 5 :price-unit :percent})
    (chart-interop/sync-overlay-indicator-formats! chart-obj)
    (is (= 1 (count @overlay-formats)))
    (is (= "20.875%" ((aget (last @overlay-formats) "formatter") 0.20875)))
    (chart-interop/sync-overlay-indicator-formats! chart-obj)
    (is (= 1 (count @overlay-formats)) "an unchanged format is applied once")
    ;; The market's price decimals change without a remount: the main series
    ;; takes a new format, and the overlay follows it.
    (chart-interop/set-series-data! main candles :candlestick {:price-decimals 3 :price-unit :percent})
    (chart-interop/sync-overlay-indicator-formats! chart-obj)
    (is (= 2 (count @overlay-formats)))
    (is (= "20.9%" ((aget (last @overlay-formats) "formatter") 0.20875)))
    (chart-interop/set-series-data! main candles :candlestick {:price-decimals 3 :price-unit :cents})
    (chart-interop/sync-overlay-indicator-formats! chart-obj)
    (is (= "20.9¢" ((aget (last @overlay-formats) "formatter") 0.20875)))
    (is (= [] @separate-formats) "separate panes keep their own scale")))

(deftest overlay-indicators-keep-their-format-on-dollar-charts-test
  (let [main (series-support/make-series :apply-options (fn [_] nil) :set-data (fn [_] nil))
        overlay-formats (atom [])
        chart-obj #js {:mainSeries main
                       :indicatorSeries #js [#js {:series (overlay-recorder overlay-formats) :paneIndex 0}]}]
    (chart-interop/set-series-data! main candles :candlestick {:price-decimals 4})
    (chart-interop/sync-overlay-indicator-formats! chart-obj)
    (is (= [] @overlay-formats) "perp and spot charts are unchanged")
    (chart-interop/sync-overlay-indicator-formats! nil)
    (chart-interop/sync-overlay-indicator-formats! #js {:mainSeries main})))

(deftest a-candle-tick-applies-no-options-when-unit-and-decimals-hold-test
  (let [main-applies (atom 0)
        main (series-support/make-series :apply-options (fn [_] (swap! main-applies inc))
                                         :set-data (fn [_] nil)
                                         :update (fn [_] nil))
        overlay-formats (atom [])
        chart-obj #js {:mainSeries main
                       :indicatorSeries #js [#js {:series (overlay-recorder overlay-formats) :paneIndex 0}]}
        opts {:price-decimals 5 :price-unit :percent}]
    (chart-interop/set-series-data! main candles :candlestick opts)
    (chart-interop/sync-overlay-indicator-formats! chart-obj)
    (is (= 1 @main-applies))
    (is (= 1 (count @overlay-formats)))
    ;; live ticks: the last candle moves, then a new one opens
    (doseq [next-candles [(assoc-in candles [1 :close] 0.21)
                          (conj candles {:time 3 :open 0.2 :high 0.22 :low 0.2 :close 0.215})]]
      (chart-interop/set-series-data! main next-candles :candlestick opts)
      (chart-interop/sync-overlay-indicator-formats! chart-obj))
    (is (= 1 @main-applies) "the main series is not re-optioned per tick")
    (is (= 1 (count @overlay-formats)) "nor is any price-pane indicator")))
