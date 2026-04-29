(ns hyperopen.views.portfolio.optimize.frontier-chart-axes
  (:require [hyperopen.views.portfolio.optimize.format :as opt-format]))

(defn- nice-step
  [span tick-count]
  (let [rough-step (/ span (max 1 tick-count))
        magnitude (js/Math.pow 10 (js/Math.floor (/ (js/Math.log rough-step) (.-LN10 js/Math))))
        fraction (/ rough-step magnitude)
        nice-fraction (cond
                        (<= fraction 1) 1
                        (<= fraction 2) 2
                        (<= fraction 5) 5
                        :else 10)]
    (* nice-fraction magnitude)))

(defn axis-ticks
  [domain* tick-count]
  (let [[domain-min domain-max] domain*
        span (max 0.0001 (- domain-max domain-min))
        step (nice-step span tick-count)
        tick-min (* (js/Math.floor (/ domain-min step)) step)
        tick-max (* (js/Math.ceil (/ domain-max step)) step)]
    (loop [value tick-min
           ticks []]
      (if (> value (+ tick-max (/ step 2)))
        ticks
        (recur (+ value step)
               (conj ticks (if (< (js/Math.abs value) 1.0e-10) 0 value)))))))

(defn tick-domain
  [ticks fallback-domain]
  (if (seq ticks)
    [(first ticks) (last ticks)]
    fallback-domain))

(defn- axis-pct-label
  [value]
  (let [abs-pct (js/Math.abs (* 100 value))
        fraction-digits (cond
                          (>= abs-pct 10) 0
                          (>= abs-pct 1) 1
                          :else 2)]
    (opt-format/format-pct value {:minimum-fraction-digits 0
                                  :maximum-fraction-digits fraction-digits})))

(defn- scale-value
  [domain-min domain-max range-min range-max value]
  (if (and (opt-format/finite-number? value)
           (not= domain-min domain-max))
    (+ range-min
       (* (/ (- value domain-min)
             (- domain-max domain-min))
          (- range-max range-min)))
    (/ (+ range-min range-max) 2)))

(defn x-tick-position
  [{:keys [left right]} x-domain value]
  (scale-value (first x-domain) (second x-domain) left right value))

(defn y-tick-position
  [{:keys [top bottom]} y-domain value]
  (scale-value (first y-domain) (second y-domain) bottom top value))

(defn tick-label
  [{:keys [left bottom]} orientation idx position value]
  (case orientation
    :x
    [:text {:key (str "x-label-" idx)
            :x position
            :y (+ bottom 22)
            :fill "currentColor"
            :fontSize 10
            :opacity 0.72
            :text-anchor "middle"
            :data-role (str "portfolio-optimizer-frontier-x-tick-" idx)}
     (axis-pct-label value)]
    [:text {:key (str "y-label-" idx)
            :x (- left 8)
            :y (+ position 4)
            :fill "currentColor"
            :fontSize 10
            :opacity 0.72
            :text-anchor "end"
            :data-role (str "portfolio-optimizer-frontier-y-tick-" idx)}
     (axis-pct-label value)]))
