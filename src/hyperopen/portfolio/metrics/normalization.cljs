(ns hyperopen.portfolio.metrics.normalization
  (:require [clojure.string :as str]
            [hyperopen.portfolio.metrics.parsing :as parsing]))

(def day-ms (* 24 60 60 1000))

(defn optional-number [value] (parsing/optional-number value))
(defn finite-number? [value] (parsing/finite-number? value))
(defn history-point-value [row] (parsing/history-point-value row))
(defn history-point-time-ms [row] (parsing/history-point-time-ms row))
(defn day-string-from-ms [time-ms] (parsing/day-string-from-ms time-ms))
(defn parse-day-ms [day] (parsing/parse-day-ms day))

(defn- dedupe-rows-by-time
  [rows]
  (reduce (fn [acc {:keys [time-ms] :as row}]
            (if (and (seq acc)
                     (= (:time-ms (peek acc)) time-ms))
              (conj (pop acc) row)
              (conj acc row)))
          []
          rows))

(defn history-points
  [rows]
  (->> (or rows [])
       (keep (fn [row]
               (let [time-ms (history-point-time-ms row)
                     value (history-point-value row)]
                 (when (and (finite-number? time-ms)
                            (finite-number? value))
                   {:time-ms time-ms
                    :value value}))))
       (sort-by :time-ms)
       dedupe-rows-by-time
       vec))

(defn canonical-history-rows
  [rows]
  (mapv (fn [{:keys [time-ms value]}]
          [time-ms value])
        (history-points rows)))

(defn normalize-summary
  [summary]
  {:accountValueHistory (canonical-history-rows (:accountValueHistory summary))
   :pnlHistory (canonical-history-rows (:pnlHistory summary))})

(defn aligned-account-pnl-points
  [summary]
  (let [normalized (normalize-summary summary)
        pnl-by-time (into {} (:pnlHistory normalized))]
    (->> (:accountValueHistory normalized)
         (keep (fn [[time-ms account-value]]
                 (when-let [pnl-value (get pnl-by-time time-ms)]
                   {:time-ms time-ms
                    :account-value account-value
                    :pnl-value pnl-value})))
         vec)))

(defn- first-positive-account-index
  [points]
  (first (keep-indexed (fn [idx {:keys [account-value]}]
                         (when (pos? account-value)
                           idx))
                       points)))

(defn anchored-account-pnl-points
  [summary]
  (let [points (aligned-account-pnl-points summary)
        anchor-index (first-positive-account-index points)]
    (if (some? anchor-index)
      (subvec points anchor-index)
      [])))

(defn normalize-daily-rows
  [rows]
  (->> (or rows [])
       (keep (fn [row]
               (let [return (or (optional-number (:return row))
                                (history-point-value row))
                     time-ms (or (optional-number (:time-ms row))
                                 (history-point-time-ms row))
                     day (or (some-> (:day row) str str/trim)
                             (when (finite-number? time-ms)
                               (day-string-from-ms time-ms)))]
                 (when (and (finite-number? return)
                            (finite-number? time-ms)
                            (seq day))
                   {:day day
                    :time-ms time-ms
                    :return return}))))
       (sort-by :time-ms)
       dedupe-rows-by-time
       vec))

(defn normalize-cumulative-percent-rows
  [rows]
  (->> (or rows [])
       (keep (fn [row]
               (let [time-ms (history-point-time-ms row)
                     percent (history-point-value row)
                     factor (when (finite-number? percent)
                              (+ 1 (/ percent 100)))]
                 (when (and (finite-number? time-ms)
                            (finite-number? percent)
                            (finite-number? factor)
                            (not (neg? factor)))
                   {:time-ms time-ms
                    :percent percent
                    :factor factor}))))
       (sort-by :time-ms)
       dedupe-rows-by-time
       vec))
