(ns hyperopen.funding.predictability
  (:require [hyperopen.portfolio.metrics.math :as math]))

(def thirty-day-window-ms
  (* 30 24 60 60 1000))

(def day-ms
  (* 24 60 60 1000))

(def ^:private autocorrelation-lag-days
  [1 5 15])

(def ^:private autocorrelation-max-lag-days
  29)

(def ^:private autocorrelation-series-lag-days
  (vec (range 1 (inc autocorrelation-max-lag-days))))

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

(defn- parse-ms
  [value]
  (when-let [parsed (parse-number value)]
    (js/Math.floor parsed)))

(defn- day-start-ms
  [time-ms]
  (* day-ms
     (js/Math.floor (/ time-ms day-ms))))

(defn- rolling-window-start-ms
  [now-ms]
  (let [end-day-ms (day-start-ms now-ms)]
    (max 0 (- end-day-ms (* 29 day-ms)))))

(defn- normalize-row
  [row]
  (when (map? row)
    (let [time-ms (or (parse-ms (:time-ms row))
                      (parse-ms (:time row)))
          funding-rate (or (parse-number (:funding-rate-raw row))
                           (parse-number (:fundingRate row))
                           (parse-number (:funding-rate row)))]
      (when (and (number? time-ms)
                 (number? funding-rate))
        {:time-ms time-ms
         :funding-rate-raw funding-rate}))))

(defn- normalize-rows-for-window
  [rows now-ms]
  (let [now-ms* (or (parse-ms now-ms) 0)
        window-start-ms (rolling-window-start-ms now-ms*)]
    (->> (or rows [])
         (keep normalize-row)
         (filter (fn [{:keys [time-ms]}]
                   (and (<= window-start-ms time-ms)
                        (<= time-ms now-ms*))))
         (sort-by :time-ms)
         vec)))

(defn- build-daily-index
  [rows]
  (reduce (fn [acc {:keys [time-ms funding-rate-raw]}]
            (let [bucket (day-start-ms time-ms)
                  current (get acc bucket {:sum 0
                                           :count 0})]
              (assoc acc
                     bucket
                     {:sum (+ (:sum current) funding-rate-raw)
                      :count (inc (:count current))})))
          {}
          rows))

(defn- daily-series
  [rows now-ms]
  (let [now-ms* (or (parse-ms now-ms) 0)
        window-start-ms (rolling-window-start-ms now-ms*)
        start-day-ms (day-start-ms window-start-ms)
        end-day-ms (day-start-ms now-ms*)
        day-index (build-daily-index rows)]
    (if (> start-day-ms end-day-ms)
      []
      (loop [current-day-ms start-day-ms
             result []]
        (if (> current-day-ms end-day-ms)
          result
          (let [{:keys [sum count]} (get day-index current-day-ms)
                mean-rate (when (pos? (or count 0))
                            (/ sum count))
                ;; Effective rate for a 24-hour hold
                daily-rate (when mean-rate
                             (* mean-rate 24))]
            (recur (+ current-day-ms day-ms)
                   (conj result {:day-start-ms current-day-ms
                                 :daily-rate daily-rate
                                 :sample-count (or count 0)}))))))))

(defn- annotate-daily-series
  [daily-points]
  (mapv (fn [idx point]
          (assoc point :day-index (inc idx)))
        (range)
        daily-points))

(defn- lag-autocorrelation
  [daily-points lag-days]
  (let [minimum-daily-count (inc lag-days)
        daily-observations (count (filter :daily-rate daily-points))]
    (if (< daily-observations minimum-daily-count)
      {:lag-days lag-days
       :minimum-daily-count minimum-daily-count
       :pair-count 0
       :insufficient? true
       :undefined? true
       :value nil}
      (let [pairs (->> (range lag-days (count daily-points))
                       (keep (fn [idx]
                               (let [current (nth daily-points idx)
                                     lagged (nth daily-points (- idx lag-days))
                                     current-value (:daily-rate current)
                                     lagged-value (:daily-rate lagged)]
                                 (when (and (number? current-value)
                                            (number? lagged-value))
                                   [current-value lagged-value]))))
                       vec)
            xs (mapv first pairs)
            ys (mapv second pairs)
            value (math/pearson-correlation xs ys)
            pair-count (count pairs)]
        {:lag-days lag-days
         :minimum-daily-count minimum-daily-count
         :pair-count pair-count
         :insufficient? false
         :undefined? (or (< pair-count 2)
                         (nil? value))
         :value (when (>= pair-count 2)
                  value)}))))

(defn compute-30d-summary
  [rows now-ms]
  (let [now-ms* (or (parse-ms now-ms) 0)
        window-start-ms (rolling-window-start-ms now-ms*)
        rows* (normalize-rows-for-window rows now-ms*)
        daily-points (daily-series rows* now-ms*)
        valid-daily-rates (keep :daily-rate daily-points)
        daily-mean (when (seq valid-daily-rates)
                     (math/mean valid-daily-rates))
        daily-stddev (when (seq valid-daily-rates)
                       (math/sample-stddev valid-daily-rates))
        lag-series (mapv (fn [lag-days]
                           (lag-autocorrelation daily-points lag-days))
                         autocorrelation-series-lag-days)
        lag-series-by-day (into {}
                                (map (fn [lag-stat]
                                       [(:lag-days lag-stat) lag-stat]))
                                lag-series)
        lag-stats (into {}
                        (map (fn [lag-days]
                               [(keyword (str "lag-" lag-days "d"))
                                (get lag-series-by-day lag-days)])
                             autocorrelation-lag-days))]
    {:window-start-ms window-start-ms
     :window-end-ms now-ms*
     :sample-count (count rows*)
     :daily-count (count valid-daily-rates)
     :mean daily-mean
     :stddev daily-stddev
     :daily-funding-series (annotate-daily-series daily-points)
     :autocorrelation-series lag-series
     :autocorrelation lag-stats}))
