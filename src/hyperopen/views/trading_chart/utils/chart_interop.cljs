(ns hyperopen.views.trading-chart.utils.chart-interop
  (:require ["lightweight-charts" :refer [createChart AreaSeries BarSeries BaselineSeries CandlestickSeries HistogramSeries LineSeries createSeriesMarkers]]
            [hyperopen.platform :as platform]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.trading-chart.utils.chart-options :as chart-options]))

(def ^:private line-type-with-steps 1)

;; Generic chart creation with volume support
(defn create-chart-with-volume! [container]
  "Create a chart with volume pane"
  (let [chartOptions (clj->js (chart-options/fixed-height-chart-options 400))
        chart (createChart container chartOptions)
        ;; Create volume series on a separate pane
        volumeSeries (.addSeries chart HistogramSeries #js {:priceFormat #js {:type "volume"}
                                                            :priceScaleId ""
                                                            :scaleMargins #js {:top 0.8 :bottom 0}
                                                            :color "#26a69a"})]
    #js {:chart chart :volumeSeries volumeSeries}))

;; Generic chart creation
(defn create-chart! [container]
  "Create a chart with common options"
  (let [chartOptions (clj->js (chart-options/base-chart-options))]
    (let [chart (createChart container chartOptions)]
      chart)))

;; Series creation functions for each chart type
(defn add-area-series! [chart]
  "Add an area series to the chart"
  (let [seriesOptions #js {:lineColor "#2962FF"
                           :topColor "#2962FF"
                           :bottomColor "rgba(41, 98, 255, 0.28)"}
        series (.addSeries ^js chart AreaSeries seriesOptions)]
    series))

(defn add-bar-series! [chart]
  "Add a bar series to the chart"
  (let [seriesOptions #js {:upColor "#26a69a"
                           :downColor "#ef5350"}
        series (.addSeries ^js chart BarSeries seriesOptions)]
    series))

(defn add-high-low-series! [chart]
  "Add a high-low series rendered as solid floating range bars."
  (let [seriesOptions #js {:upColor "#2962FF"
                           :downColor "#2962FF"
                           :wickVisible false
                           :borderVisible false}
        series (.addSeries ^js chart CandlestickSeries seriesOptions)]
    series))

(defn add-baseline-series! [chart]
  "Add a baseline series to the chart"
  (let [seriesOptions #js {:baseValue #js {:type "price" :price 25}
                           :topLineColor "rgba(38, 166, 154, 1)"
                           :topFillColor1 "rgba(38, 166, 154, 0.28)"
                           :topFillColor2 "rgba(38, 166, 154, 0.05)"
                           :bottomLineColor "rgba(239, 83, 80, 1)"
                           :bottomFillColor1 "rgba(239, 83, 80, 0.05)"
                           :bottomFillColor2 "rgba(239, 83, 80, 0.28)"}
        series (.addSeries ^js chart BaselineSeries seriesOptions)]
    series))

(defn add-candlestick-series! [chart]
  "Add a candlestick series to the chart"
  (let [seriesOptions #js {:upColor "#26a69a"
                           :downColor "#ef5350"
                           :borderVisible false
                           :wickUpColor "#26a69a"
                           :wickDownColor "#ef5350"}
        series (.addSeries ^js chart CandlestickSeries seriesOptions)]
    series))

(defn add-hollow-candles-series! [chart]
  "Add a hollow candlestick series to the chart."
  (let [seriesOptions #js {:upColor "rgba(0, 0, 0, 0)"
                           :downColor "#ef5350"
                           :borderVisible true
                           :borderUpColor "#26a69a"
                           :borderDownColor "#ef5350"
                           :wickUpColor "#26a69a"
                           :wickDownColor "#ef5350"}
        series (.addSeries ^js chart CandlestickSeries seriesOptions)]
    series))

