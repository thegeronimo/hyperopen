(ns hyperopen.views.portfolio.optimize.frontier-target
  (:require [hyperopen.views.portfolio.optimize.frontier-callout :as frontier-callout]))

(def target-cyan "#48d4ff")
(def target-blue "#5d7cff")
(def target-violet "#8b5cff")
(def target-purple "#b54cff")
(def target-magenta "#ff4fd8")

(def target-orb-gradient "url(#portfolioOptimizerTargetOrbGradient)")
(def target-halo-gradient "url(#portfolioOptimizerTargetHaloGradient)")
(def target-ring-gradient "url(#portfolioOptimizerTargetRingGradient)")
(def target-label-border-gradient "url(#portfolioOptimizerTargetLabelBorderGradient)")

(def legend-gradient
  "radial-gradient(circle at 35% 30%, #ffffff 0%, #7cecff 14%, #5d7cff 38%, #8b5cff 66%, #ff4fd8 100%)")

(defn gradient-defs
  []
  [:defs {:data-role "portfolio-optimizer-frontier-target-defs"}
   [:radialGradient {:id "portfolioOptimizerTargetOrbGradient"
                     :cx "34%"
                     :cy "30%"
                     :r "82%"}
    [:stop {:offset "0%" :stop-color "#ffffff" :stop-opacity "0.95"}]
    [:stop {:offset "10%" :stop-color "#7cecff" :stop-opacity "0.96"}]
    [:stop {:offset "32%" :stop-color target-blue :stop-opacity "0.95"}]
    [:stop {:offset "58%" :stop-color target-violet :stop-opacity "0.96"}]
    [:stop {:offset "78%" :stop-color target-purple :stop-opacity "0.94"}]
    [:stop {:offset "100%" :stop-color target-magenta :stop-opacity "0.90"}]]
   [:radialGradient {:id "portfolioOptimizerTargetHaloGradient"
                     :cx "50%"
                     :cy "50%"
                     :r "58%"}
    [:stop {:offset "0%" :stop-color target-violet :stop-opacity "0.22"}]
    [:stop {:offset "34%" :stop-color target-cyan :stop-opacity "0.12"}]
    [:stop {:offset "62%" :stop-color target-magenta :stop-opacity "0.08"}]
    [:stop {:offset "100%" :stop-color "#000000" :stop-opacity "0"}]]
   [:linearGradient {:id "portfolioOptimizerTargetRingGradient"
                     :x1 "0%"
                     :y1 "0%"
                     :x2 "100%"
                     :y2 "100%"}
    [:stop {:offset "0%" :stop-color target-cyan :stop-opacity "0.75"}]
    [:stop {:offset "45%" :stop-color target-violet :stop-opacity "0.9"}]
    [:stop {:offset "100%" :stop-color target-magenta :stop-opacity "0.7"}]]
   [:linearGradient {:id "portfolioOptimizerTargetLabelBorderGradient"
                     :x1 "0%"
                     :y1 "0%"
                     :x2 "100%"
                     :y2 "100%"}
    [:stop {:offset "0%" :stop-color target-cyan :stop-opacity "0.38"}]
    [:stop {:offset "52%" :stop-color target-violet :stop-opacity "0.70"}]
    [:stop {:offset "100%" :stop-color target-magenta :stop-opacity "0.44"}]]
   [:linearGradient {:id "portfolioOptimizerTargetTooltipBorderGradient"
                     :x1 "0%"
                     :y1 "0%"
                     :x2 "100%"
                     :y2 "100%"}
    [:stop {:offset "0%" :stop-color target-cyan :stop-opacity "0.38"}]
    [:stop {:offset "36%" :stop-color target-violet :stop-opacity "0.68"}]
    [:stop {:offset "64%" :stop-color target-purple :stop-opacity "0.70"}]
    [:stop {:offset "100%" :stop-color target-magenta :stop-opacity "0.42"}]]
   [:filter {:id "portfolioOptimizerTargetSoftGlow"
             :x "-80%"
             :y "-80%"
             :width "260%"
             :height "260%"}
    [:feGaussianBlur {:stdDeviation "2.0" :result "blur"}]
    [:feColorMatrix {:in "blur"
                     :type "matrix"
                     :values "1 0 0 0 0 0 1 0 0 0 0 0 1 0 0 0 0 0 0.34 0"
                     :result "softGlow"}]
    [:feMerge
     [:feMergeNode {:in "softGlow"}]
     [:feMergeNode {:in "SourceGraphic"}]]]])

