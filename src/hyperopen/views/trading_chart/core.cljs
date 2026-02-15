(ns hyperopen.views.trading-chart.core
  (:require [clojure.string :as str]
            [hyperopen.views.trading-chart.utils.chart-interop :as ci]
            [hyperopen.views.trading-chart.utils.data-processing :as dp]
            [hyperopen.views.trading-chart.utils.indicators :as indicators]
            [hyperopen.views.trading-chart.timeframe-dropdown :refer [timeframe-dropdown]]
            [hyperopen.views.trading-chart.chart-type-dropdown :refer [chart-type-dropdown]]
            [hyperopen.views.trading-chart.indicators-dropdown :refer [indicators-dropdown]]
            [hyperopen.views.websocket-freshness :as ws-freshness]))

;; Main timeframes for quick access buttons
(def main-timeframes [:5m :1h :1d])

;; Top menu component with timeframe selection and bars indicator
(defn chart-top-menu [state]
  (let [timeframes-dropdown-visible (get-in state [:chart-options :timeframes-dropdown-visible])
        selected-timeframe (get-in state [:chart-options :selected-timeframe] :1d)
        chart-type-dropdown-visible (get-in state [:chart-options :chart-type-dropdown-visible])
        selected-chart-type (get-in state [:chart-options :selected-chart-type] :candlestick)
        indicators-dropdown-visible (get-in state [:chart-options :indicators-dropdown-visible])
        active-indicators (get-in state [:chart-options :active-indicators] {})
        indicators-search-term (get-in state [:chart-options :indicators-search-term] "")
        show-surface-freshness-cues?
        (boolean (get-in state [:websocket-ui :show-surface-freshness-cues?] false))
        websocket-health (or (:websocket-health state)
                             (get-in state [:websocket :health]))
        freshness-cue (when show-surface-freshness-cues?
                        (ws-freshness/surface-cue websocket-health
                                                  {:topic "trades"
                                                   :selector {:coin (:active-asset state)}
                                                   :live-prefix "Last tick"}))]
    [:div.flex.items-center.border-b.border-gray-700.px-4.py-2.w-full.space-x-4.bg-base-100
     ;; Left side - Favorite timeframes + dropdown
     [:div.flex.items-center.space-x-1
      ;; Main timeframe buttons
      (for [key main-timeframes]
        [:button.relative.px-3.py-1.text-sm.font-medium.rounded.transition-colors
         {:key key
          :class (if (= selected-timeframe key)
                   ["text-trading-green"]
                   ["text-gray-300" "hover:text-white" "hover:bg-gray-700"])
          :on {:click [[:actions/select-chart-timeframe key]]}}
         (name key)])
      ;; Additional timeframe button visible only when selected timeframe is not one of the main 3
      (when-not (contains? (set main-timeframes) selected-timeframe)
        [:button.relative.px-3.py-1.text-sm.font-medium.rounded.transition-colors
         {:class ["text-trading-green"]
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
      ;; Indicators dropdown
      [:div.relative
       (let [active-count (count active-indicators)
             has-active-indicators? (pos? active-count)]
         [:button
          {:class (cond-> ["flex" "items-center" "gap-1.5" "h-8" "px-3" "text-base" "font-medium"
                           "rounded-none" "transition-colors"
                           "text-gray-300" "bg-gray-900/40"
                           "hover:text-white" "hover:bg-gray-800/70"
                           "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-slate-500/70"
                           "focus-visible:ring-offset-1" "focus-visible:ring-offset-base-100"]
                    indicators-dropdown-visible (into ["text-white" "bg-gray-800"])
                    has-active-indicators? (conj "text-gray-100"))
           :on {:click [[:actions/toggle-indicators-dropdown]]}
           :aria-label (if has-active-indicators?
                         (str "Indicators (" active-count " active)")
                         "Indicators")}
          [:span "Indicators"]
          (when has-active-indicators?
            [:span {:class ["text-xs" "text-gray-400"]} (str "(" active-count ")")])])
       (indicators-dropdown {:indicators-dropdown-visible indicators-dropdown-visible
                            :active-indicators active-indicators
                            :search-term indicators-search-term})]]

     (when freshness-cue
       ^{:replicant/key "chart-freshness-cue"}
       [:div {:class ["ml-auto" "flex" "items-center"]
              :data-role "chart-freshness-cue"}
        [:span {:class (case (:tone freshness-cue)
                         :success ["text-xs" "font-medium" "text-success" "tracking-wide"]
                         :warning ["text-xs" "font-medium" "text-warning" "tracking-wide"]
                         ["text-xs" "font-medium" "text-base-content/70" "tracking-wide"])}
         (:text freshness-cue)]])]))

;; Generic chart component that supports all chart types with volume
(defn- sorted-active-indicator-configs
  [active-indicators]
  (sort-by (comp name key) active-indicators))

(defn- flatten-indicator-series
  [indicators-data]
  (vec (mapcat :series indicators-data)))

(defn- flatten-indicator-markers
  [indicators-data]
  (vec (mapcat :markers indicators-data)))

(defn chart-canvas [candle-data chart-type active-indicators legend-meta selected-timeframe]
  (let [;; Calculate indicators data
        indicators-data-vec (->> (sorted-active-indicator-configs active-indicators)
                                 (keep (fn [[indicator-type config]]
                                         (indicators/calculate-indicator indicator-type candle-data config)))
                                 vec)
        indicator-series-data-vec (flatten-indicator-series indicators-data-vec)
        indicator-marker-data-vec (flatten-indicator-markers indicators-data-vec)
        legend-key (str (or (:symbol legend-meta) "")
                        "-"
                        (or (:timeframe-label legend-meta) "")
                        "-"
                        (or (:venue legend-meta) ""))
        mount! (fn [{:keys [:replicant/life-cycle :replicant/node]}]
                 (case life-cycle
                   :replicant.life-cycle/mount
                   (try
                     ;; Create chart with indicators support
                     (let [chart-obj (if (seq indicators-data-vec)
                                       (ci/create-chart-with-indicators! node chart-type candle-data indicators-data-vec)
                                       (ci/create-chart-with-volume-and-series! node chart-type candle-data))
                           chart (.-chart chart-obj)
                           legend-control (ci/create-legend! node chart legend-meta)
                           data-ready? (boolean (seq candle-data))
                           restored-now? (when (seq candle-data)
                                           (ci/apply-persisted-visible-range! chart selected-timeframe))]
                       (set! (.-legendControl ^js chart-obj) legend-control)
                       (set! (.-__chartType ^js chart-obj) chart-type)
                       ;; If the chart mounts before candles arrive, retry restore once on first data update.
                       (set! (.-__visibleRangeRestoreTried ^js chart-obj) (boolean (or restored-now?
                                                                                       (seq candle-data))))
                       (ci/set-main-series-markers! chart-obj indicator-marker-data-vec)
                       ;; Avoid persisting default/pre-data ranges before we can restore user intent.
                       (set! (.-__visibleRangePersistenceSubscribed ^js chart-obj) false)
                       (when data-ready?
                         (set! (.-__visibleRangeCleanup ^js chart-obj)
                               (ci/subscribe-visible-range-persistence! chart selected-timeframe))
                         (set! (.-__visibleRangePersistenceSubscribed ^js chart-obj) true))
                       (set! (.-__hyperopenChart ^js node) chart-obj))
                     (catch :default e
                       (js/console.error "Error in chart:" e)))
                   :replicant.life-cycle/update
                   (let [chart-obj (.-__hyperopenChart ^js node)
                         main-series (when chart-obj (.-mainSeries ^js chart-obj))
                         volume-series (when chart-obj (.-volumeSeries ^js chart-obj))
                         indicator-series (when chart-obj (.-indicatorSeries ^js chart-obj))
                         legend-control (when chart-obj (.-legendControl ^js chart-obj))
                         chart (when chart-obj (.-chart ^js chart-obj))
                         visible-range-restore-tried? (boolean (when chart-obj (.-__visibleRangeRestoreTried ^js chart-obj)))
                         visible-range-subscribed? (boolean (when chart-obj (.-__visibleRangePersistenceSubscribed ^js chart-obj)))
                         previous-chart-type (when chart-obj (.-__chartType ^js chart-obj))]
                     (when (and chart previous-chart-type (not= previous-chart-type chart-type))
                       (let [time-scale (.timeScale ^js chart)
                             visible-range (.getVisibleLogicalRange ^js time-scale)
                             new-series (ci/add-series! chart chart-type)]
                         (when main-series
                           (try
                             (.removeSeries ^js chart main-series)
                             (catch :default _ nil)))
                         (set! (.-mainSeries ^js chart-obj) new-series)
                         (set! (.-__chartType ^js chart-obj) chart-type)
                         (ci/set-series-data! new-series candle-data chart-type)
                         (when visible-range
                           (try
                             (.setVisibleLogicalRange ^js time-scale visible-range)
                             (catch :default _ nil)))))
                     (when (and main-series (or (nil? previous-chart-type) (= previous-chart-type chart-type)))
                       (ci/set-series-data! main-series candle-data chart-type))
                     (when volume-series
                       (ci/set-volume-data! volume-series candle-data))
                     (when (and chart-obj chart (seq candle-data) (not visible-range-restore-tried?))
                       (ci/apply-persisted-visible-range! chart selected-timeframe)
                       (set! (.-__visibleRangeRestoreTried ^js chart-obj) true))
                     (when (and chart-obj chart (seq candle-data) (not visible-range-subscribed?))
                       (set! (.-__visibleRangeCleanup ^js chart-obj)
                             (ci/subscribe-visible-range-persistence! chart selected-timeframe))
                       (set! (.-__visibleRangePersistenceSubscribed ^js chart-obj) true))
                     (when chart-obj
                       (ci/set-main-series-markers! chart-obj indicator-marker-data-vec))
                     (when (and indicator-series (seq indicator-series-data-vec))
                       (doseq [[idx series-entry] (map-indexed vector indicator-series-data-vec)]
                         (when-let [^js indicator-series-entry (aget ^js indicator-series idx)]
                           (when-let [series (.-series indicator-series-entry)]
                             (ci/set-indicator-data! series (:data series-entry))))))
                     (when legend-control
                       (.update ^js legend-control legend-meta)))
                   :replicant.life-cycle/unmount
                   (let [chart-obj (.-__hyperopenChart ^js node)
                         legend-control (when chart-obj (.-legendControl ^js chart-obj))
                         visible-range-cleanup (when chart-obj (.-__visibleRangeCleanup ^js chart-obj))
                         chart (when chart-obj (.-chart ^js chart-obj))]
                     (when legend-control
                       (.destroy ^js legend-control))
                     (when visible-range-cleanup
                       (try
                         (visible-range-cleanup)
                         (catch :default _
                           nil)))
                     (when chart
                       (try
                         (.remove ^js chart)
                         (catch :default _ nil)))
                     (set! (.-__hyperopenChart ^js node) nil))
                   nil))]
    [:div {:class ["w-full" "relative" "flex-1" "h-full" "min-h-[360px]" "bg-base-100" "trading-chart-host"]
           :replicant/key (str "chart-" (hash active-indicators) "-" legend-key)
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
        symbol (or active-asset "—")
        timeframe-label (str/upper-case (name selected-timeframe))
        legend-meta {:symbol symbol
                     :timeframe-label timeframe-label
                     :venue "Hyperopen"
                     :candle-data candle-data}]
    [:div {:class ["w-full" "h-full"]}
     ;; Chart container with consistent width for both menu and chart
     [:div {:class ["w-full" "h-full" "flex" "flex-col"]}
      ;; Add the top menu above the chart
      (chart-top-menu state)
      (if has-error?
        [:div {:class ["text-red-500" "p-4" "flex-1"]} "Error fetching chart data."]
        (chart-canvas candle-data
                      selected-chart-type
                      (get-in state [:chart-options :active-indicators] {})
                      legend-meta
                      selected-timeframe))]]))