(defn add-heikin-ashi-series! [chart]
  "Add a candlestick series used for Heikin Ashi candles."
  (let [seriesOptions #js {:upColor "#26a69a"
                           :downColor "#ef5350"
                           :borderVisible false
                           :wickUpColor "#26a69a"
                           :wickDownColor "#ef5350"}
        series (.addSeries ^js chart CandlestickSeries seriesOptions)]
    series))

(defn add-histogram-series! [chart]
  "Add a histogram series to the chart"
  (let [seriesOptions #js {:color "#26a69a"}
        series (.addSeries ^js chart HistogramSeries seriesOptions)]
    series))

(defn add-columns-series! [chart]
  "Add a columns-style histogram series to the chart."
  (let [seriesOptions #js {:color "#26a69a"}
        series (.addSeries ^js chart HistogramSeries seriesOptions)]
    series))

(defn add-line-series! [chart]
  "Add a line series to the chart"
  (let [seriesOptions #js {:color "#2962FF"}
        series (.addSeries ^js chart LineSeries seriesOptions)]
    series))

(defn add-line-with-markers-series! [chart]
  "Add a line series with point markers."
  (let [seriesOptions #js {:color "#2962FF"
                           :pointMarkersVisible true
                           :pointMarkersRadius 3}
        series (.addSeries ^js chart LineSeries seriesOptions)]
    series))

(defn add-step-line-series! [chart]
  "Add a step-line series."
  (let [seriesOptions #js {:color "#2962FF"
                           :lineType line-type-with-steps}
        series (.addSeries ^js chart LineSeries seriesOptions)]
    series))

(defn add-hlc-area-series! [chart]
  "Add an HLC area series."
  (let [seriesOptions #js {:lineColor "#2962FF"
                           :topColor "#2962FF"
                           :bottomColor "rgba(41, 98, 255, 0.28)"}
        series (.addSeries ^js chart AreaSeries seriesOptions)]
    series))

(defn add-volume-series! [chart]
  "Add a volume histogram series to the chart"
  (let [seriesOptions #js {:priceFormat #js {:type "volume"}
                           :priceScaleId ""
                           :scaleMargins #js {:top 0.7 :bottom 0}
                           :color "#26a69a"}
        series (.addSeries ^js chart HistogramSeries seriesOptions)]
    series))

(defn- normalize-main-chart-type
  [chart-type]
  (if (= chart-type :histogram) :columns chart-type))

;; Data transformation functions
(defn- close-value
  [candle]
  (:close candle))

(defn- hlc3-value
  [candle]
  (/ (+ (:high candle) (:low candle) (:close candle)) 3))

(defn transform-data-for-single-value
  [data value-fn]
  "Transform OHLC data to single-value data using `value-fn`."
  (map (fn [candle]
         {:value (value-fn candle)
          :time (:time candle)})
       data))

(defn transform-data-for-columns
  [data]
  "Transform OHLC data to columns data with directional colors."
  (map (fn [candle]
         {:value (:close candle)
          :time (:time candle)
          :color (if (>= (:close candle) (:open candle)) "#26a69a" "#ef5350")})
       data))

(defn transform-data-for-heikin-ashi
  [data]
  "Transform raw candles into Heikin Ashi candles."
  (loop [remaining data
         prev-ha-open nil
         prev-ha-close nil
         acc []]
    (if (empty? remaining)
      acc
      (let [candle (first remaining)
            ha-close (/ (+ (:open candle)
                           (:high candle)
                           (:low candle)
                           (:close candle))
                        4)
            ha-open (if (and (number? prev-ha-open)
                             (number? prev-ha-close))
                      (/ (+ prev-ha-open prev-ha-close) 2)
                      (/ (+ (:open candle) (:close candle)) 2))
            ha-high (apply max [(:high candle) ha-open ha-close])
            ha-low (apply min [(:low candle) ha-open ha-close])]
        (recur (rest remaining)
               ha-open
               ha-close
               (conj acc {:time (:time candle)
                          :open ha-open
                          :high ha-high
                          :low ha-low
                          :close ha-close}))))))

