(ns hyperopen.views.trading-chart.utils.chart-interop.series
  (:require ["lightweight-charts" :refer [AreaSeries BarSeries BaselineSeries CandlestickSeries HistogramSeries LineSeries]]
            [hyperopen.views.trading-chart.utils.chart-interop.transforms :as transforms]))

(def ^:private line-type-with-steps 1)

(defn add-area-series!
  "Add an area series to the chart."
  [chart]
  (let [series-options #js {:lineColor "#2962FF"
                            :topColor "#2962FF"
                            :bottomColor "rgba(41, 98, 255, 0.28)"}]
    (.addSeries ^js chart AreaSeries series-options)))

(defn add-bar-series!
  "Add a bar series to the chart."
  [chart]
  (let [series-options #js {:upColor "#26a69a"
                            :downColor "#ef5350"}]
    (.addSeries ^js chart BarSeries series-options)))

(defn add-high-low-series!
  "Add a high-low series rendered as solid floating range bars."
  [chart]
  (let [series-options #js {:upColor "#2962FF"
                            :downColor "#2962FF"
                            :wickVisible false
                            :borderVisible false}]
    (.addSeries ^js chart CandlestickSeries series-options)))

(defn add-baseline-series!
  "Add a baseline series to the chart."
  [chart]
  (let [series-options #js {:topLineColor "rgba(38, 166, 154, 1)"
                            :topFillColor1 "rgba(38, 166, 154, 0.28)"
                            :topFillColor2 "rgba(38, 166, 154, 0.05)"
                            :bottomLineColor "rgba(239, 83, 80, 1)"
                            :bottomFillColor1 "rgba(239, 83, 80, 0.05)"
                            :bottomFillColor2 "rgba(239, 83, 80, 0.28)"}]
    (.addSeries ^js chart BaselineSeries series-options)))

(defn add-candlestick-series!
  "Add a candlestick series to the chart."
  [chart]
  (let [series-options #js {:upColor "#26a69a"
                            :downColor "#ef5350"
                            :borderVisible false
                            :wickUpColor "#26a69a"
                            :wickDownColor "#ef5350"}]
    (.addSeries ^js chart CandlestickSeries series-options)))

(defn add-hollow-candles-series!
  "Add a hollow candlestick series to the chart."
  [chart]
  (let [series-options #js {:upColor "rgba(0, 0, 0, 0)"
                            :downColor "#ef5350"
                            :borderVisible true
                            :borderUpColor "#26a69a"
                            :borderDownColor "#ef5350"
                            :wickUpColor "#26a69a"
                            :wickDownColor "#ef5350"}]
    (.addSeries ^js chart CandlestickSeries series-options)))

(defn add-heikin-ashi-series!
  "Add a candlestick series used for Heikin Ashi candles."
  [chart]
  (let [series-options #js {:upColor "#26a69a"
                            :downColor "#ef5350"
                            :borderVisible false
                            :wickUpColor "#26a69a"
                            :wickDownColor "#ef5350"}]
    (.addSeries ^js chart CandlestickSeries series-options)))

(defn add-histogram-series!
  "Add a histogram series to the chart."
  [chart]
  (let [series-options #js {:color "#26a69a"}]
    (.addSeries ^js chart HistogramSeries series-options)))

(defn add-columns-series!
  "Add a columns-style histogram series to the chart."
  [chart]
  (let [series-options #js {:color "#26a69a"}]
    (.addSeries ^js chart HistogramSeries series-options)))

(defn add-line-series!
  "Add a line series to the chart."
  [chart]
  (let [series-options #js {:color "#2962FF"}]
    (.addSeries ^js chart LineSeries series-options)))

(defn add-line-with-markers-series!
  "Add a line series with point markers."
  [chart]
  (let [series-options #js {:color "#2962FF"
                            :pointMarkersVisible true
                            :pointMarkersRadius 3}]
    (.addSeries ^js chart LineSeries series-options)))

(defn add-step-line-series!
  "Add a step-line series."
  [chart]
  (let [series-options #js {:color "#2962FF"
                            :lineType line-type-with-steps}]
    (.addSeries ^js chart LineSeries series-options)))

(defn add-hlc-area-series!
  "Add an HLC area series."
  [chart]
  (let [series-options #js {:lineColor "#2962FF"
                            :topColor "#2962FF"
                            :bottomColor "rgba(41, 98, 255, 0.28)"}]
    (.addSeries ^js chart AreaSeries series-options)))

(defn add-volume-series!
  "Add a volume histogram series to the chart."
  [chart]
  (let [series-options #js {:priceFormat #js {:type "volume"}
                            :priceScaleId ""
                            :scaleMargins #js {:top 0.7 :bottom 0}
                            :color "#26a69a"}]
    (.addSeries ^js chart HistogramSeries series-options)))

(defn- ohlc-prices
  [transformed-data]
  (mapcat (fn [c] [(:open c) (:high c) (:low c) (:close c)]) transformed-data))

(defn- single-value-prices
  [transformed-data]
  (map :value transformed-data))

(def ^:private chart-type-registry
  {:area {:add add-area-series!
          :transform transforms/transform-data-for-close
          :prices single-value-prices}
   :bar {:add add-bar-series!
         :transform identity
         :prices ohlc-prices}
   :high-low {:add add-high-low-series!
              :transform transforms/transform-data-for-high-low
              :prices ohlc-prices}
   :baseline {:add add-baseline-series!
              :transform transforms/transform-data-for-close
              :prices single-value-prices}
   :hlc-area {:add add-hlc-area-series!
              :transform transforms/transform-data-for-hlc3
              :prices single-value-prices}
   :candlestick {:add add-candlestick-series!
                 :transform identity
                 :prices ohlc-prices}
   :hollow-candles {:add add-hollow-candles-series!
                    :transform identity
                    :prices ohlc-prices}
   :heikin-ashi {:add add-heikin-ashi-series!
                 :transform transforms/transform-data-for-heikin-ashi
                 :prices ohlc-prices}
   :columns {:add add-columns-series!
             :transform transforms/transform-data-for-columns
             :prices single-value-prices}
   :line {:add add-line-series!
          :transform transforms/transform-data-for-close
          :prices single-value-prices}
   :line-with-markers {:add add-line-with-markers-series!
                       :transform transforms/transform-data-for-close
                       :prices single-value-prices}
   :step-line {:add add-step-line-series!
               :transform transforms/transform-data-for-close
               :prices single-value-prices}})

(defn resolve-chart-type
  "Resolve chart type from registry with candlestick fallback."
  [chart-type]
  (let [normalized (transforms/normalize-main-chart-type chart-type)]
    (or (get chart-type-registry normalized)
        (get chart-type-registry :candlestick))))

(defn add-series!
  "Add a series for the requested chart type."
  [chart chart-type]
  ((:add (resolve-chart-type chart-type)) chart))

(defn transform-main-series-data
  "Transform main-series data for the requested chart type."
  [data chart-type]
  ((:transform (resolve-chart-type chart-type)) data))

(defn extract-series-prices
  "Extract normalized prices from transformed series data."
  [transformed-data chart-type]
  ((:prices (resolve-chart-type chart-type)) transformed-data))
