(ns hyperopen.views.trading-chart.core
  (:require [hyperopen.views.trading-chart.utils.chart-interop :as ci]))

;; Candlestick chart component
(defn candlestick-chart-canvas []
  (let [mount! (fn [{:keys [:replicant/life-cycle :replicant/node]}]
                 (case life-cycle
                   :replicant.life-cycle/mount
                   (try
                     (js/console.log "=== MOUNTING CANDLESTICK CHART ===")
                     ;; Create chart
                     (let [chart (ci/create-candlestick-chart! node)
                           candlestick-series (ci/add-candlestick-series! chart)]
                       (ci/set-candlestick-data! candlestick-series)
                       (ci/fit-content! chart)
                       ;; Create legend element following TradingView docs
                       (ci/create-legend! node chart candlestick-series)
                       (js/console.log "=== CANDLESTICK CHART SETUP COMPLETE ==="))
                     (catch :default e
                       (js/console.error "Error in candlestick chart:" e)))
                   
                   :replicant.life-cycle/unmount
                   (js/console.log "Unmounting candlestick chart")
                   
                   nil))]

    [:div.w-full.h-96.bg-gray-800.relative
     {:replicant/on-render mount!
      :style {:width "600px" :height "400px"}}]))

(defn trading-chart-view [_]
  [:div.w-full.max-w-6xl.mx-auto.p-4
   [:h1.text-2xl.mb-4 "Candlestick Chart Test"]
   (candlestick-chart-canvas)]) 