(ns hyperopen.views.pnl-share.templates.neon-arrow-art
  "Artwork for the Neon arrow share card: gradients, the contour field, the two
   arrow forms, and the small line icons.

   The design expresses the background as CSS repeating-radial-gradients behind
   a linear mask. SVG has no repeating gradient, so the field is drawn as a run
   of concentric ellipse strokes inside a real <mask>; the visual result is the
   same and it survives rasterization, which CSS masks on foreign content do
   not."
  (:require [hyperopen.views.pnl-share.svg :as svg]))

(def card-width 1080)
(def card-height 608)
(def card-radius 26)

(def ^:private arrow-up-path
  "M 60 800 C 300 740, 352 560, 368 300 L 268 300 L 413 110 L 558 300 L 458 300 C 442 540, 420 700, 240 830 Z")

(def ^:private arrow-up-spark-path
  "M 132 810 C 340 726, 392 556, 408 302")

(def ^:private arrow-down-path
  "M 48 12 L 468 512 L 518 462 L 560 660 L 362 618 L 412 568 L -8 68 Z")

(def ^:private arrow-down-spark-path
  "M 96 30 L 470 476")

(defn- ellipse-field
  [{:keys [cx cy rx-step ry-step rings stroke]}]
  (into [:g {:fill "none" :stroke stroke :stroke-width 1}]
        (map (fn [i]
               [:ellipse {:cx cx :cy cy :rx (* i rx-step) :ry (* i ry-step)}]))
        (range 1 (inc rings))))

(defn contour-field
  [palette]
  [:g {:mask "url(#ho-pnl-contour-mask)"}
   [:g {:transform "rotate(-7 540 304)"}
    (ellipse-field {:cx 713 :cy 268 :rx-step 43 :ry-step 18 :rings 26
                    :stroke (:contour palette)})
    (ellipse-field {:cx 994 :cy 523 :rx-step 71 :ry-step 24 :rings 20
                    :stroke (:contour-faint palette)})]])

(defn loss-rain
  "The four falling hairlines the loss treatment drops from the top edge."
  [palette]
  (into [:g]
        (map (fn [[x height opacity]]
               [:rect {:x x :y 0 :width 1 :height height
                       :fill (:accent palette) :fill-opacity opacity}]))
        [[984 260 0.5] [872 150 0.35] [750 330 0.28] [660 96 0.4]]))

(defn defs
  [palette winning?]
  [:defs
   [:clipPath {:id "ho-pnl-card-clip"}
    [:rect {:x 0 :y 0 :width card-width :height card-height :rx card-radius}]]

   (svg/radial-wash "ho-pnl-wash-main"
                    {:cx 886 :cy 365 :rx 756 :ry 547
                     :inner (:plate-wash-near palette)
                     :outer (:plate-wash-far palette)})
   (svg/radial-wash "ho-pnl-wash-corner"
                    {:cx 108 :cy 61 :rx 972 :ry 425 :stop "60%"
                     :inner (:plate-wash-corner palette)
                     :outer (:plate-wash-far palette)})

   ;; Mask stops are luminance, not colour: black hides, white reveals.
   [:linearGradient {:id "ho-pnl-contour-fade" :x1 "0" :y1 "0" :x2 "1" :y2 "0"}
    [:stop {:offset "22%" :stop-color "black"}]
    [:stop {:offset "62%" :stop-color "white"}]]
   [:mask {:id "ho-pnl-contour-mask" :maskUnits "userSpaceOnUse"
           :x 0 :y 0 :width card-width :height card-height}
    [:rect {:x 0 :y 0 :width card-width :height card-height
            :fill "url(#ho-pnl-contour-fade)"}]]

   (if winning?
     (svg/linear-gradient "ho-pnl-arrow-fill" [0 1 0.4 0]
                          [["0%" (:arrow-fill-low palette)]
                           ["60%" (:arrow-fill-mid palette)]
                           ["100%" (:arrow-fill-high palette)]])
     (svg/linear-gradient "ho-pnl-arrow-fill" [0 0 0.6 1]
                          [["0%" (:arrow-fill-low palette)]
                           ["60%" (:arrow-fill-mid palette)]
                           ["100%" (:arrow-fill-high palette)]]))
   (if winning?
     (svg/linear-gradient "ho-pnl-arrow-edge" [0 1 0.3 0]
                          [["0%" (:arrow-edge-low palette)]
                           ["55%" (:arrow-edge-mid palette)]
                           ["100%" (:arrow-edge-high palette)]])
     (svg/linear-gradient "ho-pnl-arrow-edge" [0 0 0.5 1]
                          [["0%" (:arrow-edge-low palette)]
                           ["55%" (:arrow-edge-mid palette)]
                           ["100%" (:arrow-edge-high palette)]]))

   (svg/linear-gradient "ho-pnl-hero" [0 0 0.18 1]
                        [["6%" (:hero-high palette)]
                         ["48%" (:hero-mid palette)]
                         ["96%" (:hero-low palette)]])

   [:filter {:id "ho-pnl-arrow-glow" :x "-40%" :y "-40%" :width "180%" :height "180%"}
    [:feDropShadow {:dx 0 :dy 0 :stdDeviation 8
                    :flood-color (:arrow-glow palette) :flood-opacity 1}]
    [:feDropShadow {:dx 0 :dy 0 :stdDeviation 28
                    :flood-color (:arrow-glow palette) :flood-opacity 0.6}]]])

