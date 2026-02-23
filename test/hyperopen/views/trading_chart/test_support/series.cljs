(ns hyperopen.views.trading-chart.test-support.series)

(defn make-series
  [& {:keys [apply-options set-data update]}]
  (let [series #js {}]
    (when (some? apply-options)
      (aset series "applyOptions" apply-options))
    (when (some? set-data)
      (aset series "setData" set-data))
    (when (some? update)
      (aset series "update" update))
    series))

(defn make-chart
  [& {:keys [add-series]}]
  (let [chart #js {}]
    (when (some? add-series)
      (aset chart "addSeries" add-series))
    chart))
