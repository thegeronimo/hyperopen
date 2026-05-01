(ns hyperopen.portfolio.optimizer.application.history-loader.normalization
  (:require [hyperopen.portfolio.metrics.history :as metrics-history]))

(defn finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn parse-number
  [value]
  (cond
    (finite-number? value)
    value

    (string? value)
    (let [parsed (js/parseFloat value)]
      (when (finite-number? parsed)
        parsed))

    :else
    nil))

(defn parse-ms
  [value]
  (when-let [parsed (parse-number value)]
    (js/Math.floor parsed)))

(defn- normalize-candle-row
  [row]
  (when (map? row)
    (let [time-ms (or (parse-ms (:time-ms row))
                      (parse-ms (:time row))
                      (parse-ms (:t row))
                      (parse-ms (:T row)))
          close (or (parse-number (:close row))
                    (parse-number (:c row))
                    (parse-number (:close-price row))
                    (parse-number (:px row)))]
      (when (and (number? time-ms)
                 (number? close)
                 (pos? close))
        {:time-ms time-ms
         :close close}))))

(defn normalize-candle-history
  [rows]
  (->> rows
       (keep normalize-candle-row)
       (reduce (fn [acc row]
                 (assoc acc (:time-ms row) row))
               {})
       vals
       (sort-by :time-ms)
       vec))

(defn- selected-vault-summary
  [details]
  (metrics-history/preferred-vault-summary
   (or (:portfolio details) {})))

(defn- cumulative-percent-row->price-row
  [row]
  (let [time-ms (if (map? row)
                  (or (parse-ms (:time-ms row))
                      (parse-ms (:time row)))
                  (parse-ms (first row)))
        percent (if (map? row)
                  (or (parse-number (:percent row))
                      (parse-number (:value row)))
                  (parse-number (second row)))
        close (when (number? percent)
                (* 100 (+ 1 (/ percent 100))))]
    (when (and (number? time-ms)
               (number? close)
               (pos? close))
      {:time-ms time-ms
       :close close})))

(defn normalize-vault-history
  [details]
  (->> (metrics-history/returns-history-rows-from-summary
        (or (selected-vault-summary details) {}))
       (keep cumulative-percent-row->price-row)
       (reduce (fn [acc row]
                 (assoc acc (:time-ms row) row))
               {})
       vals
       (sort-by :time-ms)
       vec))

(defn- normalize-funding-row
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

(defn normalize-funding-history
  [rows]
  (->> rows
       (keep normalize-funding-row)
       (reduce (fn [acc row]
                 (assoc acc (:time-ms row) row))
               {})
       vals
       (sort-by :time-ms)
       vec))

(defn- day-start-ms
  [time-ms]
  (when (number? time-ms)
    (some-> time-ms
            metrics-history/day-string-from-ms
            metrics-history/parse-day-ms)))

(defn daily-price-history
  [history]
  (->> (or history [])
       (sort-by :time-ms)
       (keep (fn [{:keys [time-ms] :as row}]
               (when-let [day-ms (day-start-ms time-ms)]
                 (assoc row :time-ms day-ms))))
       (reduce (fn [acc row]
                 (assoc acc (:time-ms row) row))
               {})
       vals
       (sort-by :time-ms)
       vec))
