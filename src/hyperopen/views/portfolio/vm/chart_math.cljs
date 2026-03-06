(ns hyperopen.views.portfolio.vm.chart-math
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.portfolio.vm.constants :as constants]))

(defn non-zero-span
  [domain-min domain-max]
  (let [span (- domain-max domain-min)]
    (if (zero? span) 1 span)))

(defn normalize-degenerate-domain
  [min-value max-value]
  (if (= min-value max-value)
    (let [pad (max 1 (* 0.05 (js/Math.abs min-value)))]
      [(- min-value pad) (+ min-value pad)])
    [min-value max-value]))

(defn chart-domain
  [values]
  (if (seq values)
    (let [[min-value max-value] (normalize-degenerate-domain (apply min values)
                                                             (apply max values))
          step (/ (non-zero-span min-value max-value)
                  (dec constants/chart-y-tick-count))]
      {:min min-value
       :max max-value
       :step step})
    {:min 0
     :max 3
     :step 1}))

(defn chart-y-ticks
  [{:keys [min max step]}]
  (let [step* (if (and (number? step)
                       (pos? step))
                step
                (/ (non-zero-span min max)
                   (dec constants/chart-y-tick-count)))
        span (non-zero-span min max)]
    (mapv (fn [idx]
            (let [value (if (= idx (dec constants/chart-y-tick-count))
                          min
                          (- max (* step* idx)))]
              {:value value
               :y-ratio (/ (- max value) span)}))
          (range constants/chart-y-tick-count))))

(defn normalize-chart-points
  [points {:keys [min max]}]
  (let [point-count (count points)
        span (non-zero-span min max)]
    (mapv (fn [idx {:keys [value] :as point}]
            (let [x-ratio (if (> point-count 1)
                            (/ idx (dec point-count))
                            0)
                  y-ratio (/ (- max value) span)]
              (assoc point
                     :x-ratio x-ratio
                     :y-ratio y-ratio)))
          (range point-count)
          points)))

(defn format-svg-number
  [value]
  (let [rounded (/ (js/Math.round (* value 1000)) 1000)]
    (if (== rounded -0)
      0
      rounded)))

(defn chart-line-path
  [points]
  (when (seq points)
    (let [commands (map-indexed
                    (fn [idx {:keys [x-ratio y-ratio]}]
                      (let [x (format-svg-number (* 100 x-ratio))
                            y (format-svg-number (* 100 y-ratio))]
                        (str (if (zero? idx) "M " "L ")
                             x
                             " "
                             y)))
                    points)]
      (if (= 1 (count points))
        (let [first-point (first points)
              y (format-svg-number (* 100 (:y-ratio first-point)))]
          (str (first commands) " L 100 " y))
        (str/join " " commands)))))

(defn chart-axis-kind
  [tab]
  (if (= tab :returns)
    :percent
    :number))

(defn normalize-hover-index
  [value point-count]
  (let [point-count* (if (and (number? point-count)
                              (pos? point-count))
                       (js/Math.floor point-count)
                       0)
        idx (projections/parse-optional-num value)]
    (when (and (pos? point-count*)
               (number? idx))
      (let [idx* (js/Math.floor idx)]
        (max 0 (min idx* (dec point-count*)))))))
