(ns hyperopen.views.trading-chart.utils.chart-interop.series-sync
  (:require [hyperopen.views.trading-chart.utils.chart-interop.baseline :as baseline]
            [hyperopen.views.trading-chart.utils.chart-interop.candle-sync-policy :as candle-sync-policy]
            [hyperopen.views.trading-chart.utils.chart-interop.price-format :as price-format]
            [hyperopen.views.trading-chart.utils.chart-interop.series :as series]
            [hyperopen.views.trading-chart.utils.chart-interop.transforms :as transforms]))

(defonce ^:private main-series-sync-sidecar (js/WeakMap.))
(defonce ^:private volume-series-sync-sidecar (js/WeakMap.))

(defn- sidecar-state
  [sidecar series*]
  (if series*
    (or (.get sidecar series*) {})
    {}))

(defn- remember-state!
  [sidecar series* state]
  (when series*
    (.set sidecar series* state))
  state)

(defn- vectorize-candles
  [candles]
  (if (vector? candles) candles (vec candles)))

(defn- incremental-sync-mode?
  [decision]
  (contains? #{:update-last :append-last} (:mode decision)))

(defn- full-main-transformed-data
  [candles chart-type]
  (vec (series/transform-main-series-data candles chart-type)))

(defn- full-volume-transformed-data
  [candles]
  (vec (transforms/transform-data-for-volume candles)))

(defn- maybe-incremental-main-transformed-data
  [state candles chart-type decision]
  (when (and (incremental-sync-mode? decision)
             (contains? state :transformed-data))
    (transforms/derive-next-main-series-data
     (:source-candles state)
     (:transformed-data state)
     candles
     chart-type
     (:mode decision))))

(defn- maybe-incremental-volume-transformed-data
  [state candles decision]
  (when (and (incremental-sync-mode? decision)
             (contains? state :transformed-data))
    (transforms/derive-next-volume-data
     (:source-candles state)
     (:transformed-data state)
     candles
     (:mode decision))))

(defn- config-reset-decision
  [previous-candles next-candles]
  {:mode :full-reset
   :reason :config-changed
   :previous-count (count (or previous-candles []))
   :next-count (count next-candles)})

(defn- update-series-point!
  [series* point]
  (let [update-fn (when series*
                    (aget series* "update"))]
    (if (and (fn? update-fn) (some? point))
      (do
        (.call update-fn series* (clj->js point))
        true)
      false)))

(defn- apply-sync-decision!
  [series* decision transformed-data]
  (case (:mode decision)
    :append-last
    (when-not (update-series-point! series* (peek transformed-data))
      (.setData series* (clj->js transformed-data)))

    :update-last
    (when-not (update-series-point! series* (peek transformed-data))
      (.setData series* (clj->js transformed-data)))

    :full-reset
    (.setData series* (clj->js transformed-data))

    nil))

(defn sync-main-series!
  [series* candles chart-type {:keys [price-decimals price-unit]}]
  (let [candles* (vectorize-candles candles)
        chart-type* (transforms/normalize-main-chart-type chart-type)
        state (sidecar-state main-series-sync-sidecar series*)
        config-changed?
        (or (not= chart-type* (:chart-type state))
            (not= price-decimals (:price-decimals state))
            (not= price-unit (:price-unit state)))
        decision (if config-changed?
                   (config-reset-decision (:source-candles state) candles*)
                   (candle-sync-policy/infer-decision (:source-candles state) candles*))]
    (when (or config-changed?
              (not= :noop (:mode decision)))
      (let [transformed-data (or (maybe-incremental-main-transformed-data state candles* chart-type* decision)
                                 (full-main-transformed-data candles* chart-type*))
            base-value (when (= chart-type* :baseline)
                         (baseline/infer-baseline-base-value transformed-data))
            price-format* (price-format/infer-series-price-format
                           transformed-data
                           (fn [points]
                             (series/extract-series-prices points chart-type*))
                           {:price-decimals price-decimals
                            :price-unit price-unit})
            series-options (cond-> {:priceFormat price-format*}
                             (some? base-value)
                             (assoc :baseValue {:type "price" :price base-value}))
            options-changed? (not= series-options (:series-options state))]
        (when options-changed?
          (.applyOptions ^js series* (clj->js series-options)))
        (apply-sync-decision! series* decision transformed-data)
        (remember-state!
         main-series-sync-sidecar
         series*
         {:source-candles candles*
          :transformed-data transformed-data
          :chart-type chart-type*
          :price-decimals price-decimals
          :price-unit price-unit
          :series-options series-options})))))

(defn main-series-unit-price-format
  "The price format last applied to `series*` when it labels an outcome
   unit (percent or cents), else nil. Price-pane indicators copy it."
  [series*]
  (let [state (sidecar-state main-series-sync-sidecar series*)]
    (when (:price-unit state)
      (get-in state [:series-options :priceFormat]))))

(defn sync-volume-series!
  [volume-series candles]
  (let [candles* (vectorize-candles candles)
        state (sidecar-state volume-series-sync-sidecar volume-series)
        decision (candle-sync-policy/infer-decision (:source-candles state) candles*)]
    (when (not= :noop (:mode decision))
      (let [volume-data (or (maybe-incremental-volume-transformed-data state candles* decision)
                            (full-volume-transformed-data candles*))]
        (apply-sync-decision! volume-series decision volume-data)
        (remember-state!
         volume-series-sync-sidecar
         volume-series
         {:source-candles candles*
          :transformed-data volume-data})))))
