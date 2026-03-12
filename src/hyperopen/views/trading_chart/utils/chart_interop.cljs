(ns hyperopen.views.trading-chart.utils.chart-interop
  (:require ["lightweight-charts" :refer [createChart HistogramSeries]]
            [hyperopen.schema.chart-interop-contracts :as chart-contracts]
            [hyperopen.views.trading-chart.utils.chart-interop.baseline :as baseline]
            [hyperopen.views.trading-chart.utils.chart-interop.chart-navigation-overlay :as chart-navigation-overlay]
            [hyperopen.views.trading-chart.utils.chart-interop.indicators :as indicator-interop]
            [hyperopen.views.trading-chart.utils.chart-interop.legend :as legend]
            [hyperopen.views.trading-chart.utils.chart-interop.markers :as markers]
            [hyperopen.views.trading-chart.utils.chart-interop.open-order-overlays :as open-order-overlays]
            [hyperopen.views.trading-chart.utils.chart-interop.position-overlays :as position-overlays]
            [hyperopen.views.trading-chart.utils.chart-interop.series :as series]
            [hyperopen.views.trading-chart.utils.chart-interop.series-sync :as series-sync]
            [hyperopen.views.trading-chart.utils.chart-interop.transforms :as transforms]
            [hyperopen.views.trading-chart.utils.chart-interop.volume-indicator-overlay :as volume-indicator-overlay]
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
                                                             :color transforms/hyperliquid-volume-up-color})]
    #js {:chart chart :volumeSeries volume-series :volumePaneIndex 1}))

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
  (when chart-obj
    (chart-contracts/assert-chart-handle! chart-obj
                                          {:boundary :chart-interop/sync-baseline
                                           :chart-type chart-type}))
  (baseline/sync-baseline-base-value-subscription! chart-obj chart-type))

(defn clear-baseline-base-value-subscription!
  "Clear baseline base-value subscription and sidecar state."
  [chart-obj]
  (when chart-obj
    (chart-contracts/assert-chart-handle! chart-obj
                                          {:boundary :chart-interop/clear-baseline}))
  (baseline/clear-baseline-base-value-subscription! chart-obj))

(defn set-series-data!
  "Set data for any series type with registry-based transformation."
  ([series* data chart-type]
   (set-series-data! series* data chart-type {}))
  ([series* data chart-type {:keys [price-decimals]}]
   (chart-contracts/assert-candles! data
                                    {:boundary :chart-interop/set-series-data
                                     :chart-type chart-type})
   (series-sync/sync-main-series! series* data chart-type {:price-decimals price-decimals})))

(defn set-volume-data!
  "Set volume data for volume series."
  [volume-series data]
  (chart-contracts/assert-candles! data
                                   {:boundary :chart-interop/set-volume-data}
                                   {:require-volume? true})
  (series-sync/sync-volume-series! volume-series data))

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
  ([chart timeframe]
   (apply-persisted-visible-range! chart timeframe {}))
  ([chart timeframe deps]
   (visible-range-persistence/apply-persisted-visible-range! chart timeframe deps)))

(defn apply-default-visible-range!
  "Apply the default recent visible range when no persisted range should win."
  [chart candles]
  (visible-range-persistence/apply-default-visible-range! chart candles))

(defn subscribe-visible-range-persistence!
  "Subscribe to visible-range changes and persist them by asset + timeframe."
  ([chart timeframe]
   (subscribe-visible-range-persistence! chart timeframe {}))
  ([chart timeframe deps]
   (visible-range-persistence/subscribe-visible-range-persistence! chart timeframe deps)))

(defn create-legend!
  "Create legend element that adapts to different chart types."
  ([container chart legend-meta]
   (create-legend! container chart legend-meta {}))
  ([container chart legend-meta deps]
   (chart-contracts/assert-legend-meta! legend-meta
                                        {:boundary :chart-interop/create-legend})
   (legend/create-legend! container chart legend-meta deps)))

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
  ([chart-obj markers]
   (set-main-series-markers! chart-obj markers {}))
  ([chart-obj markers deps]
   (when chart-obj
     (chart-contracts/assert-chart-handle! chart-obj
                                           {:boundary :chart-interop/set-main-series-markers}))
   (markers/set-main-series-markers! chart-obj markers deps)))

(defn sync-open-order-overlays!
  "Attach/update chart open-order overlays aligned to order price coordinates."
  ([chart-obj container orders]
   (sync-open-order-overlays! chart-obj container orders {}))
  ([chart-obj container orders opts]
   (when chart-obj
     (chart-contracts/assert-chart-handle! chart-obj
                                           {:boundary :chart-interop/sync-open-order-overlays}))
   (open-order-overlays/sync-open-order-overlays! chart-obj container orders opts)))

(defn clear-open-order-overlays!
  "Clear chart open-order overlays and remove overlay listeners/DOM."
  [chart-obj]
  (when chart-obj
    (chart-contracts/assert-chart-handle! chart-obj
                                          {:boundary :chart-interop/clear-open-order-overlays}))
  (open-order-overlays/clear-open-order-overlays! chart-obj))

