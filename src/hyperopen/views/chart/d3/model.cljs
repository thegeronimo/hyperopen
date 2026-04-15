(ns hyperopen.views.chart.d3.model)

(defn clamp
  [value min-value max-value]
  (cond
    (< value min-value) min-value
    (> value max-value) max-value
    :else value))

(defn positive-point-count
  [value]
  (when (and (number? value)
             (js/isFinite value))
    (let [count* (js/Math.floor value)]
      (when (pos? count*)
        count*))))

(defn hover-index
  [client-x left width points]
  (let [points* (vec (or points []))
        point-count (count points*)]
    (when (pos? point-count)
      (if (= point-count 1)
        0
        (when (and (number? client-x)
                   (number? left)
                   (number? width)
                   (pos? width))
          (let [target-x-ratio (clamp (/ (- client-x left) width) 0 1)]
            (some->> points*
                     (keep-indexed (fn [idx {:keys [x-ratio]}]
                                     (when (and (number? x-ratio)
                                                (js/isFinite x-ratio))
                                       [idx (js/Math.abs (- x-ratio target-x-ratio))])))
                     seq
                     (apply min-key second)
                     first)))))))

(def tooltip-center-ratio
  0.5)

(defn tooltip-center-top-pct
  []
  (* 100 tooltip-center-ratio))

(defn tooltip-center-top-px
  [height]
  (* height tooltip-center-ratio))

(defn tooltip-layout
  ([width height hovered-point]
   (tooltip-layout width height nil hovered-point))
  ([width height pointer-left-px hovered-point]
   (when (and (number? width)
              (pos? width)
              (number? height)
              (pos? height)
              (map? hovered-point))
     (let [x-ratio (or (:x-ratio hovered-point) 0)
           left-px (clamp (if (and (number? pointer-left-px)
                                   (js/isFinite pointer-left-px))
                            pointer-left-px
                            (* width x-ratio))
                          0
                          width)
           top-px (tooltip-center-top-px height)]
       {:left-px left-px
        :top-px top-px
        :right-side? (> left-px (* width 0.74))}))))

(defn extend-single-point
  [width points]
  (let [points* (vec (or points []))]
    (if (= 1 (count points*))
      (let [point (first points*)]
        [point
         (assoc point :x width)])
      points*)))

(defn points->pixel-points
  [width height points]
  (->> (or points [])
       (map (fn [{:keys [x-ratio y-ratio] :as point}]
              (assoc point
                     :x (* width (or x-ratio 0))
                     :y (* height (or y-ratio 0)))))
       vec))

(defn positive-clip-height
  [height zero-y-ratio]
  (let [ratio (clamp (if (number? zero-y-ratio) zero-y-ratio 1) 0 1)]
    (* height ratio)))

(defn area-type
  [{:keys [area-fill area-positive-fill area-negative-fill zero-y-ratio]}]
  (cond
    (string? area-fill) :solid
    (and (string? area-positive-fill)
         (string? area-negative-fill)
         (number? zero-y-ratio)) :split-zero
    :else :none))
