(ns hyperopen.views.trading-chart.utils.chart-interop
  (:require ["lightweight-charts" :refer [createChart HistogramSeries]]
            [hyperopen.views.trading-chart.utils.chart-interop.baseline :as baseline]
            [hyperopen.views.trading-chart.utils.chart-interop.indicators :as indicator-interop]
            [hyperopen.views.trading-chart.utils.chart-interop.legend :as legend]
            [hyperopen.views.trading-chart.utils.chart-interop.markers :as markers]
            [hyperopen.views.trading-chart.utils.chart-interop.price-format :as price-format]
            [hyperopen.views.trading-chart.utils.chart-interop.series :as series]
            [hyperopen.views.trading-chart.utils.chart-interop.transforms :as transforms]
            [hyperopen.views.trading-chart.utils.chart-interop.visible-range-persistence :as visible-range-persistence]
            [hyperopen.views.trading-chart.utils.chart-options :as chart-options]))

(defn create-chart-with-volume!
  "Create a chart with volume pane."
  [container]
  (let [chart-options* (clj->js (chart-options/fixed-height-chart-options 400))
        chart (createChart container chart-options*)
        volume-series (.addSeries chart HistogramSeries #js {:priceFormat #js {:type "volume"}
                                                             :priceScaleId ""
                                                             :scaleMargins #js {:top 0.8 :bottom 0}
                                                             :color "#26a69a"})]
    #js {:chart chart :volumeSeries volume-series}))

(defn create-chart!
  "Create a chart with common options."
  [container]
  (let [chart-options* (clj->js (chart-options/base-chart-options))]
    (createChart container chart-options*)))

(defn add-area-series!
  "Add an area series to the chart."
  [chart]
  (series/add-area-series! chart))

(defn add-bar-series!
  "Add a bar series to the chart."
  [chart]
  (series/add-bar-series! chart))

(defn add-high-low-series!
  "Add a high-low series rendered as solid floating range bars."
  [chart]
  (series/add-high-low-series! chart))

(defn add-baseline-series!
  "Add a baseline series to the chart."
  [chart]
  (series/add-baseline-series! chart))

(defn add-candlestick-series!
  "Add a candlestick series to the chart."
  [chart]
  (series/add-candlestick-series! chart))

(defn add-hollow-candles-series!
  "Add a hollow candlestick series to the chart."
  [chart]
  (series/add-hollow-candles-series! chart))

(defn add-heikin-ashi-series!
  "Add a candlestick series used for Heikin Ashi candles."
  [chart]
  (series/add-heikin-ashi-series! chart))

(defn add-histogram-series!
  "Add a histogram series to the chart."
  [chart]
  (series/add-histogram-series! chart))

(defn add-columns-series!
  "Add a columns-style histogram series to the chart."
  [chart]
  (series/add-columns-series! chart))

(defn add-line-series!
  "Add a line series to the chart."
  [chart]
  (series/add-line-series! chart))

(defn add-line-with-markers-series!
  "Add a line series with point markers."
  [chart]
  (series/add-line-with-markers-series! chart))

(defn add-step-line-series!
  "Add a step-line series."
  [chart]
  (series/add-step-line-series! chart))

(defn add-hlc-area-series!
  "Add an HLC area series."
  [chart]
  (series/add-hlc-area-series! chart))

(defn add-volume-series!
  "Add a volume histogram series to the chart."
  [chart]
  (series/add-volume-series! chart))

(defn transform-data-for-single-value
  "Transform OHLC data to single-value data using `value-fn`."
  [data value-fn]
  (transforms/transform-data-for-single-value data value-fn))

(defn transform-data-for-columns
  "Transform OHLC data to columns data with directional colors."
  [data]
  (transforms/transform-data-for-columns data))

(defn transform-data-for-heikin-ashi
  "Transform raw candles into Heikin Ashi candles."
  [data]
  (transforms/transform-data-for-heikin-ashi data))

(defn transform-data-for-high-low
  "Transform candles into solid high-low range bars."
  [data]
  (transforms/transform-data-for-high-low data))

(defn transform-data-for-volume
  "Transform OHLC data to volume data for volume chart."
  [data]
  (transforms/transform-data-for-volume data))

(defn sync-baseline-base-value-subscription!
  "Ensure baseline base-value subscription state matches current chart type."
  [chart-obj chart-type]
  (baseline/sync-baseline-base-value-subscription! chart-obj chart-type))

(defn clear-baseline-base-value-subscription!
  "Clear baseline base-value subscription and sidecar state."
  [chart-obj]
  (baseline/clear-baseline-base-value-subscription! chart-obj))

(defn set-series-data!
  "Set data for any series type with registry-based transformation."
  [series* data chart-type]
  (let [chart-type* (transforms/normalize-main-chart-type chart-type)
        transformed-data (series/transform-main-series-data data chart-type*)
        base-value (when (= chart-type* :baseline)
                     (baseline/infer-baseline-base-value transformed-data))
        price-format* (price-format/infer-series-price-format
                       transformed-data
                       (fn [points]
                         (series/extract-series-prices points chart-type*)))
        series-options (cond-> {:priceFormat price-format*}
                         (some? base-value)
                         (assoc :baseValue {:type "price" :price base-value}))]
    (.applyOptions ^js series* (clj->js series-options))
    (.setData series* (clj->js transformed-data))))

(defn set-volume-data!
  "Set volume data for volume series."
  [volume-series data]
  (let [volume-data (transforms/transform-data-for-volume data)]
    (.setData volume-series (clj->js volume-data))))

(defn add-series!
  "Add a series of the specified type to the chart."
  [chart chart-type]
  (series/add-series! chart chart-type))

(defn fit-content!
  "Fit content to the chart viewport."
  [chart]
  (.fitContent ^js (.timeScale ^js chart)))

(defn apply-persisted-visible-range!
  "Apply persisted visible range to chart time scale if available."
  [chart timeframe]
  (visible-range-persistence/apply-persisted-visible-range! chart timeframe))

(defn subscribe-visible-range-persistence!
  "Subscribe to visible-range changes and persist them by timeframe."
  [chart timeframe]
  (visible-range-persistence/subscribe-visible-range-persistence! chart timeframe))

(defn create-legend!
  "Create legend element that adapts to different chart types."
  [container chart legend-meta]
  (legend/create-legend! container chart legend-meta))

(defn add-indicator-series!
  "Add an indicator series in the requested pane."
  [chart series-def pane-index]
  (indicator-interop/add-indicator-series! chart series-def pane-index))

(defn set-indicator-data!
  "Set indicator data and preserve whitespace points."
  [series* data]
  (indicator-interop/set-indicator-data! series* data))

(defn set-main-series-markers!
  "Attach/update markers on the main price series."
  [chart-obj markers]
  (markers/set-main-series-markers! chart-obj markers))

(defn create-chart-with-volume-and-series!
  "Create a chart with main series and volume series in separate panes."
  [container chart-type data]
  (let [chart (create-chart! container)
        main-series (add-series! chart chart-type)
        volume-series (.addSeries ^js chart HistogramSeries
                                  #js {:color "#26a69a"
                                       :priceFormat #js {:type "volume"}}
                                  1)]
    (set-series-data! main-series data chart-type)
    (set-volume-data! volume-series data)
    (let [volume-pane (aget (.panes ^js chart) 1)]
      (.setHeight ^js volume-pane 150))
    (fit-content! chart)
    #js {:chart chart :mainSeries main-series :volumeSeries volume-series}))

(defn create-chart-with-indicators!
  "Create a chart with indicators, main series, and volume series."
  [container chart-type data indicators]
  (let [chart (create-chart! container)
        {:keys [next-pane-index assignments]} (indicator-interop/indicator-pane-allocation indicators)
        indicator-series (mapv (fn [{:keys [indicator series-def pane-index]}]
                                 (let [series* (add-indicator-series! chart series-def pane-index)]
                                   (set-indicator-data! series* (:data series-def))
                                   {:series series*
                                    :indicator indicator
                                    :seriesDef series-def
                                    :paneIndex pane-index}))
                               assignments)
        main-series (add-series! chart chart-type)
        volume-series (.addSeries ^js chart HistogramSeries
                                  #js {:color "#26a69a"
                                       :priceFormat #js {:type "volume"}}
                                  next-pane-index)]
    (set-series-data! main-series data chart-type)
    (set-volume-data! volume-series data)
    (doseq [pane-index (range 1 next-pane-index)]
      (when-let [pane (aget (.panes ^js chart) pane-index)]
        (.setHeight ^js pane 120)))
    (when-let [volume-pane (aget (.panes ^js chart) next-pane-index)]
      (.setHeight ^js volume-pane 150))
    (fit-content! chart)
    #js {:chart chart
         :mainSeries main-series
         :volumeSeries volume-series
         :indicatorSeries (clj->js indicator-series)}))

(defn create-candlestick-chart!
  "Create a chart (legacy function name)."
  [container]
  (create-chart! container))

(defn set-candlestick-data!
  "Set candlestick data (legacy function name)."
  [series* data]
  (.setData series* (clj->js data)))
