(ns hyperopen.views.trading-chart.utils.chart-interop
  (:require ["lightweight-charts" :refer [createChart AreaSeries BarSeries BaselineSeries CandlestickSeries HistogramSeries LineSeries]]
            [hyperopen.utils.formatting :as fmt]))

;; Generic chart creation with volume support
(defn create-chart-with-volume! [container]
  "Create a chart with volume pane"
  (let [chartOptions #js {:layout #js {:textColor "#e5e7eb" 
                                       :background #js {:type "solid" 
                                                       :color "rgb(30, 41, 55)"}}
                          :grid #js {:vertLines #js {:color "#374151"}
                                    :horzLines #js {:color "#374151"}}
                          :rightPriceScale #js {:borderColor "#374151"}
                          :timeScale #js {:borderColor "#374151"}
                          :height 400}
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
  (let [chartOptions #js {:autoSize true
                          :layout #js {:textColor "#e5e7eb" 
                                       :background #js {:type "solid" 
                                                       :color "rgb(30, 41, 55)"}}
                          :grid #js {:vertLines #js {:color "#374151"}
                                    :horzLines #js {:color "#374151"}}
                          :rightPriceScale #js {:borderColor "#374151"}
                          :timeScale #js {:borderColor "#374151"}}]
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

(defn add-histogram-series! [chart]
  "Add a histogram series to the chart"
  (let [seriesOptions #js {:color "#26a69a"}
        series (.addSeries ^js chart HistogramSeries seriesOptions)]
    series))

(defn add-line-series! [chart]
  "Add a line series to the chart"
  (let [seriesOptions #js {:color "#2962FF"}
        series (.addSeries ^js chart LineSeries seriesOptions)]
    series))

(defn add-volume-series! [chart]
  "Add a volume histogram series to the chart"
  (let [seriesOptions #js {:priceFormat #js {:type "volume"}
                           :priceScaleId ""
                           :scaleMargins #js {:top 0.7 :bottom 0}
                           :color "#26a69a"}
        series (.addSeries ^js chart HistogramSeries seriesOptions)]
    series))

(defn- series-prices
  "Extract numeric prices from chart data for precision inference."
  [data chart-type]
  (let [values (case chart-type
                 (:area :baseline :line :histogram) (map :close data)
                 (:bar :candlestick) (mapcat (fn [c] [(:open c) (:high c) (:low c) (:close c)]) data)
                 (map :close data))]
    (->> values
         (map (fn [v]
                (if (number? v) v (js/parseFloat v))))
         (filter (fn [v]
                   (and (number? v) (not (js/isNaN v)))))
         vec)))

(defn- infer-series-price-format
  "Infer Lightweight Charts price format options based on current data."
  [data chart-type]
  (let [prices (series-prices data chart-type)
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

;; Data transformation functions
(defn transform-data-for-single-value [data]
  "Transform OHLC data to single value (close price) for area, baseline, line, histogram"
  (map (fn [candle]
         {:value (:close candle)
          :time (:time candle)}) data))

(defn transform-data-for-volume [data]
  "Transform OHLC data to volume data for volume chart"
  (map (fn [candle]
         {:value (:volume candle)
          :time (:time candle)
          :color (if (>= (:close candle) (:open candle)) "#26a69a" "#ef5350")}) data))

;; Generic data setting function
(defn set-series-data! [series data chart-type]
  "Set data for any series type with appropriate transformation"
  (let [transformed-data (case chart-type
                           (:area :baseline :line :histogram) (transform-data-for-single-value data)
                           (:bar :candlestick) data)]
    (.applyOptions ^js series #js {:priceFormat (infer-series-price-format data chart-type)})
    (.setData series (clj->js transformed-data))))

(defn set-volume-data! [volume-series data]
  "Set volume data for volume series"
  (let [volume-data (transform-data-for-volume data)]
    (.setData volume-series (clj->js volume-data))))

;; Generic series creation function
(defn add-series! [chart chart-type]
  "Add a series of the specified type to the chart"
  (case chart-type
    :area (add-area-series! chart)
    :bar (add-bar-series! chart)
    :baseline (add-baseline-series! chart)
    :candlestick (add-candlestick-series! chart)
    :histogram (add-histogram-series! chart)
    :line (add-line-series! chart)
    (add-candlestick-series! chart))) ; Default fallback

(defn fit-content! [chart]
  "Fit content to the chart viewport"
  (.fitContent ^js (.timeScale ^js chart))) 

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
  (let [legend (js/document.createElement "div")]
    ;; Fully transparent legend styling
    (set! (.-style legend) "position: absolute; left: 12px; top: 8px; z-index: 100; font-size: 12px; font-family: 'SF Pro Display', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.4; font-weight: 500; color: #ffffff; padding: 6px 10px; border-radius: 6px; box-shadow: none; pointer-events: none;")
    
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
(defn add-indicator-line-series! [chart color]
  "Add a line series for indicators"
  (let [seriesOptions #js {:color color
                           :lineWidth 2}
        series (.addSeries ^js chart LineSeries seriesOptions)]
    series))

(defn set-indicator-data! [series data]
  "Set indicator data for line series - preserves whitespace data points"
  (.setData series (clj->js data)))

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
(defn create-chart-with-indicators! [container chart-type data indicators]
  "Create a chart with indicators, main series, and volume series - indicators appear underneath"
  (let [chart (create-chart! container)
        indicator-colors ["#FF6B6B" "#4ECDC4" "#45B7D1" "#96CEB4" "#FFEAA7"]
        ;; Add indicator series FIRST so they appear underneath
        indicator-series (map-indexed 
                          (fn [idx indicator]
                            (let [color (nth indicator-colors (mod idx (count indicator-colors)))
                                  series (add-indicator-line-series! chart color)]
                              (set-indicator-data! series (:data indicator))
                              {:series series :indicator indicator}))
                          indicators)
        ;; Add main series to pane 0 (after indicators)
        main-series (add-series! chart chart-type)
        ;; Add volume series to pane 1 (separate pane)
        volume-series (.addSeries ^js chart HistogramSeries 
                                  #js {:color "#26a69a"
                                       :priceFormat #js {:type "volume"}}
                                  1)] ; Pane index 1
    
    ;; Set data for main and volume series
    (set-series-data! main-series data chart-type)
    (set-volume-data! volume-series data)
    
    ;; Configure the volume pane height
    (let [volume-pane (aget (.panes ^js chart) 1)]
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
