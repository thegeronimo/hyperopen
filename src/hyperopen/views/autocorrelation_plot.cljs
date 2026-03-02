(ns hyperopen.views.autocorrelation-plot)

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
  22)

(def ^:private ticks
  [1 5 10 15 20 25 29])

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

(defn- clamp
  [value min-value max-value]
  (max min-value
       (min max-value value)))

(defn- y-for-value
  [value top chart-height]
  (let [normalized (/ (+ 1 (clamp value -1 1)) 2)]
    (+ top (* (- 1 normalized) chart-height))))

(defn- normalize-lag
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseFloat value)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num)))
      (js/Math.floor num))))

(defn- normalize-series
  [series]
  (->> (or series [])
       (keep (fn [point]
               (when (map? point)
                 (let [lag-days (normalize-lag (:lag-days point))
                       value (when (finite-number? (:value point))
                               (clamp (:value point) -1 1))]
                   (when (pos? (or lag-days 0))
                     {:lag-days lag-days
                      :value value
                      :undefined? (true? (:undefined? point))})))))
       (sort-by :lag-days)
       vec))

(defn- bar-fill
  [{:keys [value undefined?]}]
  (cond
    (or undefined? (not (finite-number? value))) undefined-fill
    (neg? value) negative-fill
    :else positive-fill))

(defn autocorrelation-plot
  [series]
  (let [points (normalize-series series)
        point-count (count points)
        left margin-left
        top margin-top
        chart-width (max 1 (- plot-width margin-left margin-right))
        chart-height (max 1 (- plot-height margin-top margin-bottom))
        baseline-y (y-for-value 0 top chart-height)
        top-y (y-for-value 1 top chart-height)
        bottom-y (y-for-value -1 top chart-height)
        tick-start-y (+ bottom-y 1)
        tick-end-y (+ bottom-y 4)
        tick-label-y (+ bottom-y 12)
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
      "Autocorrelation"]
     [:svg {:viewBox (str "0 0 " plot-width " " plot-height)
            :class ["h-[7rem]" "w-full"]
            :role "img"
            :aria-label "Autocorrelation bar chart for lag days 1 through 29 over the last 30 days"}
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
      (for [[idx {:keys [lag-days value] :as point}] (map-indexed vector points)]
        (let [x (+ left
                   (* idx step)
                   (/ (- step bar-width) 2))
              target-y (if (finite-number? value)
                         (y-for-value value top chart-height)
                         baseline-y)
              bar-y (min baseline-y target-y)
              bar-height (max 1 (js/Math.abs (- baseline-y target-y)))]
          ^{:key (str "lag-" lag-days)}
          [:rect {:x x
                  :y bar-y
                  :width bar-width
                  :height bar-height
                  :rx 0.9
                  :fill (bar-fill point)
                  :data-lag lag-days
                  :data-autocorrelation-value (if (finite-number? value)
                                                (str value)
                                                "")}]))
      (for [tick ticks
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
       "+1"]
      [:text {:x (+ left 1)
              :y (+ baseline-y 3)
              :font-size "8.2"
              :fill "rgba(226,235,244,0.9)"}
       "0"]
      [:text {:x (+ left 1)
              :y (+ bottom-y -2)
              :font-size "8.2"
              :fill "rgba(226,235,244,0.9)"}
       "-1"]
      [:text {:x (+ left (/ chart-width 2))
              :y (- plot-height 3)
              :text-anchor "middle"
              :font-size "8.8"
              :fill "rgba(226,235,244,0.9)"}
       "Lag (days)"]]]))
