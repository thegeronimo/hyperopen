(ns hyperopen.views.trading-chart.utils.chart-interop
  (:require ["lightweight-charts" :refer [createChart AreaSeries BarSeries BaselineSeries CandlestickSeries HistogramSeries LineSeries]]))

;; Generic chart creation with volume support
(defn create-chart-with-volume! [container]
  "Create a chart with volume pane"
  (let [chartOptions #js {:layout #js {:textColor "#e5e7eb" 
                                       :background #js {:type "solid" 
                                                       :color "#1f2937"}}
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
  (let [chartOptions #js {:layout #js {:textColor "#e5e7eb" 
                                       :background #js {:type "solid" 
                                                       :color "#1f2937"}}
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

(defn create-legend! [container chart series chart-type]
  "Create legend element that adapts to different chart types"
  ;; Ensure container has relative positioning for absolute legend positioning
  (let [container-style (.-style container)]
    (when (or (not (.-position container-style)) 
              (= (.-position container-style) "static"))
      (set! (.-position container-style) "relative")))
  
  ;; Create legend div element
  (let [legend (js/document.createElement "div")]
    ;; Fully transparent legend styling
    (set! (.-style legend) "position: absolute; left: 12px; top: 12px; z-index: 100; font-size: 13px; font-family: 'SF Pro Display', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.4; font-weight: 500; color: #ffffff; padding: 8px 12px; border-radius: 6px; box-shadow: none;")
    
    ;; Set initial content
    (set! (.-innerHTML legend) "")
    
    ;; Append to container
    (.appendChild container legend)
    
    ;; Format price helper
    (let [format-price (fn [price] (.toFixed price 2))
          format-volume (fn [volume] 
                         (cond
                           (>= volume 1000000000) (str (.toFixed (/ volume 1000000000) 1) "B")
                           (>= volume 1000000) (str (.toFixed (/ volume 1000000) 1) "M")
                           (>= volume 1000) (str (.toFixed (/ volume 1000) 1) "K")
                           :else (.toFixed volume 0)))
          
          ;; Update legend content based on chart type
          update-legend (fn [param]
                         (if (and param (.-time param))
                           ;; Valid crosshair point - show appropriate data
                           (let [data-map (.-seriesData param)
                                 bar (.get data-map series)]
                             (if bar
                               (case chart-type
                                 (:bar :candlestick)
                                 (let [o (.-open bar)
                                       h (.-high bar)
                                       l (.-low bar)
                                       c (.-close bar)]
                                   (set! (.-innerHTML legend) 
                                         (str "<span style='color: #888; font-weight: 400;'>O</span> " (format-price o) 
                                              " <span style='color: #888; font-weight: 400;'>H</span> " (format-price h) 
                                              " <span style='color: #888; font-weight: 400;'>L</span> " (format-price l) 
                                              " <span style='color: #888; font-weight: 400;'>C</span> " (format-price c))))
                                 (:area :baseline :line :histogram)
                                 (let [value (.-value bar)]
                                   (set! (.-innerHTML legend) 
                                         (str "<span style='color: #888; font-weight: 400;'>Value</span> " (format-price value))))
                                 ;; Default
                                 (set! (.-innerHTML legend) "No data"))
                               (set! (.-innerHTML legend) "No data")))
                           ;; No crosshair point - show hint
                           (set! (.-innerHTML legend) "")))]
      
      ;; Subscribe to crosshair move events
      (.subscribeCrosshairMove ^js chart update-legend)
      
      ;; Initialize
      (update-legend nil))))

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

;; Legacy function names for backward compatibility
(defn create-candlestick-chart! [container]
  "Create a chart (legacy function name)"
  (create-chart! container))

(defn set-candlestick-data! [series data]
  "Set candlestick data (legacy function name)"
  (.setData series (clj->js data))) 