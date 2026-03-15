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
  [client-x left width point-count]
  (let [point-count* (positive-point-count point-count)]
    (when point-count*
      (if (= point-count* 1)
        0
        (when (and (number? client-x)
                   (number? left)
                   (number? width)
                   (pos? width))
          (let [x-ratio (clamp (/ (- client-x left) width) 0 1)
                max-index (dec point-count*)
                nearest-index (js/Math.round (* x-ratio max-index))]
            (clamp nearest-index 0 max-index)))))))

(defn tooltip-layout
  [width height hovered-point]
  (when (and (number? width)
             (pos? width)
             (number? height)
             (pos? height)
             (map? hovered-point))
    (let [x-ratio (or (:x-ratio hovered-point) 0)
          y-ratio (or (:y-ratio hovered-point) 0)
          left-px (* width x-ratio)
          top-px (clamp (- (* height y-ratio) (* height 0.08))
                        (* height 0.08)
                        (* height 0.92))]
      {:left-px left-px
       :top-px top-px
       :right-side? (> left-px (* width 0.74))})))

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
