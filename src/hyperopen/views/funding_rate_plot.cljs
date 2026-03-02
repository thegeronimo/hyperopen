(ns hyperopen.views.funding-rate-plot
  (:require [hyperopen.utils.formatting :as fmt]))

(def ^:private plot-width
  248)

(def ^:private plot-height
  112)

(def ^:private margin-left
  10)

(def ^:private margin-right
  8)

(def ^:private margin-top
  10)

(def ^:private margin-bottom
  24)

(def ^:private day-ticks
  [1 5 10 15 20 25 30])

(def ^:private positive-fill
  "#22c997")

(def ^:private negative-fill
  "#e35f78")

(def ^:private undefined-fill
  "rgba(143,165,184,0.35)")

(def ^:private axis-stroke
  "rgba(143,165,184,0.38)")

(def ^:private grid-stroke
  "rgba(143,165,184,0.20)")

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- parse-number
  [value]
  (cond
    (number? value)
    (when (finite-number? value)
      value)

    (string? value)
    (let [parsed (js/parseFloat value)]
      (when (finite-number? parsed)
        parsed))

    :else
    nil))

(defn- normalize-day-index
  [value]
  (when-let [num (parse-number value)]
    (let [day-index (js/Math.floor num)]
      (when (pos? day-index)
        day-index))))

(defn- hourly-decimal->annualized-percent
  [hourly-decimal]
  (when (finite-number? hourly-decimal)
    (fmt/annualized-funding-rate (* hourly-decimal 100))))

(defn- normalize-series
  [series]
  (->> (or series [])
       (keep (fn [point]
               (when (map? point)
                 (let [day-index (normalize-day-index (:day-index point))
                       hourly-rate (parse-number (:mean-rate point))
                       annualized-rate (hourly-decimal->annualized-percent hourly-rate)]
                   (when (pos? (or day-index 0))
                     {:day-index day-index
                      :value annualized-rate
                      :undefined? (not (finite-number? annualized-rate))})))))
       (sort-by :day-index)
       vec))

(defn- domain-range
  [points]
  (let [values (vec (keep :value points))]
    (if (seq values)
      (let [minimum (apply min (cons 0 values))
            maximum (apply max (cons 0 values))
            spread (- maximum minimum)
            scale-anchor (max (js/Math.abs minimum)
                              (js/Math.abs maximum)
                              1)
            padding (if (zero? spread)
                      (* scale-anchor 0.2)
                      (* spread 0.12))]
        [(- minimum padding)
         (+ maximum padding)])
      [-1 1])))

(defn- y-for-value
  [value top chart-height min-value max-value]
  (let [span (max 1e-9 (- max-value min-value))
        normalized (/ (- value min-value) span)]
    (+ top (* (- 1 normalized) chart-height))))

(defn- signed-percent-label
  [value]
  (if (finite-number? value)
    (let [sign (cond
                 (pos? value) "+"
                 (neg? value) "-"
                 :else "")]
      (str sign
           (fmt/format-fixed-number (js/Math.abs value) 2)
           "%"))
    "0.00%"))

(defn- bar-fill
  [{:keys [value undefined?]}]
  (cond
    (or undefined? (not (finite-number? value))) undefined-fill
    (neg? value) negative-fill
    :else positive-fill))

(defn funding-rate-plot
  [series]
  (let [points (normalize-series series)
        point-count (count points)
        left margin-left
        top margin-top
        chart-width (max 1 (- plot-width margin-left margin-right))
        chart-height (max 1 (- plot-height margin-top margin-bottom))
        [domain-min domain-max] (domain-range points)
        baseline-y (y-for-value 0 top chart-height domain-min domain-max)
        top-y (y-for-value domain-max top chart-height domain-min domain-max)
        bottom-y (y-for-value domain-min top chart-height domain-min domain-max)
        tick-start-y (+ bottom-y 1)
        tick-end-y (+ bottom-y 4)
        tick-label-y (+ bottom-y 11)
        step (if (pos? point-count)
               (/ chart-width point-count)
               chart-width)
        bar-width (if (pos? point-count)
                    (-> (* step 0.78)
                        (max 1.2)
                        (min 6.8))
                    2)]
    [:div {:class ["mt-2"
                   "rounded-md"
                   "border"
                   "border-base-300"
                   "bg-base-100/80"
                   "px-2.5"
                   "py-2"]}
     [:h5 {:class ["mb-1"
                   "text-[0.8rem]"
                   "font-semibold"
                   "text-gray-300"
                   "text-center"]}
      "Funding Rate"]
     [:svg {:viewBox (str "0 0 " plot-width " " plot-height)
            :class ["h-[7rem]" "w-full"]
            :role "img"
            :aria-label "Funding rate bar chart for daily mean rates over the last 30 days"}
      [:line {:x1 left
              :y1 top-y
              :x2 (+ left chart-width)
              :y2 top-y
              :stroke grid-stroke
              :stroke-width 1}]
      [:line {:x1 left
              :y1 baseline-y
              :x2 (+ left chart-width)
              :y2 baseline-y
              :stroke axis-stroke
              :stroke-width 1.1}]
      [:line {:x1 left
              :y1 bottom-y
              :x2 (+ left chart-width)
              :y2 bottom-y
              :stroke grid-stroke
              :stroke-width 1}]
      (for [[idx {:keys [day-index value] :as point}] (map-indexed vector points)]
        (let [x (+ left
                   (* idx step)
                   (/ (- step bar-width) 2))
              numeric-value? (finite-number? value)
              target-y (if numeric-value?
                         (y-for-value value top chart-height domain-min domain-max)
                         baseline-y)
              bar-height (if numeric-value?
                           (max 1 (js/Math.abs (- baseline-y target-y)))
                           1)
              bar-y (if numeric-value?
                      (min baseline-y target-y)
                      (- baseline-y 0.5))]
          ^{:key (str "day-" day-index)}
          [:rect {:x x
                  :y bar-y
                  :width bar-width
                  :height bar-height
                  :rx 0.9
                  :fill (bar-fill point)
                  :data-day day-index
                  :data-funding-rate-value (if numeric-value?
                                             (str value)
                                             "")}]))
      (for [tick day-ticks
            :let [idx (dec tick)]
            :when (and (>= idx 0)
                       (< idx point-count))]
        (let [x (+ left (* idx step) (/ step 2))]
          ^{:key (str "tick-" tick)}
          [:g
           [:line {:x1 x
                   :y1 tick-start-y
                   :x2 x
                   :y2 tick-end-y
                   :stroke axis-stroke
                   :stroke-width 1}]
           [:text {:x x
                   :y tick-label-y
                   :text-anchor "middle"
                   :font-size "8.8"
                   :fill "rgba(226,235,244,0.9)"}
            (str tick)]]))
      [:text {:x (+ left 1)
              :y (+ top-y 3)
              :font-size "8.2"
              :fill "rgba(226,235,244,0.9)"}
       (signed-percent-label domain-max)]
      [:text {:x (+ left 1)
              :y (+ baseline-y 3)
              :font-size "8.2"
              :fill "rgba(226,235,244,0.9)"}
       "0%"]
      [:text {:x (+ left 1)
              :y (+ bottom-y -2)
              :font-size "8.2"
              :fill "rgba(226,235,244,0.9)"}
       (signed-percent-label domain-min)]
      [:text {:x (+ left (/ chart-width 2))
              :y (- plot-height 1)
              :text-anchor "middle"
              :font-size "8.8"
              :fill "rgba(226,235,244,0.9)"}
       "Day (oldest to newest)"]]]))
