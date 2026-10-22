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

(def ^:private history-backfill-threshold-bars
  32)

(def ^:private history-backfill-bars
  330)

(def ^:private millisecond-time-threshold
  100000000000)

(defn- parse-number
  [value]
  (cond
    (number? value)
    (when (and (not (js/isNaN value))
               (js/isFinite value))
      value)

    (string? value)
    (let [parsed (js/Number value)]
      (when (and (number? parsed)
                 (not (js/isNaN parsed))
                 (js/isFinite parsed))
        parsed))

    :else
    nil))

(defn- candle-time-ms
  [candle]
  (when (map? candle)
    (when-let [parsed (parse-number (or (:time candle)
                                        (:t candle)
                                        (get candle "time")
                                        (get candle "t")))]
      (js/Math.floor
       (if (> parsed millisecond-time-threshold)
         parsed
         (* parsed 1000))))))

(defn- oldest-candle-time-ms
  [candles]
  (when-let [times (seq (keep candle-time-ms candles))]
    (apply min times)))

(defn- visible-logical-range
  [chart]
  (try
    (let [time-scale (when chart (.timeScale ^js chart))]
      (when (and time-scale
                 (fn? (.-getVisibleLogicalRange ^js time-scale)))
        (let [range-data (some-> (.getVisibleLogicalRange ^js time-scale)
                                 (js->clj :keywordize-keys true))
              from (parse-number (:from range-data))
              to (parse-number (:to range-data))]
          (when (and (some? from)
                     (some? to)
                     (<= from to))
            {:from from
             :to to}))))
    (catch :default _
      nil)))

(defn- history-backfill-bars-for-visible-range
  [visible-range]
  (let [empty-left-bars (max 0 (- (:from visible-range)))
        needed-bars (+ (js/Math.ceil empty-left-bars)
                       history-backfill-threshold-bars)]
    (max history-backfill-bars needed-bars)))

(defn- mark-history-backfill-requested!
  [node end-time-ms]
  (let [requested (or (:history-backfill-requested-end-times (chart-runtime/get-state node))
                      #{})]
    (when-not (contains? requested end-time-ms)
      (chart-runtime/assoc-state! node
                                  :history-backfill-requested-end-times
                                  (conj requested end-time-ms))
      true)))

(defn- maybe-request-history-backfill!
  [node chart]
  (let [{:keys [history-backfill-context]} (chart-runtime/get-state node)
        {:keys [candles selected-timeframe on-history-backfill-request]} history-backfill-context
        visible-range (visible-logical-range chart)]
    (when (and (fn? on-history-backfill-request)
               (seq candles)
               (keyword? selected-timeframe)
               visible-range
               (<= (:from visible-range) history-backfill-threshold-bars))
      (when-let [oldest-ms (oldest-candle-time-ms candles)]
        (let [end-time-ms (dec oldest-ms)]
          (when (and (pos? end-time-ms)
                     (mark-history-backfill-requested! node end-time-ms))
            (on-history-backfill-request
             {:interval selected-timeframe
              :bars (history-backfill-bars-for-visible-range visible-range)
              :end-time-ms end-time-ms})))))))

(defn- sync-history-backfill-context!
  [node {:keys [candle-data selected-timeframe on-history-backfill-request]}]
  (chart-runtime/assoc-state! node
                              :history-backfill-context
                              {:candles candle-data
                               :selected-timeframe selected-timeframe
                               :on-history-backfill-request on-history-backfill-request}))

(defn- restore-token-current?
  [node chart restore-token]
  (let [{:keys [chart-obj visible-range-restore-token]} (chart-runtime/get-state node)
        current-chart (when chart-obj (.-chart ^js chart-obj))]
    (and (= restore-token visible-range-restore-token)
         (identical? chart current-chart))))

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
          :allow-persist-fn (fn []
                              (>= (or (:visible-range-candle-count (chart-runtime/get-state node)) 0)
                                  history-backfill-threshold-bars))
          :on-visible-range-change! (fn []
                                      (mark-visible-range-interaction! node))
          :on-visible-range-event! (fn []
                                     (maybe-request-history-backfill! node chart)))))

(defn- restore-stale-for-candles?
  "True when the last visible-range restore ran against a partial seed
   dataset (fewer than history-backfill-threshold-bars bars) and older
   history has since been prepended. That is the symbol-load race: a
   live candle from the websocket/trades stream seeds the dataset before
   the REST snapshot lands, the restore latches onto the seed's view,
   and the snapshot then arrives as a prepend. Live tail appends never
   prepend, so genuinely tiny datasets that only grow forward keep their
   view."
  [{:keys [visible-range-restore-tried?
           visible-range-restore-candle-count
           visible-range-restore-oldest-time-ms]}
   candles]
  (boolean
   (and visible-range-restore-tried?
        (number? visible-range-restore-candle-count)
        (< visible-range-restore-candle-count history-backfill-threshold-bars)
        (number? visible-range-restore-oldest-time-ms)
        (when-let [oldest-ms (oldest-candle-time-ms candles)]
          (< oldest-ms visible-range-restore-oldest-time-ms)))))