(defn transform-data-for-high-low
  [data]
  "Transform candles into solid high-low range bars."
  (map (fn [candle]
         {:time (:time candle)
          :open (:low candle)
          :high (:high candle)
          :low (:low candle)
          :close (:high candle)})
       data))

(defn transform-data-for-volume [data]
  "Transform OHLC data to volume data for volume chart"
  (map (fn [candle]
         {:value (:volume candle)
          :time (:time candle)
          :color (if (>= (:close candle) (:open candle)) "#26a69a" "#ef5350")}) data))

(defn- transform-main-series-data
  [data chart-type]
  (case (normalize-main-chart-type chart-type)
    (:area :baseline :line :line-with-markers :step-line)
    (transform-data-for-single-value data close-value)

    :hlc-area
    (transform-data-for-single-value data hlc3-value)

    :columns
    (transform-data-for-columns data)

    :heikin-ashi
    (transform-data-for-heikin-ashi data)

    :high-low
    (transform-data-for-high-low data)

    (:bar :candlestick :hollow-candles)
    data

    ;; Default fallback keeps the chart rendering resilient.
    data))

(defn- extract-series-prices
  [transformed-data chart-type]
  (case (normalize-main-chart-type chart-type)
    (:area :baseline :line :line-with-markers :step-line :hlc-area :columns)
    (map :value transformed-data)

    (:bar :high-low :candlestick :hollow-candles :heikin-ashi)
    (mapcat (fn [c] [(:open c) (:high c) (:low c) (:close c)]) transformed-data)

    (map :value transformed-data)))

(defn- infer-series-price-format
  "Infer Lightweight Charts price format options based on transformed data."
  [transformed-data chart-type]
  (let [prices (->> (extract-series-prices transformed-data chart-type)
                    (map (fn [v]
                           (if (number? v) v (js/parseFloat v))))
                    (filter (fn [v]
                              (and (number? v) (not (js/isNaN v)))))
                    vec)
        positive-prices (filter pos? prices)
        reference-price (or (when (seq positive-prices)
                              (apply min positive-prices))
                            (when (seq prices)
                              (apply min (map js/Math.abs prices))))
        decimals (or (fmt/infer-price-decimals reference-price) 2)
        min-move (js/Math.pow 10 (- decimals))]
    #js {:type "price"
         :precision decimals
         :minMove min-move}))

