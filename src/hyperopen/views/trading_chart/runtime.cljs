(ns hyperopen.views.trading-chart.runtime
  (:require [hyperopen.views.trading-chart.runtime-state :as chart-runtime]
            [hyperopen.views.trading-chart.utils.chart-interop :as ci]))

(declare apply-chart-accessibility!)

(defn default-schedule-decoration-frame!
  [callback]
  (if-let [request-animation-frame (some-> js/globalThis
                                           (aget "requestAnimationFrame"))]
    (request-animation-frame callback)
    (do
      (callback 0)
      nil)))

(defn default-cancel-decoration-frame!
  [frame-id]
  (when-let [cancel-animation-frame (some-> js/globalThis
                                            (aget "cancelAnimationFrame"))]
    (cancel-animation-frame frame-id)))

(defn- schedule-decoration-frame-fn
  [context]
  (let [schedule! (:schedule-decoration-frame! context)]
    (if (ifn? schedule!)
      schedule!
      default-schedule-decoration-frame!)))

(defn- cancel-decoration-frame-fn
  [context]
  (let [cancel! (:cancel-decoration-frame! context)]
    (if (ifn? cancel!)
      cancel!
      default-cancel-decoration-frame!)))

(defn- mark-visible-range-interaction!
  [node]
  (chart-runtime/update-state! node
                               update
                               :visible-range-interaction-epoch
                               (fnil inc 0)))

(defn- reset-visible-range!
  [node chart candles]
  (ci/apply-default-visible-range! chart candles)
  (mark-visible-range-interaction! node))

(defn- sync-navigation-overlay!
  [node chart-obj candles]
  (ci/sync-chart-navigation-overlay!
   chart-obj
   node
   candles
   {:on-interaction #(mark-visible-range-interaction! node)
    :on-reset (fn [chart* candles*]
                (reset-visible-range! node chart* candles*))}))

