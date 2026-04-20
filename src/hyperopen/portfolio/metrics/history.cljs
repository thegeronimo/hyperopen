(ns hyperopen.portfolio.metrics.history
  (:require [hyperopen.portfolio.metrics.normalization :as normalization]))

(def day-ms normalization/day-ms)
(def ms-per-year (* 365.2425 24 60 60 1000))
(def epsilon 1e-12)

(defn optional-number [value] (normalization/optional-number value))
(defn finite-number? [value] (normalization/finite-number? value))
(defn history-point-value [row] (normalization/history-point-value row))
(defn history-point-time-ms [row] (normalization/history-point-time-ms row))
(defn day-string-from-ms [time-ms] (normalization/day-string-from-ms time-ms))
(defn parse-day-ms [day] (normalization/parse-day-ms day))
(defn history-points [rows] (normalization/history-points rows))
(defn dedupe-history-points-by-time [points] (normalization/dedupe-history-points-by-time points))
(defn aligned-account-pnl-points [summary] (normalization/aligned-account-pnl-points summary))
(defn normalize-daily-rows [rows] (normalization/normalize-daily-rows rows))
(defn normalize-cumulative-percent-rows [rows] (normalization/normalize-cumulative-percent-rows rows))

(defn clamp-near-zero
  [value]
  (if (< (js/Math.abs value) epsilon)
    0
    value))

(defn- anchored-account-pnl-points
  [summary]
  (normalization/anchored-account-pnl-points summary))

(defn- implied-cash-flow
  [previous current]
  (let [delta-account (- (:account-value current)
                         (:account-value previous))
        delta-pnl (- (:pnl-value current)
                     (:pnl-value previous))]
    (- delta-account delta-pnl)))

(defn- cash-flow-ratio
  [previous-account-value implied-cash-flow*]
  (if (finite-number? previous-account-value)
    (js/Math.abs (/ implied-cash-flow*
                    (max previous-account-value 1)))
    js/Number.POSITIVE_INFINITY))

(defn- modified-dietz-return
  [delta-pnl previous-account-value implied-cash-flow*]
  (let [denominator (+ previous-account-value
                       (* 0.5 implied-cash-flow*))
        flow-ratio (cash-flow-ratio previous-account-value implied-cash-flow*)]
    (when (and (finite-number? denominator)
               (pos? denominator)
               (finite-number? flow-ratio)
               (< flow-ratio 0.5))
      (/ delta-pnl denominator))))

(defn- fallback-period-return
  [delta-pnl previous-account-value]
  (when (and (finite-number? previous-account-value)
             (pos? previous-account-value))
    (/ delta-pnl previous-account-value)))

(defn- indeterminate-cash-flow?
  [previous current implied-cash-flow*]
  (let [previous-account-value (:account-value previous)
        flow-ratio (cash-flow-ratio previous-account-value implied-cash-flow*)]
    (or (not (finite-number? flow-ratio))
        (and (pos? (:account-value current))
             (>= flow-ratio 0.5)))))

(defn- bounded-period-return
  [previous current]
  (let [previous-account-value (:account-value previous)
        delta-pnl (- (:pnl-value current)
                     (:pnl-value previous))
        implied-cash-flow* (implied-cash-flow previous current)
        period-return (cond
                        (indeterminate-cash-flow? previous current implied-cash-flow*)
                        0

                        :else
                        (or (modified-dietz-return delta-pnl
                                                   previous-account-value
                                                   implied-cash-flow*)
                            (fallback-period-return delta-pnl previous-account-value)
                            0))]
    (if (finite-number? period-return)
      (max -0.999999 period-return)
      0)))

(defn- cumulative-percent-from-factor
  [cumulative-factor]
  (* 100 (- cumulative-factor 1)))

(defn- initial-returns-history-state
  [point]
  {:previous point
   :cumulative-factor 1
   :cumulative-percent 0
   :rows [[(:time-ms point) 0]]})

(defn- append-returns-history-row
  [{:keys [previous cumulative-factor cumulative-percent rows]} current]
  (let [period-return (bounded-period-return previous current)
        cumulative-factor* (* cumulative-factor (+ 1 period-return))
        cumulative-percent* (let [candidate (cumulative-percent-from-factor cumulative-factor*)]
                              (if (finite-number? candidate)
                                candidate
                                cumulative-percent))]
    {:previous current
     :cumulative-factor cumulative-factor*
     :cumulative-percent cumulative-percent*
     :rows (conj rows [(:time-ms current) cumulative-percent*])}))

(defn returns-history-rows-from-summary
  [summary]
  (let [points (anchored-account-pnl-points summary)]
    (if-let [first-point (first points)]
      (:rows (reduce append-returns-history-row
                     (initial-returns-history-state first-point)
                     (rest points)))
      [])))

(defn returns-history-rows
  ([_state summary _summary-scope]
   (returns-history-rows-from-summary summary)))