;; Generic data setting function
(defn set-series-data! [series data chart-type]
  "Set data for any series type with appropriate transformation."
  (let [transformed-data (transform-main-series-data data chart-type)]
    (.applyOptions ^js series #js {:priceFormat (infer-series-price-format transformed-data chart-type)})
    (.setData series (clj->js transformed-data))))

(defn set-volume-data! [volume-series data]
  "Set volume data for volume series"
  (let [volume-data (transform-data-for-volume data)]
    (.setData volume-series (clj->js volume-data))))

;; Generic series creation function
(defn add-series! [chart chart-type]
  "Add a series of the specified type to the chart"
  (case (normalize-main-chart-type chart-type)
    :area (add-area-series! chart)
    :bar (add-bar-series! chart)
    :high-low (add-high-low-series! chart)
    :baseline (add-baseline-series! chart)
    :hlc-area (add-hlc-area-series! chart)
    :candlestick (add-candlestick-series! chart)
    :hollow-candles (add-hollow-candles-series! chart)
    :heikin-ashi (add-heikin-ashi-series! chart)
    :columns (add-columns-series! chart)
    :line (add-line-series! chart)
    :line-with-markers (add-line-with-markers-series! chart)
    :step-line (add-step-line-series! chart)
    (add-candlestick-series! chart))) ; Default fallback

(defn fit-content! [chart]
  "Fit content to the chart viewport"
  (.fitContent ^js (.timeScale ^js chart))) 

(def ^:private visible-range-storage-key-prefix "chart-visible-time-range")

(defn- normalize-visible-range-kind
  [kind]
  (cond
    (= kind :time) :time
    (= kind :logical) :logical
    (= kind "time") :time
    (= kind "logical") :logical
    :else nil))

(defn- parse-range-number
  [value]
  (cond
    (number? value) value
    (string? value) (let [parsed (js/parseFloat value)]
                      (when-not (js/isNaN parsed) parsed))
    :else nil))

(defn- normalize-visible-range
  [range-data]
  (let [kind (normalize-visible-range-kind (:kind range-data))
        from (parse-range-number (:from range-data))
        to (parse-range-number (:to range-data))]
    (when (and kind
               (some? from)
               (some? to)
               (<= from to))
      {:kind kind
       :from from
       :to to})))

(defn- visible-range-storage-key
  [timeframe]
  (let [timeframe-token (cond
                          (keyword? timeframe) (name timeframe)
                          (string? timeframe) timeframe
                          :else "default")]
    (str visible-range-storage-key-prefix ":" timeframe-token)))

(defn- persist-visible-range!
  [timeframe range-data]
  (when-let [normalized (normalize-visible-range range-data)]
    (try
      (platform/local-storage-set!
       (visible-range-storage-key timeframe)
       (js/JSON.stringify (clj->js normalized)))
      true
      (catch :default _
        false))))

(defn- load-persisted-visible-range
  [timeframe]
  (try
    (let [raw (platform/local-storage-get (visible-range-storage-key timeframe))]
      (when (seq raw)
        (normalize-visible-range (js->clj (js/JSON.parse raw) :keywordize-keys true))))
    (catch :default _
      nil)))

(defn- visible-range-from-time-scale
  [time-scale]
  (or (try
        (when (fn? (.-getVisibleLogicalRange ^js time-scale))
          (some-> (.getVisibleLogicalRange ^js time-scale)
                  (js->clj :keywordize-keys true)
                  (assoc :kind :logical)
                  normalize-visible-range))
        (catch :default _
          nil))
      (try
        (when (fn? (.-getVisibleRange ^js time-scale))
          (some-> (.getVisibleRange ^js time-scale)
                  (js->clj :keywordize-keys true)
                  (assoc :kind :time)
                  normalize-visible-range))
        (catch :default _
          nil))))

(defn- persist-range-candidate!
  [timeframe kind range]
  (let [range-data (cond
                     (map? range) range
                     (some? range) (js->clj range :keywordize-keys true)
                     :else nil)]
    (when (some? range-data)
      (persist-visible-range! timeframe (assoc range-data :kind kind)))))

(defn apply-persisted-visible-range!
  [chart timeframe]
  (let [time-scale (.timeScale ^js chart)
        persisted (load-persisted-visible-range timeframe)]
    (if (and time-scale persisted)
      (try
        (case (:kind persisted)
          :time (if (fn? (.-setVisibleRange ^js time-scale))
                  (do
                    (.setVisibleRange ^js time-scale
                                      (clj->js {:from (:from persisted)
                                                :to (:to persisted)}))
                    true)
                  false)
          :logical (if (fn? (.-setVisibleLogicalRange ^js time-scale))
                     (do
                       (.setVisibleLogicalRange ^js time-scale
                                                (clj->js {:from (:from persisted)
                                                          :to (:to persisted)}))
                       true)
                     false)
          false)
        (catch :default _
          false))
      false)))

(defn subscribe-visible-range-persistence!
  [chart timeframe]
  (let [time-scale (.timeScale ^js chart)]
    (if-not time-scale
      (fn [] nil)
      (let [persist-current! (fn []
                               (when-let [range-data (visible-range-from-time-scale time-scale)]
                                 (persist-visible-range! timeframe range-data)))
            logical-handler (fn [range]
                              (when-not (persist-range-candidate! timeframe :logical range)
                                (persist-current!)))
            time-handler (fn [range]
                           (when-not (persist-range-candidate! timeframe :time range)
                             (persist-current!)))
            unsubscribe! (cond
                           (fn? (.-subscribeVisibleLogicalRangeChange ^js time-scale))
                           (do
                             (.subscribeVisibleLogicalRangeChange ^js time-scale logical-handler)
                             (fn []
                               (try
                                 (.unsubscribeVisibleLogicalRangeChange ^js time-scale logical-handler)
                                 (catch :default _
                                   nil))))

                           (fn? (.-subscribeVisibleTimeRangeChange ^js time-scale))
                           (do
                             (.subscribeVisibleTimeRangeChange ^js time-scale time-handler)
                             (fn []
                               (try
                                 (.unsubscribeVisibleTimeRangeChange ^js time-scale time-handler)
                                 (catch :default _
                                   nil))))

                           :else
                           (fn [] nil))]
        (fn []
          (unsubscribe!))))))

(defn- build-legend-state [legend-meta]
  (let [symbol (or (:symbol legend-meta) "—")
        timeframe-label (or (:timeframe-label legend-meta) "—")
        venue (or (:venue legend-meta) "Hyperopen")
        header-text (str symbol " · " timeframe-label " · " venue)
        candle-data (or (:candle-data legend-meta) [])
        candle-lookup (when (seq candle-data)
                        (loop [remaining candle-data
                               prev-close nil
                               acc {}]
                          (if (empty? remaining)
                            acc
                            (let [c (first remaining)
                                  acc (assoc acc (:time c) {:candle c :prev-close prev-close})
                                  prev-close (:close c)]
                              (recur (rest remaining) prev-close acc)))))
        latest-candle (last candle-data)
        latest-prev-close (when (> (count candle-data) 1)
                            (:close (nth candle-data (- (count candle-data) 2))))
        latest-entry (when latest-candle
                       {:candle latest-candle
                        :prev-close latest-prev-close})]
    {:header-text header-text
     :candle-lookup candle-lookup
     :latest-entry latest-entry}))

(defn create-legend! [container chart legend-meta]
  "Create legend element that adapts to different chart types"
  ;; Ensure container has relative positioning for absolute legend positioning
  (let [container-style (.-style container)]
    (when (or (not (.-position container-style)) 
              (= (.-position container-style) "static"))
      (set! (.-position container-style) "relative")))
  
  ;; Create legend div element
  (let [legend (js/document.createElement "div")
        legend-font-family (chart-options/resolve-chart-font-family)]
    ;; Fully transparent legend styling
    (set! (.-style legend)
          (str "position: absolute; left: 12px; top: 8px; z-index: 100; "
               "font-size: 12px; font-family: " legend-font-family "; "
               "font-variant-numeric: tabular-nums lining-nums; "
               "font-feature-settings: 'tnum' 1, 'lnum' 1; "
               "line-height: 1.4; font-weight: 500; color: #ffffff; "
               "padding: 6px 10px; border-radius: 6px; box-shadow: none; pointer-events: none;"))
    
    ;; Set initial content
    (set! (.-innerHTML legend) "")
    
    ;; Append to container
    (.appendChild container legend)
    
    (let [state (atom (build-legend-state legend-meta))
          format-price (fn [price]
                         (when (number? price)
                           (fmt/format-trade-price-plain price)))
          format-delta (fn [delta]
                         (when (number? delta)
                           (let [formatted (fmt/format-trade-price-delta delta)]
                             (if (>= delta 0) (str "+" formatted) formatted))))
          format-pct (fn [pct]
                       (when (number? pct)
                         (let [formatted (.toFixed pct 2)]
                           (if (>= pct 0) (str "+" formatted "%") (str formatted "%")))))
          render-legend! (fn [entry]
                           (let [{:keys [header-text]} @state]
                             (if (and entry (:candle entry))
                               (let [c (:candle entry)
                                     baseline (or (:prev-close entry) (:open c))
                                     close (:close c)
                                     delta (when (and close baseline) (- close baseline))
                                     pct (when (and delta baseline (not= baseline 0)) (* 100 (/ delta baseline)))
                                     delta-color (cond
                                                   (nil? delta) "#9ca3af"
                                                   (>= delta 0) "#10b981"
                                                   :else "#ef4444")
                                     o (or (format-price (:open c)) "--")
                                     h (or (format-price (:high c)) "--")
                                     l (or (format-price (:low c)) "--")
                                     cl (or (format-price (:close c)) "--")
                                     delta-str (or (format-delta delta) "--")
                                     pct-str (or (format-pct pct) "--")]
                                 (set! (.-innerHTML legend)
                                       (str "<div style='display:flex; align-items:center; gap:6px; font-weight:600;'>"
                                            "<span style='color:#e5e7eb;'>" header-text "</span>"
                                            "</div>"
                                            "<div style='display:flex; align-items:center; gap:8px;'>"
                                            "<span style='color:#9ca3af'>O</span> " o
                                            " <span style='color:#9ca3af'>H</span> " h
                                            " <span style='color:#9ca3af'>L</span> " l
                                            " <span style='color:#9ca3af'>C</span> " cl
                                            " <span style='color:" delta-color "; font-weight:600;'>" delta-str " (" pct-str ")</span>"
                                            "</div>")))
                               (set! (.-innerHTML legend)
                                     (str "<div style='display:flex; align-items:center; gap:6px; font-weight:600;'>"
                                          "<span style='color:#e5e7eb;'>" header-text "</span>"
                                          "</div>"
                                          "<div style='display:flex; align-items:center; gap:8px;'>"
                                          "<span style='color:#9ca3af'>O</span> --"
                                          " <span style='color:#9ca3af'>H</span> --"
                                          " <span style='color:#9ca3af'>L</span> --"
                                          " <span style='color:#9ca3af'>C</span> --"
                                          " <span style='color:#9ca3af'>-- (--)</span>"
                                          "</div>")))))
          update-legend (fn [param]
                          (let [{:keys [candle-lookup latest-entry]} @state
                                entry (when (and param (.-time param))
                                        (get candle-lookup (.-time param)))]
                            (render-legend! (or entry latest-entry))))
          update! (fn [new-meta]
                    (reset! state (build-legend-state new-meta))
                    (update-legend nil))
          destroy! (fn []
                     (try
                       (.unsubscribeCrosshairMove ^js chart update-legend)
                       (catch :default _ nil))
                     (when (.-parentNode legend)
                       (.removeChild (.-parentNode legend) legend)))]
      ;; Subscribe to crosshair move events
      (.subscribeCrosshairMove ^js chart update-legend)
      ;; Initialize
      (update-legend nil)
      #js {:update update! :destroy destroy!})))

;; Indicator series functions
(defn- indicator-line-options
  [series-def]
  #js {:color (or (:color series-def) "#38bdf8")
       :lineWidth (or (:line-width series-def) 2)})

(defn- indicator-histogram-options
  [series-def]
  #js {:color (or (:color series-def) "#10b981")
       :priceFormat #js {:type "price"}
       :base (or (:base series-def) 0)})

(defn add-indicator-series!
  [chart series-def pane-index]
  "Add an indicator series in the requested pane."
  (case (:series-type series-def)
    :histogram (.addSeries ^js chart HistogramSeries (indicator-histogram-options series-def) pane-index)
    (.addSeries ^js chart LineSeries (indicator-line-options series-def) pane-index)))

(defn set-indicator-data! [series data]
  "Set indicator data and preserve whitespace points."
  (.setData series (clj->js data)))

(defn set-main-series-markers!
  [chart-obj markers]
  "Attach/update markers on the main price series."
  (when-let [main-series (when chart-obj (.-mainSeries ^js chart-obj))]
    (let [existing-plugin (.-mainSeriesMarkersPlugin ^js chart-obj)
          existing-series (.-mainSeriesMarkersSeries ^js chart-obj)
          plugin (if (and existing-plugin (identical? existing-series main-series))
                   existing-plugin
                   (let [created (createSeriesMarkers main-series #js [])]
                     (set! (.-mainSeriesMarkersPlugin ^js chart-obj) created)
                     (set! (.-mainSeriesMarkersSeries ^js chart-obj) main-series)
                     created))
          marker-data (if (sequential? markers) markers [])]
      (.setMarkers ^js plugin (clj->js marker-data)))))

;; Chart with volume support using separate panes
(defn create-chart-with-volume-and-series! [container chart-type data]
  "Create a chart with main series and volume series in separate panes"
  (let [chart (create-chart! container)
        ;; Add main series to pane 0 (default)
        main-series (add-series! chart chart-type)
        ;; Add volume series to pane 1 (separate pane)
        volume-series (.addSeries ^js chart HistogramSeries 
                                  #js {:color "#26a69a"
                                       :priceFormat #js {:type "volume"}}
                                  1)] ; Pane index 1
    
    ;; Set data for both series
    (set-series-data! main-series data chart-type)
    (set-volume-data! volume-series data)
    
    ;; Configure the volume pane height
    (let [volume-pane (aget (.panes ^js chart) 1)]
      (.setHeight ^js volume-pane 150))
    
    (fit-content! chart)
    
    ;; Return both chart and series for legend creation
    #js {:chart chart :mainSeries main-series :volumeSeries volume-series}))

;; Enhanced chart creation with indicators support
(defn- indicator-pane-allocation
  [indicators]
  (loop [remaining indicators
         next-pane-index 1
         assignments []]
    (if (empty? remaining)
      {:next-pane-index next-pane-index
       :assignments assignments}
      (let [indicator (first remaining)
            pane-index (if (= :overlay (:pane indicator))
                         0
                         next-pane-index)
            updated-next-pane (if (= :overlay (:pane indicator))
                                next-pane-index
                                (inc next-pane-index))
            indicator-assignments (mapv (fn [series-def]
                                          {:indicator indicator
                                           :series-def series-def
                                           :pane-index pane-index})
                                        (:series indicator))]
        (recur (rest remaining)
               updated-next-pane
               (into assignments indicator-assignments))))))

(defn create-chart-with-indicators! [container chart-type data indicators]
  "Create a chart with indicators, main series, and volume series."
  (let [chart (create-chart! container)
        {:keys [next-pane-index assignments]} (indicator-pane-allocation indicators)
        ;; Add indicator series first so overlays stay beneath candles.
        indicator-series (mapv (fn [{:keys [indicator series-def pane-index]}]
                                 (let [series (add-indicator-series! chart series-def pane-index)]
                                   (set-indicator-data! series (:data series-def))
                                   {:series series
                                    :indicator indicator
                                    :seriesDef series-def
                                    :paneIndex pane-index}))
                               assignments)
        ;; Add main series to pane 0.
        main-series (add-series! chart chart-type)
        ;; Keep volume in the pane after all indicator panes.
        volume-series (.addSeries ^js chart HistogramSeries 
                                  #js {:color "#26a69a"
                                       :priceFormat #js {:type "volume"}}
                                  next-pane-index)]
    
    ;; Set data for main and volume series.
    (set-series-data! main-series data chart-type)
    (set-volume-data! volume-series data)
    
    ;; Configure indicator panes and volume pane heights.
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

;; Legacy function names for backward compatibility
(defn create-candlestick-chart! [container]
  "Create a chart (legacy function name)"
  (create-chart! container))

(defn set-candlestick-data! [series data]
  "Set candlestick data (legacy function name)"
  (.setData series (clj->js data))) 
