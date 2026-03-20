(ns hyperopen.views.chart.hover)

(defn- finite-number
  [value]
  (let [n (cond
            (number? value) value
            (string? value) (js/Number value)
            :else js/NaN)]
    (when (and (number? n)
               (js/isFinite n))
      n)))

(defn- positive-point-count
  [value]
  (when-let [n (finite-number value)]
    (let [count* (js/Math.floor n)]
      (when (pos? count*)
        count*))))

(defn- clamp
  [value min-value max-value]
  (cond
    (< value min-value) min-value
    (> value max-value) max-value
    :else value))

(defn hover-index-from-pointer
  [client-x bounds point-count]
  (let [point-count* (positive-point-count point-count)]
    (when point-count*
      (if (= point-count* 1)
        0
        (let [client-x* (finite-number client-x)
              left (finite-number (:left bounds))
              width (finite-number (:width bounds))]
          (when (and (number? client-x*)
                     (number? left)
                     (number? width)
                     (pos? width))
            (let [x-ratio (clamp (/ (- client-x* left) width) 0 1)
                  max-index (dec point-count*)
                  nearest-index (js/Math.round (* x-ratio max-index))]
              (clamp nearest-index 0 max-index))))))))

(defn normalize-hover-index
  [value point-count]
  (let [point-count* (positive-point-count point-count)
        idx (finite-number value)]
    (when (and point-count*
               (number? idx))
      (let [max-index (dec point-count*)
            idx* (js/Math.floor idx)]
        (clamp idx* 0 max-index)))))
