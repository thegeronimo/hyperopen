(ns hyperopen.portfolio.optimizer.application.history-loader.normalization
  (:require [hyperopen.portfolio.metrics.history :as metrics-history]))

(def ^:private direct-vault-window-preference
  [:one-year :six-month :three-month :month :week :day])

(def ^:private derived-one-year-vault-min-observations
  3)

(declare cumulative-percent-row->price-row)

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

(defn- normalize-price-history
  [rows]
  (->> rows
       (keep cumulative-percent-row->price-row)
       (reduce (fn [acc row]
                 (assoc acc (:time-ms row) row))
               {})
       vals
       (sort-by :time-ms)
       vec))

(defn- summary-entry
  [portfolio summary-key]
  (let [summary (get portfolio summary-key)]
    (when (map? summary)
      summary)))

(defn- first-any-summary-entry
  [portfolio]
  (some (fn [[summary-key summary]]
          (when (map? summary)
            [summary-key summary]))
        portfolio))

(defn- normalized-summary-candidate
  [source window summary]
  (let [history (normalize-price-history
                 (metrics-history/returns-history-rows-from-summary
                  (or summary {})))]
    (when (seq history)
      {:source source
       :window window
       :history history})))

(defn vault-history-candidates
  [details]
  (let [portfolio (or (:portfolio details) {})
        all-time-summary (summary-entry portfolio :all-time)
        derived-one-year-context (when all-time-summary
                                   (metrics-history/bounded-summary-context
                                    all-time-summary
                                    :one-year))
        derived-one-year-summary (let [summary (:summary derived-one-year-context)
                                       observations (count
                                                     (metrics-history/returns-history-rows-from-summary
                                                      (or summary {})))]
                                   (when (and (:complete-window? derived-one-year-context)
                                              (>= observations
                                                  derived-one-year-vault-min-observations))
                                     summary))
        [first-any-window first-any-summary] (or (first-any-summary-entry portfolio)
                                                 [nil nil])]
    (->> (concat [(normalized-summary-candidate :direct-one-year
                                                :one-year
                                                (summary-entry portfolio :one-year))
                  (normalized-summary-candidate :derived-one-year
                                                :one-year
                                                derived-one-year-summary)]
                 (map (fn [window]
                        (normalized-summary-candidate
                         (keyword (str "direct-" (name window)))
                         window
                         (summary-entry portfolio window)))
                      (rest direct-vault-window-preference))
                 [(normalized-summary-candidate :all-time
                                               :all-time
                                               all-time-summary)
                  (normalized-summary-candidate :first-any
                                               (or first-any-window :all-time)
                                               first-any-summary)])
         (keep identity)
         (reduce (fn [{:keys [seen candidates]} candidate]
                   (if (contains? seen (:history candidate))
                     {:seen seen
                      :candidates candidates}
                     {:seen (conj seen (:history candidate))
                      :candidates (conj candidates candidate)}))
                 {:seen #{}
                  :candidates []})
         :candidates)))

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
  (or (some-> details
              vault-history-candidates
              first
              :history)
      []))

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