(defn- start-visible-range-persistence!
  [node chart selected-timeframe persistence-deps]
  (ci/subscribe-visible-range-persistence!
   chart
   selected-timeframe
   (assoc persistence-deps
          :on-visible-range-change! #(mark-visible-range-interaction! node))))

(defn- start-visible-range-restore!
  [node chart candles selected-timeframe persistence-deps]
  (let [runtime-state (chart-runtime/get-state node)
        restore-token (inc (or (:visible-range-restore-token runtime-state) 0))
        interaction-epoch (or (:visible-range-interaction-epoch runtime-state) 0)]
    (ci/apply-default-visible-range! chart candles)
    (chart-runtime/assoc-state! node
                                :visible-range-restore-tried? true
                                :visible-range-restore-token restore-token)
    (-> (ci/apply-persisted-visible-range!
         chart
         selected-timeframe
         (assoc persistence-deps
                :candles candles
                :fallback-to-default? false
                :allow-apply-fn
                (fn []
                  (let [runtime-state* (chart-runtime/get-state node)]
                    (and (= restore-token
                            (:visible-range-restore-token runtime-state*))
                         (= interaction-epoch
                            (or (:visible-range-interaction-epoch runtime-state*) 0)))))))
        (.catch (fn [error]
                  (js/console.warn "Failed to restore persisted visible range:" error))))))

(defn- ensure-visible-range-lifecycle!
  [node chart candles selected-timeframe persistence-deps]
  (let [{:keys [visible-range-restore-tried?
                visible-range-persistence-subscribed?]}
        (chart-runtime/get-state node)
        data-ready? (boolean (seq candles))]
    (when (and data-ready? (not visible-range-restore-tried?))
      (start-visible-range-restore! node chart candles selected-timeframe persistence-deps))
    (when (and data-ready? (not visible-range-persistence-subscribed?))
      (chart-runtime/assoc-state! node
                                  :visible-range-cleanup
                                  (start-visible-range-persistence!
                                   node
                                   chart
                                   selected-timeframe
                                   persistence-deps)
                                  :visible-range-persistence-subscribed? true))))

(defn- chart-obj-with-series
  [node candle-data chart-type indicators-data series-options volume-visible?]
  (if (seq indicators-data)
    (ci/create-chart-with-indicators! node chart-type candle-data indicators-data
                                      {:series-options series-options
                                       :volume-visible? volume-visible?})
    (ci/create-chart-with-volume-and-series! node chart-type candle-data
                                             {:series-options series-options
                                              :volume-visible? volume-visible?})))

(defn- apply-chart-decorations!
  [node chart-obj candle-data chart-type main-series-markers position-overlay position-overlay-deps
   open-order-overlays overlay-deps volume-indicator-deps context-menu-deps]
  (ci/set-main-series-markers! chart-obj main-series-markers)
  (ci/sync-position-overlays! chart-obj node position-overlay position-overlay-deps)
  (ci/sync-open-order-overlays! chart-obj node open-order-overlays overlay-deps)
  (ci/sync-volume-indicator-overlay! chart-obj node candle-data volume-indicator-deps)
  (ci/sync-chart-context-menu-overlay! chart-obj
                                       node
                                       candle-data
                                       (assoc context-menu-deps
                                              :on-reset #(reset-visible-range!
                                                           node
                                                           (.-chart ^js chart-obj)
                                                           candle-data)))
  (sync-navigation-overlay! node chart-obj candle-data))

(defn- initialize-chart-runtime-state!
  [node chart-obj legend-control chart-type]
  (chart-runtime/set-state! node {:chart-obj chart-obj
                                  :legend-control legend-control
                                  :chart-type chart-type
                                  :chart-accessibility-applied? false
                                  :decoration-frame-id nil
                                  :pending-decoration-context nil
                                  :visible-range-restore-tried? false
                                  :visible-range-restore-token 0
                                  :visible-range-interaction-epoch 0
                                  :visible-range-persistence-subscribed? false
                                  :visible-range-cleanup nil}))

(defn- run-chart-decoration-pass!
  [node {:keys [candle-data chart-type main-series-markers position-overlay position-overlay-deps
                open-order-overlays overlay-deps volume-indicator-deps context-menu-deps]}]
  (let [{:keys [chart-obj chart-accessibility-applied?]} (chart-runtime/get-state node)]
    (when chart-obj
      (apply-chart-decorations! node
                                chart-obj
                                candle-data
                                chart-type
                                main-series-markers
                                position-overlay
                                position-overlay-deps
                                open-order-overlays
                                overlay-deps
                                volume-indicator-deps
                                context-menu-deps)
      (when-not chart-accessibility-applied?
        (apply-chart-accessibility! node)
        (chart-runtime/assoc-state! node :chart-accessibility-applied? true)))))

(defn- flush-pending-chart-decoration-pass!
  [node]
  (let [{:keys [pending-decoration-context]} (chart-runtime/get-state node)]
    (chart-runtime/update-state! node dissoc :decoration-frame-id :pending-decoration-context)
    (when pending-decoration-context
      (run-chart-decoration-pass! node pending-decoration-context))))

(defn- clear-pending-chart-decoration-pass!
  [node context]
  (let [{:keys [decoration-frame-id]} (chart-runtime/get-state node)]
    (when decoration-frame-id
      ((cancel-decoration-frame-fn context) decoration-frame-id))
    (chart-runtime/update-state! node dissoc :decoration-frame-id :pending-decoration-context)))

(defn- schedule-chart-decoration-pass!
  [node context]
  (when node
    (chart-runtime/assoc-state! node :pending-decoration-context context)
    (when-not (:decoration-frame-id (chart-runtime/get-state node))
      (chart-runtime/assoc-state!
       node
       :decoration-frame-id
       ((schedule-decoration-frame-fn context)
        (fn [_]
          (flush-pending-chart-decoration-pass! node)))))))

(defn apply-chart-accessibility!
  [node]
  (when (and node
             (fn? (.-querySelectorAll node)))
    (doseq [element (array-seq (js/Array.from (.querySelectorAll node ".tv-lightweight-charts table, .tv-lightweight-charts tr, .tv-lightweight-charts td")))]
      (.setAttribute element "aria-hidden" "true")
      (when (= "TABLE" (.-tagName element))
        (.setAttribute element "role" "presentation")))))

(defn- mount-chart!
  [node {:keys [candle-data chart-type indicators-data legend-meta legend-deps series-options
                volume-visible? selected-timeframe persistence-deps] :as context}]
  (let [chart-obj (chart-obj-with-series node candle-data chart-type indicators-data series-options volume-visible?)
        chart (.-chart chart-obj)
        legend-control (ci/create-legend! node chart legend-meta legend-deps)]
    (initialize-chart-runtime-state! node chart-obj legend-control chart-type)
    (ci/sync-baseline-base-value-subscription! chart-obj chart-type)
    (schedule-chart-decoration-pass! node context)
    (ensure-visible-range-lifecycle! node chart candle-data selected-timeframe persistence-deps)))

(defn- swap-main-series!
  [chart-obj chart candle-data chart-type series-options]
  (let [time-scale (.timeScale ^js chart)
        visible-range (.getVisibleLogicalRange ^js time-scale)
        new-series (ci/add-series! chart chart-type)
        main-series (.-mainSeries ^js chart-obj)]
    (when main-series
      (try
        (.removeSeries ^js chart main-series)
        (catch :default _ nil)))
    (set! (.-mainSeries ^js chart-obj) new-series)
    (ci/set-series-data! new-series candle-data chart-type series-options)
    (when visible-range
      (try
        (.setVisibleLogicalRange ^js time-scale visible-range)
        (catch :default _ nil)))))

(defn- update-main-series-data!
  [chart-obj previous-chart-type chart-type candle-data series-options]
  (let [main-series (when chart-obj (.-mainSeries ^js chart-obj))
        chart (when chart-obj (.-chart ^js chart-obj))]
    (when (and chart previous-chart-type (not= previous-chart-type chart-type))
      (swap-main-series! chart-obj chart candle-data chart-type series-options))
    (when (and main-series (or (nil? previous-chart-type) (= previous-chart-type chart-type)))
      (ci/set-series-data! main-series candle-data chart-type series-options))
    (when chart-obj
      (ci/sync-baseline-base-value-subscription! chart-obj chart-type))))

(defn- sync-indicator-series!
  [chart-obj indicator-series-data]
  (let [indicator-series (when chart-obj (.-indicatorSeries ^js chart-obj))]
    (when (and indicator-series (seq indicator-series-data))
      (doseq [[idx series-entry] (map-indexed vector indicator-series-data)]
        (when-let [^js indicator-series-entry (aget ^js indicator-series idx)]
          (when-let [series (.-series indicator-series-entry)]
            (ci/set-indicator-data! series (:data series-entry))))))))

(defn- update-chart!
  [node {:keys [candle-data chart-type indicator-series-data legend-meta selected-timeframe
                persistence-deps series-options] :as context}]
  (let [runtime-state (chart-runtime/get-state node)
        chart-obj (:chart-obj runtime-state)
        legend-control (:legend-control runtime-state)
        previous-chart-type (:chart-type runtime-state)
        chart (when chart-obj (.-chart ^js chart-obj))
        volume-series (when chart-obj (.-volumeSeries ^js chart-obj))]
    (update-main-series-data! chart-obj previous-chart-type chart-type candle-data series-options)
    (when volume-series
      (ci/set-volume-data! volume-series candle-data))
    (when chart
      (ensure-visible-range-lifecycle! node chart candle-data selected-timeframe persistence-deps))
    (when chart-obj
      (schedule-chart-decoration-pass! node context))
    (sync-indicator-series! chart-obj indicator-series-data)
    (when legend-control
      (.update ^js legend-control legend-meta))
    (when (and chart-obj (not= previous-chart-type chart-type))
      (chart-runtime/assoc-state! node :chart-type chart-type))))

(defn- unmount-chart!
  [node context]
  (let [{:keys [chart-obj legend-control visible-range-cleanup]} (chart-runtime/get-state node)
        chart (when chart-obj (.-chart ^js chart-obj))]
    (clear-pending-chart-decoration-pass! node context)
    (when legend-control
      (.destroy ^js legend-control))
    (ci/clear-open-order-overlays! chart-obj)
    (ci/clear-position-overlays! chart-obj)
    (ci/clear-volume-indicator-overlay! chart-obj)
    (ci/clear-chart-context-menu-overlay! chart-obj)
    (ci/clear-chart-navigation-overlay! chart-obj)
    (ci/clear-baseline-base-value-subscription! chart-obj)
    (when visible-range-cleanup
      (try
        (visible-range-cleanup)
        (catch :default _
          nil)))
    (when chart
      (try
        (.remove ^js chart)
        (catch :default _ nil)))
    (chart-runtime/clear-state! node)))

(defn chart-canvas-on-render
  [context]
  (fn [{:keys [:replicant/life-cycle :replicant/node]}]
    (case life-cycle
      :replicant.life-cycle/mount
      (try
        (mount-chart! node context)
        (catch :default e
          (js/console.error "Error in chart:" e)))

      :replicant.life-cycle/update
      (try
        (update-chart! node context)
        (catch :default e
          (js/console.error "Error updating chart:" e)))

      :replicant.life-cycle/unmount
      (unmount-chart! node context)

      nil)))
