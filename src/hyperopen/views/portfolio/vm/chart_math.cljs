(ns hyperopen.views.portfolio.vm.chart-math
  (:require [clojure.string :as str]))

(defn non-zero-span
  [span]
  (if (zero? span) 1 span))

(defn normalize-degenerate-domain
  [min-value max-value]
  (if (= min-value max-value)
    (let [padding (if (zero? min-value) 1 (* (js/Math.abs min-value) 0.1))]
      [(- min-value padding) (+ max-value padding)])
    [min-value max-value]))

(defn chart-domain
  [values]
  (if (empty? values)
    [-1 1]
    (let [min-val (apply min values)
          max-val (apply max values)]
      (normalize-degenerate-domain min-val max-val))))

(defn chart-y-ticks
  [{:keys [min max step]}]
  (let [span (- max min)
        step* (or step (non-zero-span (/ span 4)))
        start (if (zero? step*) min (* (js/Math.floor (/ min step*)) step*))]
    (loop [current start
           ticks []]
      (if (> current max)
        ticks
        (recur (+ current step*)
               (conj ticks {:y current}))))))

(defn normalize-chart-points
  [points {:keys [min max]}]
  (let [span (- max min)
        span* (non-zero-span span)]
    (mapv (fn [{:keys [time-ms value] :as point}]
            (assoc point :y (- 1 (/ (- value min) span*))))
          points)))

(defn format-svg-number
  [value]
  (let [s (.toFixed (js/Number. value) 4)]
    (str/replace s #"\.?0+$" "")))

(defn chart-line-path
  [points]
  (if (< (count points) 2)
    ""
    (let [n (count points)
          first-pt (first points)]
      (loop [idx 1
             path-str (str "M0," (format-svg-number (:y first-pt)))]
        (if (>= idx n)
          path-str
          (let [pt (nth points idx)
                x (/ idx (dec n))]
            (recur (inc idx)
                   (str path-str " L" (format-svg-number x) "," (format-svg-number (:y pt))))))))))

(defn chart-axis-kind
  [tab]
  (case tab
    :returns :percent
    :account-value :currency
    :pnl :currency
    :currency))

(defn normalize-hover-index
  [hover-index points-count]
  (when (and hover-index (pos? points-count))
    (let [index (int (js/Math.round (* hover-index (dec points-count))))]
      (max 0 (min index (dec points-count))))))