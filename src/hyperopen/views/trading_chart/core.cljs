(ns hyperopen.views.trading-chart.core
  (:require [hyperopen.views.trading-chart.utils.chart-interop :as ci]
            [hyperopen.views.trading-chart.utils.data-processing :as dp]
            [hyperopen.views.trading-chart.timeframe-dropdown :refer [timeframe-dropdown]]
            [hyperopen.views.trading-chart.chart-type-dropdown :refer [chart-type-dropdown]]))

;; Main timeframes for quick access buttons
(def main-timeframes [:5m :1h :1d])

;; Top menu component with timeframe selection and bars indicator
(defn chart-top-menu [state]
  (let [timeframes-dropdown-visible (get-in state [:chart-options :timeframes-dropdown-visible])
        selected-timeframe (get-in state [:chart-options :selected-timeframe] :1d)
        chart-type-dropdown-visible (get-in state [:chart-options :chart-type-dropdown-visible])
        selected-chart-type (get-in state [:chart-options :selected-chart-type] :candlestick)]
    [:div.flex.items-center.bg-gray-900.border-b.border-gray-700.px-4.py-2.w-full.space-x-4
     ;; Left side - Favorite timeframes + dropdown
     [:div.flex.items-center.space-x-1
      ;; Main timeframe buttons
      (for [key main-timeframes]
        [:button.relative.px-3.py-1.text-sm.font-medium.rounded.transition-colors
         {:key key
          :class (if (= selected-timeframe key)
                   ["text-white" "bg-blue-600"]
                   ["text-gray-300" "hover:text-white" "hover:bg-gray-700"])
          :on {:click [[:actions/select-chart-timeframe key]]}}
         (name key)])
      ;; Additional timeframe button visible only when selected timeframe is not one of the main 3
      (when-not (contains? (set main-timeframes) selected-timeframe)
        [:button.relative.px-3.py-1.text-sm.font-medium.rounded.transition-colors
         {:class ["text-white" "bg-blue-600"]
          :on {:click [[:actions/toggle-timeframes-dropdown]]}}
         (name selected-timeframe)])

      ;; Dropdown for additional timeframes
      (timeframe-dropdown {:selected-timeframe selected-timeframe
                          :timeframes-dropdown-visible timeframes-dropdown-visible})]
     
     ;; Vertical divider
     [:div.w-px.h-6.bg-gray-700]
   
     ;; Chart type and indicators section
     [:div.flex.items-center.space-x-2
      ;; Chart type dropdown
      (chart-type-dropdown {:selected-chart-type selected-chart-type
                           :chart-type-dropdown-visible chart-type-dropdown-visible})
      [:button.flex.items-center.space-x-1.px-3.py-1.text-sm.font-medium.text-gray-300.hover:text-white.hover:bg-gray-700.rounded.transition-colors
       [:span "📈"]
       [:span "fx Indicators"]
       [:span "▼"]]]]))

;; Generic chart component that supports all chart types with volume
(defn chart-canvas [candle-data chart-type]
  (let [mount! (fn [{:keys [:replicant/life-cycle :replicant/node]}]
                 (case life-cycle
                   :replicant.life-cycle/mount
                   (try
                     ;; Create chart with volume support
                     (let [chart-obj (ci/create-chart-with-volume-and-series! node chart-type candle-data)
                           chart (.-chart chart-obj)
                           main-series (.-mainSeries chart-obj)
                           volume-series (.-volumeSeries chart-obj)]
                       ;; Create legend for main series only
                       (ci/create-legend! node chart main-series chart-type))
                     (catch :default e
                       (js/console.error "Error in chart:" e)))
                   :replicant.life-cycle/unmount
                   nil
                   nil))]
    [:div.w-full.h-96.bg-gray-800.relative
     {:replicant/key (str "chart-" chart-type "-" (hash candle-data))
      :replicant/on-render mount!}]))

(defn trading-chart-view [state]
  (let [active-asset (:active-asset state)
        candles-map (:candles state)
        ;; Use selected timeframe from state
        selected-timeframe (get-in state [:chart-options :selected-timeframe] :1d)
        selected-chart-type (get-in state [:chart-options :selected-chart-type] :candlestick)
        api-response (get-in candles-map [active-asset selected-timeframe] {})
        ;; Check for error state
        has-error? (contains? api-response :error)
        ;; Handle both possible data structures: direct array or wrapped in :data
        raw-candles (if (vector? api-response)
                      api-response  ; Direct array
                      (get api-response :data []))  ; Wrapped in :data
        candle-data (dp/process-candle-data raw-candles)
        chart-type-label (case selected-chart-type
                          :area "Area Chart"
                          :bar "Bar Chart"
                          :baseline "Baseline Chart"
                          :candlestick "Candlestick Chart"
                          :histogram "Histogram Chart"
                          :line "Line Chart"
                          "Chart")]
    [:div.w-full.max-w-6xl.mx-auto.p-4
     [:h1.text-2xl.mb-4 (str chart-type-label " - " (or active-asset "No Asset Selected"))]
     ;; Chart container with consistent width for both menu and chart
     [:div.w-full
      ;; Add the top menu above the chart
      (chart-top-menu state)
      (if has-error?
        [:div.text-red-500.p-4 "Error fetching chart data."]
        (chart-canvas candle-data selected-chart-type))]])) 