(defn sync-position-overlays!
  "Attach/update chart position overlays aligned to entry/liquidation price coordinates."
  ([chart-obj container overlay]
   (sync-position-overlays! chart-obj container overlay {}))
  ([chart-obj container overlay opts]
   (when chart-obj
     (chart-contracts/assert-chart-handle! chart-obj
                                           {:boundary :chart-interop/sync-position-overlays}))
   (position-overlays/sync-position-overlays! chart-obj container overlay opts)))

(defn clear-position-overlays!
  "Clear chart position overlays and remove overlay listeners/DOM."
  [chart-obj]
  (when chart-obj
    (chart-contracts/assert-chart-handle! chart-obj
                                          {:boundary :chart-interop/clear-position-overlays}))
  (position-overlays/clear-position-overlays! chart-obj))

(defn sync-volume-indicator-overlay!
  "Attach/update the volume-pane indicator overlay with value + controls."
  ([chart-obj container candles]
   (sync-volume-indicator-overlay! chart-obj container candles {}))
  ([chart-obj container candles {:as opts}]
   (when (and chart-obj (.-volumeSeries ^js chart-obj))
     (chart-contracts/assert-candles! candles
                                      {:boundary :chart-interop/sync-volume-indicator-overlay}
                                      {:require-volume? true}))
   (volume-indicator-overlay/sync-volume-indicator-overlay! chart-obj container candles opts)))

(defn clear-volume-indicator-overlay!
  "Clear volume-pane indicator overlay DOM/subscriptions."
  [chart-obj]
  (volume-indicator-overlay/clear-volume-indicator-overlay! chart-obj))

(defn sync-chart-navigation-overlay!
  "Attach/update bottom chart navigation controls (zoom, pan, reset)."
  ([chart-obj container candles]
   (sync-chart-navigation-overlay! chart-obj container candles {}))
  ([chart-obj container candles opts]
   (chart-navigation-overlay/sync-chart-navigation-overlay! chart-obj container candles opts)))

(defn clear-chart-navigation-overlay!
  "Clear bottom chart navigation controls and event listeners."
  [chart-obj]
  (chart-navigation-overlay/clear-chart-navigation-overlay! chart-obj))

(defn- create-volume-series!
  [chart pane-index]
  (.addSeries ^js chart HistogramSeries
              #js {:color transforms/hyperliquid-volume-up-color
                   :priceFormat #js {:type "volume"}}
              pane-index))

(defn create-chart-with-volume-and-series!
  "Create a chart with main series and volume series in separate panes."
  ([container chart-type data]
   (create-chart-with-volume-and-series! container chart-type data {}))
  ([container chart-type data {:keys [series-options volume-visible?]
                               :or {volume-visible? true}}]
   (chart-contracts/assert-candles! data
                                    {:boundary :chart-interop/create-chart-with-volume-and-series
                                     :chart-type chart-type})
   (let [chart (create-chart! container)
         main-series (add-series! chart chart-type)
         volume-pane-index (when volume-visible? 1)
         volume-series (when volume-visible?
                         (create-volume-series! chart volume-pane-index))]
     (set-series-data! main-series data chart-type series-options)
     (when volume-series
       (set-volume-data! volume-series data))
     (when-let [volume-pane (some->> volume-pane-index
                                     (aget (.panes ^js chart)))]
       (.setHeight ^js volume-pane 150))
     (fit-content! chart)
     #js {:chart chart
          :mainSeries main-series
          :volumeSeries volume-series
          :volumePaneIndex volume-pane-index})))

(defn create-chart-with-indicators!
  "Create a chart with indicators, main series, and volume series."
  ([container chart-type data indicators]
   (create-chart-with-indicators! container chart-type data indicators {}))
  ([container chart-type data indicators {:keys [series-options volume-visible?]
                                          :or {volume-visible? true}}]
   (chart-contracts/assert-candles! data
                                    {:boundary :chart-interop/create-chart-with-indicators
                                     :chart-type chart-type})
   (chart-contracts/assert-indicators! indicators
                                       {:boundary :chart-interop/create-chart-with-indicators})
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
         volume-pane-index (when volume-visible? next-pane-index)
         volume-series (when volume-visible?
                         (create-volume-series! chart volume-pane-index))]
     (set-series-data! main-series data chart-type series-options)
     (when volume-series
       (set-volume-data! volume-series data))
     (doseq [pane-index (range 1 next-pane-index)]
       (when-let [pane (aget (.panes ^js chart) pane-index)]
         (.setHeight ^js pane 120)))
     (when-let [volume-pane (some->> volume-pane-index
                                     (aget (.panes ^js chart)))]
       (.setHeight ^js volume-pane 150))
     (fit-content! chart)
     #js {:chart chart
          :mainSeries main-series
          :volumeSeries volume-series
          :volumePaneIndex volume-pane-index
          :indicatorSeries (clj->js indicator-series)})))

(defn create-candlestick-chart!
  "Create a chart (legacy function name)."
  [container]
  (create-chart! container))

(defn set-candlestick-data!
  "Set candlestick data (legacy function name)."
  [series* data]
  (.setData series* (clj->js data)))
