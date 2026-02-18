(ns hyperopen.views.trading-chart.utils.chart-interop
  (:require ["lightweight-charts" :refer [createChart HistogramSeries]]
            [hyperopen.schema.chart-interop-contracts :as chart-contracts]
            [hyperopen.views.trading-chart.utils.chart-interop.baseline :as baseline]
            [hyperopen.views.trading-chart.utils.chart-interop.indicators :as indicator-interop]
            [hyperopen.views.trading-chart.utils.chart-interop.legend :as legend]
            [hyperopen.views.trading-chart.utils.chart-interop.markers :as markers]
            [hyperopen.views.trading-chart.utils.chart-interop.open-order-overlays :as open-order-overlays]
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
                                                             :color transforms/hyperliquid-volume-up-color})]
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

(defonce ^:private main-series-sync-sidecar (js/WeakMap.))
(defonce ^:private volume-series-sync-sidecar (js/WeakMap.))

(defn- series-sync-state
  [sidecar series*]
  (if series*
    (or (.get sidecar series*) {})
    {}))

(defn- set-series-sync-state!
  [sidecar series* state]
  (when series*
    (.set sidecar series* state))
  state)

(defn- vectorize-candles
  [candles]
  (if (vector? candles) candles (vec candles)))

(defn- candle-prefix-equal?
  [candles-a candles-b prefix-count]
  (loop [idx 0]
    (if (>= idx prefix-count)
      true
      (if (= (nth candles-a idx) (nth candles-b idx))
        (recur (inc idx))
        false))))

(defn- infer-candle-sync-mode
  [previous-candles next-candles]
  (let [previous-candles* (or previous-candles [])
        prev-count (count previous-candles*)
        next-count (count next-candles)]
    (cond
      (and (zero? prev-count) (zero? next-count))
      :noop

      (identical? previous-candles next-candles)
      :noop

      (zero? prev-count)
      :full-reset

      (zero? next-count)
      :full-reset

      (= next-count prev-count)
      (let [prefix-count (max 0 (dec next-count))
            previous-last (nth previous-candles* (dec prev-count))
            next-last (nth next-candles (dec next-count))]
        (if (and (candle-prefix-equal? previous-candles* next-candles prefix-count)
                 (= (:time previous-last) (:time next-last)))
          (if (= previous-last next-last)
            :noop
            :update-last)
          :full-reset))

      (= next-count (inc prev-count))
      (let [previous-last (nth previous-candles* (dec prev-count))
            next-last (nth next-candles (dec next-count))]
        (if (and (candle-prefix-equal? previous-candles* next-candles prev-count)
                 (not= (:time previous-last) (:time next-last)))
          :append-last
          :full-reset))

      :else
      :full-reset)))

(defn- update-series-point!
  [series* point]
  (let [update-fn (when series*
                    (aget series* "update"))]
    (if (and (fn? update-fn) (some? point))
      (do
        (.call update-fn series* (clj->js point))
        true)
      false)))

(defn set-series-data!
  "Set data for any series type with registry-based transformation."
  ([series* data chart-type]
   (set-series-data! series* data chart-type {}))
  ([series* data chart-type {:keys [price-decimals]}]
   (chart-contracts/assert-candles! data
                                    {:boundary :chart-interop/set-series-data
                                     :chart-type chart-type})
   (let [data* (vectorize-candles data)
         chart-type* (transforms/normalize-main-chart-type chart-type)
         state (series-sync-state main-series-sync-sidecar series*)
         config-changed?
         (or (not= chart-type* (:chart-type state))
             (not= price-decimals (:price-decimals state)))
         data-sync-mode (if config-changed?
                          :full-reset
                          (infer-candle-sync-mode (:source-data state) data*))]
     (when (or config-changed?
               (not= data-sync-mode :noop))
       (let [transformed-data (series/transform-main-series-data data* chart-type*)
             base-value (when (= chart-type* :baseline)
                          (baseline/infer-baseline-base-value transformed-data))
             price-format* (price-format/infer-series-price-format
                            transformed-data
                            (fn [points]
                              (series/extract-series-prices points chart-type*))
                            {:price-decimals price-decimals})
             series-options (cond-> {:priceFormat price-format*}
                              (some? base-value)
                              (assoc :baseValue {:type "price" :price base-value}))
             options-changed? (not= series-options (:series-options state))]
         (when options-changed?
           (.applyOptions ^js series* (clj->js series-options)))
         (case data-sync-mode
           :append-last (when-not (update-series-point! series* (peek transformed-data))
                          (.setData series* (clj->js transformed-data)))
           :update-last (when-not (update-series-point! series* (peek transformed-data))
                          (.setData series* (clj->js transformed-data)))
           :full-reset (.setData series* (clj->js transformed-data))
           nil)
         (set-series-sync-state!
          main-series-sync-sidecar
          series*
          {:source-data data*
           :chart-type chart-type*
           :price-decimals price-decimals
           :series-options series-options}))))))

