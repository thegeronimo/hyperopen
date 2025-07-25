(ns hyperopen.views.trading-chart.core
  (:require [hyperopen.views.trading-chart.utils.chart-interop :as ci]
            [hyperopen.views.trading-chart.utils.data-processing :as dp]))

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
      [:div.relative
       [:button.flex.items-center.space-x-1.px-3.py-1.text-sm.font-medium.text-gray-300.hover:text-white.hover:bg-gray-700.rounded.transition-colors
        {:on {:click [[:actions/toggle-timeframes-dropdown]]}}
        [:span (if timeframes-dropdown-visible "▲" "▼")]]
       ;; Dropdown menu (toggled with state)
       [:div.absolute.top-full.left-0.mt-1.bg-gray-800.border.border-gray-600.rounded.shadow-lg.z-50.min-w-32
        {:class (if timeframes-dropdown-visible "block" "hidden")}
        ;; Minutes section
        [:div.px-3.py-2.text-xs.text-gray-400.uppercase.font-semibold.border-b.border-gray-600 "Minutes"]
        [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
         {:class (if (= selected-timeframe :1m) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
          :on {:click [[:actions/select-chart-timeframe :1m]]}}
         "1 min"]
        [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
         {:class (if (= selected-timeframe :3m) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
          :on {:click [[:actions/select-chart-timeframe :3m]]}}
         "3 min"]
        [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
         {:class (if (= selected-timeframe :5m) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
          :on {:click [[:actions/select-chart-timeframe :5m]]}}
         "5 min"]
        [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
         {:class (if (= selected-timeframe :15m) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
          :on {:click [[:actions/select-chart-timeframe :15m]]}}
         "15 min"]
        [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
         {:class (if (= selected-timeframe :30m) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
          :on {:click [[:actions/select-chart-timeframe :30m]]}}
         "30 min"]
        ;; Hours section
        [:div.px-3.py-2.text-xs.text-gray-400.uppercase.font-semibold.border-b.border-gray-600.border-t "Hours"]
        [:button.relative.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
         {:class (if (= selected-timeframe :1h) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
          :on {:click [[:actions/select-chart-timeframe :1h]]}}
         "1 hour"
         [:span.absolute.right-2.top-2.text-yellow-400.text-xs "⭐"]]
        [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
         {:class (if (= selected-timeframe :2h) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
          :on {:click [[:actions/select-chart-timeframe :2h]]}}
         "2 hours"]
        [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
         {:class (if (= selected-timeframe :4h) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
          :on {:click [[:actions/select-chart-timeframe :4h]]}}
         "4 hours"]
        [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
         {:class (if (= selected-timeframe :8h) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
          :on {:click [[:actions/select-chart-timeframe :8h]]}}
         "8 hours"]
        [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
         {:class (if (= selected-timeframe :12h) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
          :on {:click [[:actions/select-chart-timeframe :12h]]}}
         "12 hours"]
        ;; Days section
        [:div.px-3.py-2.text-xs.text-gray-400.uppercase.font-semibold.border-b.border-gray-600.border-t "Days"]
        [:button.relative.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
         {:class (if (= selected-timeframe :1d) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
          :on {:click [[:actions/select-chart-timeframe :1d]]}}
         "1 day"
         [:span.absolute.right-2.top-2.text-yellow-400.text-xs "⭐"]]
        [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
         {:class (if (= selected-timeframe :3d) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
          :on {:click [[:actions/select-chart-timeframe :3d]]}}
         "3 days"]
        [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
         {:class (if (= selected-timeframe :1w) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
          :on {:click [[:actions/select-chart-timeframe :1w]]}}
         "1 week"]
        [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
         {:class (if (= selected-timeframe :1M) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
          :on {:click [[:actions/select-chart-timeframe :1M]]}}
         "1 month"]]]]
   
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