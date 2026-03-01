(ns hyperopen.portfolio.metrics.quality
  (:require [hyperopen.portfolio.metrics.math :as math]
            [hyperopen.portfolio.metrics.history :as history]))

(def default-quality-gates
  {:core-min-intervals 10
   :core-min-span-days 30
   :core-high-min-intervals 20
   :core-high-min-span-days 90
   :core-high-max-cv-gap 1.5
   :core-high-max-gap-days 30
   :sortino-min-intervals 20
   :sortino-min-downside 5
   :daily-min-points 60
   :daily-min-coverage 0.90
   :daily-max-missing-streak 3
   :psr-min-points 252
   :drawdown-max-gap-days 2
   :drawdown-min-daily-points 180
   :benchmark-min-points 10
   :rolling-min-fraction 0.5
   :structural-gap-days 90})

(defn daily-max-missing-streak
  [daily-rows]
  (let [day-indices (->> (history/normalize-daily-rows daily-rows)
                         (map (fn [{:keys [time-ms]}]
                                (js/Math.floor (/ time-ms history/day-ms))))
                         distinct
                         sort
                         vec)]
    (if (< (count day-indices) 2)
      0
      (reduce (fn [best [left right]]
                (max best (max 0 (dec (- right left)))))
              0
              (partition 2 1 day-indices)))))

(defn cadence-diagnostics
  [intervals daily-rows mar]
  (let [interval-count (count intervals)
        total-years (reduce (fn [acc {:keys [dt-years]}] (+ acc dt-years)) 0 intervals)
        span-days (* total-years 365.2425)
        gap-days (mapv :dt-days intervals)
        median-gap (when (seq gap-days) (math/quantile gap-days 0.5))
        p95-gap (when (seq gap-days) (math/quantile gap-days 0.95))
        max-gap (when (seq gap-days) (apply max gap-days))
        mean-gap (math/mean gap-days)
        gap-std (math/sample-stddev gap-days)
        cv-gap (if (and (number? mean-gap)
                        (pos? mean-gap)
                        (number? gap-std))
                 (/ gap-std mean-gap)
                 nil)
        downside-threshold (if (and (number? mar) (> mar -1)) (js/Math.log (+ 1 mar)) 0)
        downside-count (count (filter (fn [{:keys [log-return dt-years]}]
                                        (< log-return (* downside-threshold dt-years)))
                                      intervals))
        normalized-daily (history/normalize-daily-rows daily-rows)
        daily-points (count normalized-daily)
        total-calendar-days (if (pos? span-days)
                              (max 1 (inc (int (js/Math.floor span-days))))
                              0)
        daily-coverage (if (pos? total-calendar-days)
                         (/ daily-points total-calendar-days)
                         0)
        obs-density-per-week (if (pos? span-days)
                               (/ interval-count (/ span-days 7))
                               0)]
    {:interval-count interval-count
     :span-days span-days
     :total-years total-years
     :median-gap-days median-gap
     :p95-gap-days p95-gap
     :max-gap-days max-gap
     :cv-gap cv-gap
     :downside-count downside-count
     :daily-points daily-points
     :daily-coverage daily-coverage
     :daily-max-missing-streak (daily-max-missing-streak normalized-daily)
     :obs-density-per-week obs-density-per-week}))

(defn compute-quality-gates
  [diagnostics gates]
  (let [{:keys [interval-count
                span-days
                cv-gap
                max-gap-days
                downside-count
                daily-points
                daily-coverage
                daily-max-missing-streak]} diagnostics]
    {:core-min? (and (>= interval-count (:core-min-intervals gates))
                     (>= span-days (:core-min-span-days gates)))
     :core-high-confidence? (and (>= interval-count (:core-high-min-intervals gates))
                                 (>= span-days (:core-high-min-span-days gates))
                                 (number? cv-gap)
                                 (<= cv-gap (:core-high-max-cv-gap gates))
                                 (number? max-gap-days)
                                 (<= max-gap-days (:core-high-max-gap-days gates)))
     :sortino-min? (and (>= interval-count (:sortino-min-intervals gates))
                        (>= downside-count (:sortino-min-downside gates)))
     :daily-min? (and (>= daily-points (:daily-min-points gates))
                      (>= daily-coverage (:daily-min-coverage gates))
                      (<= daily-max-missing-streak (:daily-max-missing-streak gates)))
     :psr-min? (>= daily-points (:psr-min-points gates))
     :drawdown-reliable? (and (number? max-gap-days)
                              (<= max-gap-days (:drawdown-max-gap-days gates))
                              (>= daily-points (:drawdown-min-daily-points gates)))
     :structural-gap? (and (number? max-gap-days)
                           (> max-gap-days (:structural-gap-days gates)))}))