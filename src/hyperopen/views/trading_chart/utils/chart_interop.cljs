(ns hyperopen.views.trading-chart.utils.chart-interop
  (:require ["lightweight-charts" :refer [createChart CandlestickSeries]]))

;; Candlestick chart implementation
(defn create-candlestick-chart! [container]
  "Create a chart with candlestick options"
  (let [chartOptions #js {:layout #js {:textColor "#e5e7eb" 
                                       :background #js {:type "solid" 
                                                       :color "#1f2937"}}
                          :grid #js {:vertLines #js {:color "#374151"}
                                    :horzLines #js {:color "#374151"}}
                          :rightPriceScale #js {:borderColor "#374151"}
                          :timeScale #js {:borderColor "#374151"}}]
    (let [chart (createChart container chartOptions)]
      chart)))

(defn add-candlestick-series! [chart]
  "Add a candlestick series to the chart"
  (let [seriesOptions #js {:upColor "#26a69a"
                           :downColor "#ef5350"
                           :borderVisible false
                           :wickUpColor "#26a69a"
                           :wickDownColor "#ef5350"}
        series (.addSeries ^js chart CandlestickSeries seriesOptions)]
    series))

(defn set-candlestick-data! [series]
  "Set candlestick data exactly like the JavaScript example"
  (let [data #js [#js {:open 10 :high 10.63 :low 9.49 :close 9.55 :time 1642427876}
                  #js {:open 9.55 :high 10.30 :low 9.42 :close 9.94 :time 1642514276}
                  #js {:open 9.94 :high 10.17 :low 9.92 :close 9.78 :time 1642600676}
                  #js {:open 9.78 :high 10.59 :low 9.18 :close 9.51 :time 1642687076}
                  #js {:open 9.51 :high 10.46 :low 9.10 :close 10.17 :time 1642773476}
                  #js {:open 10.17 :high 10.96 :low 10.16 :close 10.47 :time 1642859876}
                  #js {:open 10.47 :high 11.39 :low 10.40 :close 10.81 :time 1642946276}
                  #js {:open 10.81 :high 11.60 :low 10.30 :close 10.75 :time 1643032676}
                  #js {:open 10.75 :high 11.60 :low 10.49 :close 10.93 :time 1643119076}
                  #js {:open 10.93 :high 11.53 :low 10.76 :close 10.96 :time 1643205476}]]
    (.setData series data)))

(defn fit-content! [chart]
  "Fit content to the chart viewport"
  (.fitContent ^js (.timeScale ^js chart))) 

(defn create-legend! [container chart candlestick-series]
  "Create legend element following TradingView documentation pattern"
  ;; Ensure container has relative positioning for absolute legend positioning
  (let [container-style (.-style container)]
    (when (or (not (.-position container-style)) 
              (= (.-position container-style) "static"))
      (set! (.-position container-style) "relative")))
  
  ;; Create legend div element
  (let [legend (js/document.createElement "div")]
    ;; Professional trading platform legend styling
    (set! (.-style legend) "position: absolute; left: 12px; top: 12px; z-index: 100; font-size: 13px; font-family: 'SF Pro Display', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.4; font-weight: 500; color: #ffffff; background: rgba(0, 0, 0, 0.75); padding: 8px 12px; border-radius: 6px; backdrop-filter: blur(4px); border: 1px solid rgba(255, 255, 255, 0.1); box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);")
    
    ;; Set initial content
    (set! (.-innerHTML legend) "Hover over candles")
    
    ;; Append to container
    (.appendChild container legend)
    
    ;; Format price helper
    (let [format-price (fn [price] (.toFixed price 2))
          
          ;; Update legend content
          update-legend (fn [param]
                         (if (and param (.-time param))
                           ;; Valid crosshair point - show OHLC
                           (let [data-map (.-seriesData param)
                                 bar (.get data-map candlestick-series)]
                             (if bar
                               (let [o (.-open bar)
                                     h (.-high bar)
                                     l (.-low bar)
                                     c (.-close bar)]
                                 (set! (.-innerHTML legend) 
                                       (str "<span style='color: #888; font-weight: 400;'>O</span> " (format-price o) 
                                            " <span style='color: #888; font-weight: 400;'>H</span> " (format-price h) 
                                            " <span style='color: #888; font-weight: 400;'>L</span> " (format-price l) 
                                            " <span style='color: #888; font-weight: 400;'>C</span> " (format-price c))))
                               (set! (.-innerHTML legend) "No data")))
                           ;; No crosshair point - show hint
                           (set! (.-innerHTML legend) "Hover over candles")))]
      
      ;; Subscribe to crosshair move events
      (.subscribeCrosshairMove ^js chart update-legend)
      
      ;; Initialize
      (update-legend nil)))) 