(defn cumulative-percent-rows->interval-returns
  [cumulative-percent-rows]
  (let [rows (normalize-cumulative-percent-rows cumulative-percent-rows)
        count* (count rows)]
    (if (< count* 2)
      []
      (loop [idx 1
             previous (first rows)
             output []]
        (if (>= idx count*)
          output
          (let [current (nth rows idx)
                prev-factor (:factor previous)
                curr-factor (:factor current)
                period-return (if (and (finite-number? prev-factor)
                                       (finite-number? curr-factor)
                                       (pos? prev-factor))
                                (- (/ curr-factor prev-factor) 1)
                                0)
                period-return* (if (finite-number? period-return)
                                 period-return
                                 0)]
            (recur (inc idx)
                   current
                   (conj output
                         {:time-ms (:time-ms current)
                          :return period-return*}))))))))

(defn daily-compounded-returns
  [cumulative-percent-rows]
  (let [rows (cumulative-percent-rows->interval-returns cumulative-percent-rows)]
    (if (empty? rows)
      []
      (loop [remaining rows
             current-day nil
             current-factor 1
             current-time-ms nil
             output []]
        (if (empty? remaining)
          (if (some? current-day)
            (conj output
                  {:day current-day
                   :time-ms current-time-ms
                   :return (- current-factor 1)})
            output)
          (let [{:keys [time-ms return]} (first remaining)
                day (day-string-from-ms time-ms)
                factor (+ 1 return)]
            (if (= day current-day)
              (recur (rest remaining) current-day (* current-factor factor) time-ms output)
              (recur (rest remaining) day factor time-ms
                     (if (some? current-day)
                       (conj output {:day current-day :time-ms current-time-ms :return (- current-factor 1)})
                       output)))))))))

(defn strategy-daily-compounded-returns
  [state summary summary-scope]
  (daily-compounded-returns (returns-history-rows state summary summary-scope)))

(defn returns-values
  [daily-rows]
  (->> daily-rows
       (map :return)
       (filter finite-number?)
       vec))

(defn daily-rows->cumulative-percent-rows
  [daily-rows]
  (let [rows (normalize-daily-rows daily-rows)]
    (if (seq rows)
      (let [anchor-time-ms (- (:time-ms (first rows)) day-ms)]
        (loop [remaining rows
               cumulative-factor 1
               output [[anchor-time-ms 0]]]
          (if (empty? remaining)
            output
            (let [{:keys [time-ms return]} (first remaining)
                  factor (if (finite-number? return)
                           (+ 1 return)
                           1)
                  cumulative-factor* (* cumulative-factor factor)
                  cumulative-percent (* 100 (- cumulative-factor* 1))]
              (recur (rest remaining)
                     cumulative-factor*
                     (conj output [time-ms cumulative-percent]))))))
      [])))

(defn cumulative-rows->irregular-intervals
  [cumulative-rows]
  (let [rows (normalize-cumulative-percent-rows cumulative-rows)]
    (if (< (count rows) 2)
      []
      (loop [idx 1
             previous (first rows)
             output []]
        (if (>= idx (count rows))
          output
          (let [current (nth rows idx)
                start-ms (:time-ms previous)
                end-ms (:time-ms current)
                dt-ms (- end-ms start-ms)
                dt-years (/ dt-ms ms-per-year)
                prev-factor (:factor previous)
                curr-factor (:factor current)
                ratio (if (and (finite-number? prev-factor)
                               (finite-number? curr-factor)
                               (pos? prev-factor))
                        (/ curr-factor prev-factor)
                        nil)
                simple-return (when (and (number? ratio)
                                         (finite-number? ratio))
                                (- ratio 1))
                log-return (when (and (number? ratio)
                                      (finite-number? ratio)
                                      (pos? ratio))
                             (js/Math.log ratio))]
            (recur (inc idx)
                   current
                   (if (and (number? dt-years)
                            (pos? dt-years)
                            (finite-number? dt-years)
                            (number? simple-return)
                            (finite-number? simple-return)
                            (number? log-return)
                            (finite-number? log-return))
                     (conj output
                           {:start-ms start-ms
                            :end-ms end-ms
                            :dt-ms dt-ms
                            :dt-days (/ dt-ms day-ms)
                            :dt-years dt-years
                            :simple-return simple-return
                            :log-return log-return})
                     output))))))))

(defn align-daily-returns
  [strategy-daily-rows benchmark-daily-rows]
  (let [strategy (normalize-daily-rows strategy-daily-rows)
        benchmark-by-day (into {}
                               (map (juxt :day :return))
                               (normalize-daily-rows benchmark-daily-rows))]
    (->> strategy
         (keep (fn [{:keys [day return]}]
                 (when-let [benchmark-return (get benchmark-by-day day)]
                   {:day day
                    :strategy-return return
                    :benchmark-return benchmark-return})))
         vec)))