(defn legend-dot
  []
  [:span {:class ["h-2.5" "w-2.5" "rounded-full"]
          :style {:background legend-gradient
                  :border "1px solid rgba(235, 226, 255, 0.72)"
                  :box-shadow "0 0 5px rgba(139, 92, 255, 0.46), 0 0 9px rgba(72, 212, 255, 0.14)"}
          :data-role "portfolio-optimizer-frontier-legend-target-dot"}])

(defn- target-model
  [{:keys [point-position x-domain y-domain result]}]
  (let [point {:expected-return (:expected-return result)
               :volatility (:volatility result)
               :sharpe (get-in result [:performance :in-sample-sharpe])}
        position (point-position x-domain y-domain point)
        {:keys [x y]} position
        label-x (+ x 26)
        label-y (- y 23)
        label "Target"
        rows (frontier-callout/point-rows
              point
              {:exposure (frontier-callout/exposure-summary result :target)})]
    {:position position
     :x x
     :y y
     :label-x label-x
     :label-y label-y
     :label label
     :rows rows}))

(defn callout
  [{:keys [bounds] :as opts}]
  (let [{:keys [position label rows]} (target-model opts)]
    (frontier-callout/callout
     {:bounds bounds
      :data-role "portfolio-optimizer-frontier-callout-target"
      :variant :target
      :label label
      :point position
      :rows rows})))

(defn marker
  [{:keys [render-callout?] :as opts}]
  (let [{:keys [x y label-x label-y label rows]} (target-model opts)]
    [:g {:class ["portfolio-frontier-marker" "outline-none"]
         :style {:color target-purple}
         :data-role "portfolio-optimizer-frontier-target-marker"
         :data-frontier-callout-trigger "target"
         :role "img"
         :tabIndex 0
         :tabindex 0
         :focusable "true"
         :aria-label (frontier-callout/aria-label label rows)}
     [:line {:x1 (+ x 10)
             :y1 (- y 4)
             :x2 label-x
             :y2 (+ label-y 11)
             :stroke "rgba(181, 76, 255, 0.50)"
             :strokeWidth 1
             :stroke-dasharray "3 3"
             :filter "drop-shadow(0 0 2px rgba(139, 92, 255, 0.16))"
             :data-role "portfolio-optimizer-frontier-target-leader-line"}]
     [:circle {:cx x
               :cy y
               :r 17
               :fill target-halo-gradient
               :opacity 0.52
               :data-role "portfolio-optimizer-frontier-target-halo"}]
     [:circle {:cx x
               :cy y
               :r 11.5
               :fill "none"
               :stroke target-ring-gradient
               :strokeWidth 1.15
               :opacity 0.74
               :data-role "portfolio-optimizer-frontier-target-ring"}]
     [:circle {:cx x
               :cy y
               :r 8
               :fill target-orb-gradient
               :stroke "rgba(246, 235, 255, 0.58)"
               :strokeWidth 0.85
               :filter "url(#portfolioOptimizerTargetSoftGlow)"
               :data-role "portfolio-optimizer-frontier-target-core"}]
     [:circle {:cx (- x 2.2)
               :cy (- y 2.6)
               :r 1.9
               :fill "rgba(255,255,255,0.66)"
               :data-role "portfolio-optimizer-frontier-target-highlight"}]
     (frontier-callout/focus-ring x y 22)
     [:g {:data-role "portfolio-optimizer-frontier-target-label"
          :transform (str "translate(" label-x " " label-y ")")}
      [:rect {:x 0
              :y 0
              :width 50
              :height 24
              :rx 3
              :fill target-label-border-gradient}]
      [:rect {:x 1
              :y 1
              :width 48
              :height 22
              :rx 2
              :fill "rgba(18, 14, 28, 0.94)"}]
      [:text {:x 25
              :y 12
              :fill "#f3ecff"
              :fontSize 12
              :fontWeight 600
              :text-anchor "middle"
              :dominant-baseline "middle"}
       "Target"]]
     (frontier-callout/hitbox
      "portfolio-optimizer-frontier-target-marker-hitbox"
      x
      y
      18)
     (when-not (false? render-callout?)
       (callout opts))]))