(defn monogram-gradient-def
  [[from to]]
  (svg/linear-gradient "ho-pnl-monogram" [0 0 1 1]
                       [["0%" from] ["100%" to]]))

(defn arrow
  [palette winning?]
  (let [geometry (if winning?
                   {:x 516 :y -30 :width 540 :height 660}
                   {:x 616 :y 10 :width 450 :height 548})]
    [:svg (merge geometry
                 {:viewBox "0 0 640 780"
                  :preserveAspectRatio "none"
                  :filter "url(#ho-pnl-arrow-glow)"})
     [:path {:d (if winning? arrow-up-path arrow-down-path)
             :fill "url(#ho-pnl-arrow-fill)"
             :stroke "url(#ho-pnl-arrow-edge)"
             :stroke-width 3.5
             :stroke-linejoin "round"}]
     [:path {:d (if winning? arrow-up-spark-path arrow-down-spark-path)
             :fill "none"
             :stroke (:arrow-spark palette)
             :stroke-width 1.2}]]))

(defn- stroked-icon
  [{:keys [x y size colour width]} & paths]
  (let [scale (/ size 24)]
    (into [:g {:transform (str "translate(" x "," y ") scale(" scale ")")
               :fill "none"
               :stroke colour
               :stroke-width (or width 1.5)
               :stroke-linecap "round"
               :stroke-linejoin "round"}]
          paths)))

(defn tag-icon
  [opts]
  (stroked-icon opts
                [:path {:d "M20.5 13.3 11.7 4.5H4.5v7.2l8.8 8.8a1.5 1.5 0 0 0 2.1 0l5.1-5.1a1.5 1.5 0 0 0 0-2.1Z"}]
                [:circle {:cx 8.2 :cy 8.2 :r 1.2}]))

(defn trend-icon
  [opts winning?]
  (if winning?
    (stroked-icon opts
                  [:path {:d "M4 16.5 9 11l3.4 3.4L20 7"}]
                  [:path {:d "M15.5 7H20v4.5"}])
    (stroked-icon opts
                  [:path {:d "M4 7l5 5.5L12.4 9 20 16.5"}]
                  [:path {:d "M15.5 16.5H20V12"}])))

(defn globe-icon
  [opts]
  (stroked-icon (assoc opts :width 1.4)
                [:circle {:cx 12 :cy 12 :r 8.6}]
                [:path {:d "M3.4 12h17.2M12 3.4c2.4 2.4 2.4 14.8 0 17.2-2.4-2.4-2.4-14.8 0-17.2Z"}]))

(defn brand-mark
  "The repo's own H-bar-O monogram from resources/public/favicon.svg: two upright
   bars flanking a stroked ring. The design's four-slash mark is not hyperopen's
   mark and is deliberately not reproduced."
  [{:keys [x y height colour-a colour-b]}]
  (let [bar-width (* height 0.17)
        ring-radius (* height 0.30)
        ring-stroke (* height 0.165)
        centre-y (+ y (/ height 2))
        ring-cx (+ x (/ (+ bar-width bar-width (* ring-radius 2) 8) 2) 1)]
    [:g
     [:rect {:x x :y y :width bar-width :height height
             :rx 2 :fill colour-a}]
     [:circle {:cx ring-cx :cy centre-y :r ring-radius
               :fill "none" :stroke colour-b :stroke-width ring-stroke}]
     [:rect {:x (+ x (* ring-radius 2) (* bar-width 1) 8) :y y
             :width bar-width :height height :rx 2 :fill colour-a}]]))

(defn brand-mark-width
  [height]
  (+ (* height 0.17 2) (* height 0.30 2) 8))
