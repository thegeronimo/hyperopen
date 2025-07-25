(ns hyperopen.views.trading-chart.core
  (:require [hyperopen.views.trading-chart.utils.chart-interop :as ci]
            [hyperopen.views.trading-chart.utils.data-processing :as dp]
            [hyperopen.views.trading-chart.timeframe-dropdown :refer [timeframe-dropdown]]))

;; Top menu component with timeframe selection and bars indicator
(defn chart-top-menu [state]
  (let [timeframes-dropdown-visible (get-in state [:chart-options :timeframes-dropdown-visible])
        selected-timeframe (get-in state [:chart-options :selected-timeframe] :1d)]
    [:div.flex.items-center.justify-between.bg-gray-900.border-b.border-gray-700.px-4.py-2
     ;; Left side - Favorite timeframes + dropdown
     [:div.flex.items-center.space-x-1
      ;; Favorite timeframes (with star indicators)
      [:button.relative.px-3.py-1.text-sm.font-medium.rounded.transition-colors
       {:class (if (= selected-timeframe :1h)
                 ["text-white" "bg-blue-600"]
                 ["text-gray-300" "hover:text-white" "hover:bg-gray-700"])
        :on {:click [[:actions/select-chart-timeframe :1h]]}}
       "1h"]
      [:button.relative.px-3.py-1.text-sm.font-medium.rounded.transition-colors
       {:class (if (= selected-timeframe :1d)
                 ["text-white" "bg-blue-600"]
                 ["text-gray-300" "hover:text-white" "hover:bg-gray-700"])
        :on {:click [[:actions/select-chart-timeframe :1d]]}}
       "1d"]
      ;; Dropdown for additional timeframes
      (timeframe-dropdown {:selected-timeframe selected-timeframe
                          :timeframes-dropdown-visible timeframes-dropdown-visible})]
   
     ;; Center - Chart type and indicators
     [:div.flex.items-center.space-x-4
      [:div.flex.items-center.space-x-2
       [:button.flex.items-center.space-x-1.px-3.py-1.text-sm.font-medium.text-gray-300.hover:text-white.hover:bg-gray-700.rounded.transition-colors
        [:span "📊"]
        [:span "Bars"]
        [:span "▼"]]
       [:button.flex.items-center.space-x-1.px-3.py-1.text-sm.font-medium.text-gray-300.hover:text-white.hover:bg-gray-700.rounded.transition-colors
        [:span "📈"]
        [:span "fx Indicators"]
        [:span "▼"]]]]]))

;; Candlestick chart component
(defn candlestick-chart-canvas [candle-data]
  (let [mount! (fn [{:keys [:replicant/life-cycle :replicant/node]}]
                 (case life-cycle
                   :replicant.life-cycle/mount
                   (try
                     ;; Create chart
                     (let [chart (ci/create-candlestick-chart! node)
                           candlestick-series (ci/add-candlestick-series! chart)]
                       (ci/set-candlestick-data! candlestick-series candle-data)
                       (ci/fit-content! chart)
                       ;; Create legend element following TradingView docs
                       (ci/create-legend! node chart candlestick-series))
                     (catch :default e
                       (js/console.error "Error in candlestick chart:" e)))
                   :replicant.life-cycle/unmount
                   nil
                   nil))]
    [:div.w-full.h-96.bg-gray-800.relative
     {:replicant/key (str "chart-" (hash candle-data))
      :replicant/on-render mount!
      :style {:width "600px" :height "400px"}}]))

(defn trading-chart-view [state]
  (let [active-asset (:active-asset state)
        candles-map (:candles state)
        ;; Use selected timeframe from state
        selected-timeframe (get-in state [:chart-options :selected-timeframe] :1d)
        api-response (get-in candles-map [active-asset selected-timeframe] {})
        ;; Check for error state
        has-error? (contains? api-response :error)
        ;; Handle both possible data structures: direct array or wrapped in :data
        raw-candles (if (vector? api-response)
                      api-response  ; Direct array
                      (get api-response :data []))  ; Wrapped in :data
        candle-data (dp/process-candle-data raw-candles)]
    [:div.w-full.max-w-6xl.mx-auto.p-4
     [:h1.text-2xl.mb-4 (str "Candlestick Chart - " (or active-asset "No Asset Selected"))]
     ;; Add the top menu above the chart
     (chart-top-menu state)
     (if has-error?
       [:div.text-red-500.p-4 "Error fetching chart data."]
       (candlestick-chart-canvas candle-data))])) 