(ns hyperopen.views.trading-chart.utils.chart-interop
  (:require ["lightweight-charts" :refer [createChart LineSeries]]))

;; Minimal implementation mirroring the JavaScript example exactly
(defn create-simple-chart! [container]
  "Create a chart with basic options"
  (let [chart (createChart container #js {:width 400 :height 300})]
    (js/console.log "chart created, available methods:" (js/Object.keys chart))
    chart))

(defn add-line-series! [chart]
  "Add a line series to the chart"
  (let [series (.addSeries ^js chart LineSeries)]
    (js/console.log "line series created, available methods:" (js/Object.keys series))
    series))

(defn set-simple-data! [series]
  "Set simple line data exactly like the JavaScript example"
  (let [data #js [#js {:time "2019-04-11" :value 80.01}
                  #js {:time "2019-04-12" :value 96.63}
                  #js {:time "2019-04-13" :value 76.64}
                  #js {:time "2019-04-14" :value 81.89}
                  #js {:time "2019-04-15" :value 74.43}
                  #js {:time "2019-04-16" :value 80.01}
                  #js {:time "2019-04-17" :value 96.63}
                  #js {:time "2019-04-18" :value 76.64}
                  #js {:time "2019-04-19" :value 81.89}
                  #js {:time "2019-04-20" :value 74.43}]]
    (js/console.log "setting data:" data)
    (.setData series data))) 