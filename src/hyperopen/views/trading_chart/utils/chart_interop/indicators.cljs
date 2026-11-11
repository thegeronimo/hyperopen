(ns hyperopen.views.trading-chart.utils.chart-interop.indicators
  (:require ["lightweight-charts" :refer [HistogramSeries LineSeries]]
            [hyperopen.domain.trading.indicators.polymorphism :as poly]))

(defn- indicator-line-options
  [series-def]
  #js {:color (or (:color series-def) "#38bdf8")
       :lineWidth (or (:line-width series-def) 2)})

(defn- indicator-histogram-options
  [series-def]
  #js {:color (or (:color series-def) "#10b981")
       :priceFormat #js {:type "price"}
       :base (or (:base series-def) 0)})

(defmethod poly/series-operation [:chart-interop/add-series! :line]
  [_ _ chart series-def pane-index]
  (.addSeries ^js chart LineSeries (indicator-line-options series-def) pane-index))

(defmethod poly/series-operation [:chart-interop/add-series! :histogram]
  [_ _ chart series-def pane-index]
  (.addSeries ^js chart HistogramSeries (indicator-histogram-options series-def) pane-index))

(defn add-indicator-series!
  "Add an indicator series in the requested pane."
  [chart series-def pane-index]
  (or (poly/series-operation :chart-interop/add-series!
                             (:series-type series-def)
                             chart
                             series-def
                             pane-index)
      (poly/series-operation :chart-interop/add-series!
                             :line
                             chart
                             series-def
                             pane-index)))

(defonce ^:private applied-price-formats (js/WeakMap.))

(defn sync-price-pane-formats!
  "Label every indicator series drawn on the price pane (pane 0) with the
   main series' current `price-format`, so an outcome chart's moving average
   reads in the same unit and precision as the candles — at mount and after
   any later format change (e.g. new price decimals) without a remount.
   Separate panes keep their own format; a nil format leaves every series as
   it is. `indicator-series` holds {series paneIndex} entries (JS objects or
   maps). Each series is re-labelled only when the format object changed."
  [indicator-series price-format]
  (when price-format
    (doseq [entry (if (array? indicator-series) (array-seq indicator-series) indicator-series)]
      (let [series* (if (map? entry) (:series entry) (some-> entry (aget "series")))
            pane-index (if (map? entry) (:paneIndex entry) (some-> entry (aget "paneIndex")))]
        (when (and series*
                   (= 0 pane-index)
                   (not (identical? price-format (.get applied-price-formats series*))))
          (.applyOptions ^js series* #js {:priceFormat price-format})
          (.set applied-price-formats series* price-format))))))

(defn set-indicator-data!
  "Set indicator data and preserve whitespace points."
  [series data]
  (.setData series (clj->js data)))

(defn indicator-pane-allocation
  "Allocate pane indexes for indicator series definitions."
  [indicators]
  (loop [remaining indicators
         next-pane-index 1
         assignments []]
    (if (empty? remaining)
      {:next-pane-index next-pane-index
       :assignments assignments}
      (let [indicator (first remaining)
            pane-index (if (= :overlay (:pane indicator))
                         0
                         next-pane-index)
            updated-next-pane (if (= :overlay (:pane indicator))
                                next-pane-index
                                (inc next-pane-index))
            indicator-assignments (mapv (fn [series-def]
                                          {:indicator indicator
                                           :series-def series-def
                                           :pane-index pane-index})
                                        (:series indicator))]
        (recur (rest remaining)
               updated-next-pane
               (into assignments indicator-assignments))))))
