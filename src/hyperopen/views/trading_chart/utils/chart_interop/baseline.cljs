(ns hyperopen.views.trading-chart.utils.chart-interop.baseline
  (:require [hyperopen.views.trading-chart.utils.chart-interop.transforms :as transforms]))

(def ^:private baseline-default-level-percent 0.5)
(defonce ^:private baseline-sidecar (js/WeakMap.))

(defn infer-baseline-base-value
  "Infer a baseline split level from transformed series values."
  [transformed-data]
  (let [values (->> transformed-data
                    (map :value)
                    (map (fn [v]
                           (if (number? v) v (js/parseFloat v))))
                    (filter (fn [v]
                              (and (number? v) (not (js/isNaN v)))))
                    vec)]
    (when (seq values)
      (let [low (apply min values)
            high (apply max values)]
        (+ low (* (- high low) baseline-default-level-percent))))))

(defn- visible-range->baseline-price
  [visible-range level-percent]
  (when (and visible-range
             (number? (:from visible-range))
             (number? (:to visible-range)))
    (let [low (min (:from visible-range) (:to visible-range))
          high (max (:from visible-range) (:to visible-range))]
      (+ low (* (- high low) level-percent)))))

(defn- refresh-baseline-base-value!
  [main-series]
  (when main-series
    (let [price-scale (.priceScale ^js main-series)
          visible-range (when (and price-scale (fn? (.-getVisibleRange ^js price-scale)))
                          (some-> (.getVisibleRange ^js price-scale)
                                  (js->clj :keywordize-keys true)))
          base-value (visible-range->baseline-price visible-range baseline-default-level-percent)]
      (when (number? base-value)
        (.applyOptions ^js main-series
                       (clj->js {:baseValue {:type "price"
                                             :price base-value}}))))))

(defn- subscribe-baseline-base-value!
  [chart main-series]
  (let [time-scale (.timeScale ^js chart)]
    (refresh-baseline-base-value! main-series)
    (if-not time-scale
      (fn [] nil)
      (let [handler (fn [_]
                      (refresh-baseline-base-value! main-series))]
        (if (fn? (.-subscribeVisibleLogicalRangeChange ^js time-scale))
          (do
            (.subscribeVisibleLogicalRangeChange ^js time-scale handler)
            (fn []
              (try
                (.unsubscribeVisibleLogicalRangeChange ^js time-scale handler)
                (catch :default _
                  nil))))
          (if (fn? (.-subscribeVisibleTimeRangeChange ^js time-scale))
            (do
              (.subscribeVisibleTimeRangeChange ^js time-scale handler)
              (fn []
                (try
                  (.unsubscribeVisibleTimeRangeChange ^js time-scale handler)
                  (catch :default _
                    nil))))
            (fn [] nil)))))))

(defn- baseline-state
  [chart-obj]
  (if chart-obj
    (or (.get baseline-sidecar chart-obj) {})
    {}))

(defn- set-baseline-state!
  [chart-obj state]
  (when chart-obj
    (.set baseline-sidecar chart-obj state)))

(defn- clear-baseline-state!
  [chart-obj]
  (when chart-obj
    (.delete baseline-sidecar chart-obj)))

(defn sync-baseline-base-value-subscription!
  "Ensure baseline base-value subscription state matches current chart type."
  [chart-obj chart-type]
  (when chart-obj
    (let [chart-type* (transforms/normalize-main-chart-type chart-type)
          chart (.-chart ^js chart-obj)
          main-series (.-mainSeries ^js chart-obj)
          {:keys [cleanup series]} (baseline-state chart-obj)]
      (if (= chart-type* :baseline)
        (if (or (nil? cleanup)
                (not (identical? series main-series)))
          (do
            (when cleanup
              (cleanup))
            (set-baseline-state! chart-obj {:cleanup (subscribe-baseline-base-value! chart main-series)
                                            :series main-series}))
          (refresh-baseline-base-value! main-series))
        (when cleanup
          (cleanup)
          (clear-baseline-state! chart-obj))))))

(defn clear-baseline-base-value-subscription!
  "Clear baseline base-value subscription and sidecar state."
  [chart-obj]
  (when chart-obj
    (when-let [cleanup (:cleanup (baseline-state chart-obj))]
      (cleanup))
    (clear-baseline-state! chart-obj)))
