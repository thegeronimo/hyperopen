(ns hyperopen.views.chart.renderer
  (:require [hyperopen.config :as app-config]))

(def ^:private default-performance-chart-renderers
  {:portfolio :d3
   :vaults :d3})

(defn performance-chart-renderer
  [surface]
  (let [renderer (get-in app-config/config
                         [:ui :performance-chart-renderer surface]
                         (get default-performance-chart-renderers surface :d3))]
    (if (= renderer :svg)
      :svg
      :d3)))

(defn d3-performance-chart?
  [surface]
  (= :d3 (performance-chart-renderer surface)))
