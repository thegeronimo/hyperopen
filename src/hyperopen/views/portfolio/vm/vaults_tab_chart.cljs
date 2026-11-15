(ns hyperopen.views.portfolio.vm.vaults-tab-chart
  "Geometry for the Vaults tab chart: vault value against net deposited, in a
  0-100 square so the view can stretch it and keep markers round."
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.portfolio.format :as portfolio-format]))

(def max-markers 60)

(def ^:private max-points 400)

(def ^:private tick-count 4)

(def ^:private date-options
  {:year "numeric" :month "short" :day "2-digit"})

(defn- finite?
  [x]
  (and (number? x) (js/isFinite x)))

(defn- thin
  "At most `max-points` points, always keeping the first and the last."
  [points]
  (let [n (count points)]
    (if (<= n max-points)
      points
      (let [stride (js/Math.ceil (/ n max-points))]
        (conj (into [] (take-nth stride) (pop points)) (peek points))))))

(defn- scale
  [lo hi x]
  (if (> hi lo) (* 100 (/ (- x lo) (- hi lo))) 50))

(defn- coords
  [x-of y-of points k]
  (->> points
       (map (fn [p] (str (.toFixed (x-of (:time-ms p)) 2) "," (.toFixed (y-of (get p k)) 2))))
       (interpose " ")
       (apply str)))

(defn- iso-date
  [ms]
  (.slice (.toISOString (js/Date. ms)) 0 10))

(defn marker-title
  [{:keys [kind source amount time-ms name vault-address]}]
  (str (cond (= :distribution source) "Distribution "
             (= :withdraw kind) "Withdrawal "
             :else "Deposit ")
       (portfolio-format/format-currency amount)
       " · " (or name vault-address "vault")
       " · " (iso-date time-ms)))

(defn- nearest-net-deposited
  [series time-ms]
  (:net-deposited
   (reduce (fn [best p]
             (if (< (js/Math.abs (- (:time-ms p) time-ms))
                    (js/Math.abs (- (:time-ms best) time-ms)))
               p
               best))
           (first series)
           series)))

(defn top-markers
  "The `max-markers` largest transfers, in time order."
  [markers]
  (->> markers
       (filter #(and (finite? (:time-ms %)) (finite? (:amount %))))
       (sort-by :amount >)
       (take max-markers)
       (sort-by :time-ms)
       vec))

(defn chart-model
  "nil when the series has too few points to draw a line."
  [{:keys [series markers]}]
  (let [series (filterv #(and (finite? (:time-ms %)) (finite? (:value %)) (finite? (:net-deposited %)))
                        series)]
    (when (>= (count series) 2)
      (let [t0 (:time-ms (first series))
            t1 (:time-ms (peek series))
            ys (mapcat (juxt :value :net-deposited) series)
            lo (apply min ys)
            hi (apply max ys)
            pad (* 0.06 (if (> hi lo) (- hi lo) (max 1 (js/Math.abs hi))))
            lo* (if (>= lo 0) (max 0 (- lo pad)) (- lo pad))
            hi* (+ hi pad)
            x-of #(scale t0 t1 %)
            y-of #(- 100 (scale lo* hi* %))
            drawn (thin series)]
        {:value-points (coords x-of y-of drawn :value)
         :net-points (coords x-of y-of drawn :net-deposited)
         :ticks (mapv (fn [i]
                        (let [v (+ lo* (* (- hi* lo*) (/ i (dec tick-count))))]
                          {:top (y-of v)
                           :label (portfolio-format/format-compact-currency v)}))
                      (range tick-count))
         :first-label (or (fmt/format-intl-date-time t0 date-options) "--")
         :last-label (or (fmt/format-intl-date-time t1 date-options) "--")
         :markers (mapv (fn [{:keys [time-ms kind] :as m}]
                          {:left (x-of (min t1 (max t0 time-ms)))
                           :top (y-of (nearest-net-deposited series time-ms))
                           :kind kind
                           :title (marker-title m)})
                        (top-markers markers))}))))