(defn set-volume-data!
  "Set volume data for volume series."
  [volume-series data]
  (chart-contracts/assert-candles! data
                                   {:boundary :chart-interop/set-volume-data}
                                   {:require-volume? true})
  (let [data* (vectorize-candles data)
        state (series-sync-state volume-series-sync-sidecar volume-series)
        data-sync-mode (infer-candle-sync-mode (:source-data state) data*)]
    (when (not= data-sync-mode :noop)
      (let [volume-data (vec (transforms/transform-data-for-volume data*))]
        (case data-sync-mode
          :append-last (when-not (update-series-point! volume-series (peek volume-data))
                         (.setData volume-series (clj->js volume-data)))
          :update-last (when-not (update-series-point! volume-series (peek volume-data))
                         (.setData volume-series (clj->js volume-data)))
          :full-reset (.setData volume-series (clj->js volume-data))
          nil)
        (set-series-sync-state!
         volume-series-sync-sidecar
         volume-series
         {:source-data data*})))))

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
  ([chart timeframe {:keys [storage-get] :as deps}]
   (if (contains? deps :storage-get)
     (visible-range-persistence/apply-persisted-visible-range! chart timeframe
                                                               {:storage-get storage-get})
     (visible-range-persistence/apply-persisted-visible-range! chart timeframe))))

(defn subscribe-visible-range-persistence!
  "Subscribe to visible-range changes and persist them by timeframe."
  ([chart timeframe]
   (subscribe-visible-range-persistence! chart timeframe {}))
  ([chart timeframe {:keys [storage-set!] :as deps}]
   (if (contains? deps :storage-set!)
     (visible-range-persistence/subscribe-visible-range-persistence! chart timeframe
                                                                     {:storage-set! storage-set!})
     (visible-range-persistence/subscribe-visible-range-persistence! chart timeframe))))

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

(defn create-chart-with-volume-and-series!
  "Create a chart with main series and volume series in separate panes."
  ([container chart-type data]
   (create-chart-with-volume-and-series! container chart-type data {}))
  ([container chart-type data {:keys [series-options]}]
   (chart-contracts/assert-candles! data
                                    {:boundary :chart-interop/create-chart-with-volume-and-series
                                     :chart-type chart-type})
   (let [chart (create-chart! container)
         main-series (add-series! chart chart-type)
         volume-series (.addSeries ^js chart HistogramSeries
                                   #js {:color transforms/hyperliquid-volume-up-color
                                        :priceFormat #js {:type "volume"}}
                                   1)]
     (set-series-data! main-series data chart-type series-options)
     (set-volume-data! volume-series data)
     (let [volume-pane (aget (.panes ^js chart) 1)]
       (.setHeight ^js volume-pane 150))
     (fit-content! chart)
     #js {:chart chart :mainSeries main-series :volumeSeries volume-series})))

(defn create-chart-with-indicators!
  "Create a chart with indicators, main series, and volume series."
  ([container chart-type data indicators]
   (create-chart-with-indicators! container chart-type data indicators {}))
  ([container chart-type data indicators {:keys [series-options]}]
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
         volume-series (.addSeries ^js chart HistogramSeries
                                   #js {:color transforms/hyperliquid-volume-up-color
                                        :priceFormat #js {:type "volume"}}
                                   next-pane-index)]
     (set-series-data! main-series data chart-type series-options)
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
          :indicatorSeries (clj->js indicator-series)})))

(defn create-candlestick-chart!
  "Create a chart (legacy function name)."
  [container]
  (create-chart! container))

(defn set-candlestick-data!
  "Set candlestick data (legacy function name)."
  [series* data]
  (.setData series* (clj->js data)))
