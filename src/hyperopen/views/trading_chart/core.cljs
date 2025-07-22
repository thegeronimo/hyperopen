(ns hyperopen.views.trading-chart.core
  (:require [hyperopen.views.trading-chart.utils.chart-interop :as ci]))

;; Minimal basic chart component
(defn simple-chart-canvas []
  (let [mount! (fn [{:keys [:replicant/life-cycle :replicant/node]}]
                 (case life-cycle
                   :replicant.life-cycle/mount
                   (try
                     (js/console.log "=== MOUNTING SIMPLE CHART ===")
                     ;; Exactly mirror the JavaScript example
                     (let [chart (ci/create-simple-chart! node)
                           line-series (ci/add-line-series! chart)]
                       (ci/set-simple-data! line-series)
                       (js/console.log "=== CHART SETUP COMPLETE ==="))
                     (catch :default e
                       (js/console.error "Error in simple chart:" e)))
                   
                   :replicant.life-cycle/unmount
                   (js/console.log "Unmounting simple chart")
                   
                   nil))]

    [:div.w-full.h-96.bg-gray-800
     {:replicant/on-render mount!
      :style {:width "400px" :height "300px"}}]))

(defn trading-chart-view [_]
  [:div.w-full.max-w-6xl.mx-auto.p-4
   [:h1.text-2xl.mb-4 "Simple Chart Test"]
   (simple-chart-canvas)]) 