(defn- start-visible-range-restore!
  [node chart candles selected-timeframe persistence-deps]
  (let [restore-token (inc (or (:visible-range-restore-token (chart-runtime/get-state node)) 0))]
    ;; Apply the default range before sampling the interaction epoch: the
    ;; range change it triggers bumps the epoch when the persistence
    ;; subscription is already attached (re-restore path), and sampling
    ;; afterwards keeps allow-apply-fn true unless the user interacted.
    (ci/apply-default-visible-range! chart candles)
    (let [interaction-epoch (or (:visible-range-interaction-epoch (chart-runtime/get-state node)) 0)]
      (chart-runtime/assoc-state! node
                                  :visible-range-restore-tried? true
                                  :visible-range-restore-token restore-token
                                  :visible-range-restore-candle-count (count candles)
                                  :visible-range-restore-oldest-time-ms (oldest-candle-time-ms candles))
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
          (.then (fn [_]
                   (when (restore-token-current? node chart restore-token)
                     (maybe-request-history-backfill! node chart))))
          (.catch (fn [error]
                    (js/console.warn "Failed to restore persisted visible range:" error)))))))

(defn- ensure-visible-range-lifecycle!
  [node chart candles selected-timeframe persistence-deps]
  (let [{:keys [visible-range-restore-tried?
                visible-range-persistence-subscribed?]
         :as runtime-state}
        (chart-runtime/get-state node)
        data-ready? (boolean (seq candles))]
    (chart-runtime/assoc-state! node :visible-range-candle-count (count candles))
    (when (and data-ready?
               (or (not visible-range-restore-tried?)
                   (restore-stale-for-candles? runtime-state candles)))
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
   open-order-overlays overlay-deps volume-indicator-deps context-menu-deps vol-range-overlay]
  (ci/set-main-series-markers! chart-obj main-series-markers)
  (ci/sync-position-overlays! chart-obj node position-overlay position-overlay-deps)
  (ci/sync-open-order-overlays! chart-obj node open-order-overlays overlay-deps)
  (ci/sync-vol-range-overlays! chart-obj node vol-range-overlay)
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

(defn- observe-theme-changes!
  "Restyle the mounted chart when <html data-theme> changes (Phase 9 of
   the theming plan: live re-theme without an asset/timeframe rebuild).
   Returns the MutationObserver, or nil outside a DOM."
  [node]
  (when (and (exists? js/MutationObserver)
             (exists? js/document))
    (let [observer (js/MutationObserver.
                    (fn [_]
                      (let [{:keys [chart-obj chart-type]} (chart-runtime/get-state node)]
                        (when chart-obj
                          (ci/restyle-chart-theme! chart-obj chart-type)))))]
      (.observe observer
                (.-documentElement js/document)
                #js {:attributes true
                     :attributeFilter #js ["data-theme"]})
      observer)))

(defn- initialize-chart-runtime-state!
  [node chart-obj legend-control chart-type]
  (chart-runtime/set-state! node {:chart-obj chart-obj
                                  :legend-control legend-control
                                  :theme-observer (observe-theme-changes! node)
                                  :chart-type chart-type
                                  :chart-accessibility-applied? false
                                  :decoration-frame-id nil
                                  :pending-decoration-context nil
                                  :visible-range-restore-tried? false
                                  :visible-range-restore-token 0
                                  :visible-range-restore-candle-count nil
                                  :visible-range-restore-oldest-time-ms nil
                                  :visible-range-candle-count 0
                                  :visible-range-interaction-epoch 0
                                  :visible-range-persistence-subscribed? false
                                  :visible-range-cleanup nil}))

(defn- run-chart-decoration-pass!
  [node {:keys [candle-data chart-type main-series-markers position-overlay position-overlay-deps
                open-order-overlays overlay-deps volume-indicator-deps context-menu-deps vol-range-overlay]}]
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
                                context-menu-deps
                                vol-range-overlay)
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
    (sync-history-backfill-context! node context)
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
      (sync-history-backfill-context! node context)
      (ensure-visible-range-lifecycle! node chart candle-data selected-timeframe persistence-deps)
      (maybe-request-history-backfill! node chart))
    (when chart-obj
      (schedule-chart-decoration-pass! node context))
    (sync-indicator-series! chart-obj indicator-series-data)
    (when legend-control
      (.update ^js legend-control legend-meta))
    (when (and chart-obj (not= previous-chart-type chart-type))
      (chart-runtime/assoc-state! node :chart-type chart-type))))

(defn- unmount-chart!
  [node context]
  (let [{:keys [chart-obj legend-control theme-observer visible-range-cleanup]} (chart-runtime/get-state node)
        chart (when chart-obj (.-chart ^js chart-obj))]
    (clear-pending-chart-decoration-pass! node context)
    (when theme-observer
      (.disconnect ^js theme-observer))
    (when legend-control
      (.destroy ^js legend-control))
    (ci/clear-chart-overlays! chart-obj)
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
