(ns hyperopen.portfolio.optimizer.application.history-loader.alignment
  (:require [clojure.set :as set]
            [hyperopen.portfolio.metrics.history :as metrics-history]
            [hyperopen.portfolio.optimizer.application.history-loader.instruments :as instruments]
            [hyperopen.portfolio.optimizer.application.history-loader.normalization :as normalization]))

(def default-min-observations
  2)

(def default-funding-periods-per-year
  1095)

(defn- row-by-time
  [rows]
  (into {}
        (map (juxt :time-ms identity))
        rows))

(defn- common-calendar
  [histories]
  (let [sets (map #(set (map :time-ms %)) histories)]
    (if (seq sets)
      (->> (apply set/intersection sets)
           sort
           vec)
      [])))

(defn- day-aligned-eligible
  [eligible]
  (mapv #(update % :history normalization/daily-price-history) eligible))

(defn- effective-history-alignment
  [eligible min-observations]
  (let [exact-calendar (common-calendar (map :history eligible))]
    (if (>= (count exact-calendar) min-observations)
      {:calendar exact-calendar
       :eligible eligible
       :observations (count exact-calendar)}
      (let [daily-eligible (day-aligned-eligible eligible)
            daily-calendar (common-calendar (map :history daily-eligible))
            daily-observations (count daily-calendar)]
        (if (>= daily-observations min-observations)
          {:calendar daily-calendar
           :eligible daily-eligible
           :observations daily-observations}
          {:calendar []
           :eligible eligible
           :observations (max (count exact-calendar) daily-observations)})))))

(defn- prices-for-calendar
  [history calendar]
  (let [by-time (row-by-time history)]
    (mapv (fn [time-ms]
            (get by-time time-ms))
          calendar)))

(defn- return-series
  [price-series]
  (->> (partition 2 1 price-series)
       (mapv (fn [[previous current]]
               (- (/ (:close current)
                     (:close previous))
                  1)))))

(defn- return-intervals
  [calendar]
  (mapv (fn [[start-ms end-ms]]
          (let [dt-ms (- end-ms start-ms)
                dt-days (/ dt-ms metrics-history/day-ms)]
            {:start-ms start-ms
             :end-ms end-ms
             :dt-days dt-days
             :dt-years (/ dt-days 365.2425)}))
        (partition 2 1 calendar)))

(defn- funding-summary
  [instrument funding-history-by-coin funding-periods-per-year]
  (if-not (instruments/perp-instrument? instrument)
    {:source :not-applicable}
    (let [coin (instruments/normalize-coin instrument)
          rows (normalization/normalize-funding-history (get funding-history-by-coin coin))
          average-rate (when (seq rows)
                         (/ (reduce + (map :funding-rate-raw rows))
                            (count rows)))]
      (if (number? average-rate)
        {:source :market-funding-history
         :rows rows
         :average-rate average-rate
         :annualized-carry (* average-rate funding-periods-per-year)}
        {:source :missing-market-funding-history
         :rows []
         :average-rate nil
         :annualized-carry 0}))))

(defn- freshness
  [calendar as-of-ms stale-after-ms]
  (let [latest-common-ms (last calendar)
        oldest-common-ms (first calendar)
        age-ms (when (and (number? as-of-ms)
                          (number? latest-common-ms))
                 (- as-of-ms latest-common-ms))
        stale? (if (number? latest-common-ms)
                 (and (number? stale-after-ms)
                      (number? age-ms)
                      (> age-ms stale-after-ms))
                 true)]
    {:as-of-ms as-of-ms
     :latest-common-ms latest-common-ms
     :oldest-common-ms oldest-common-ms
     :age-ms age-ms
     :stale? (boolean stale?)}))

(defn align-history-inputs
  [{:keys [universe
           candle-history-by-coin
           funding-history-by-coin
           vault-details-by-address
           as-of-ms
           stale-after-ms
           funding-periods-per-year
           min-observations]}]
  (let [min-observations* (or min-observations default-min-observations)
        funding-periods-per-year* (or funding-periods-per-year
                                      default-funding-periods-per-year)
        prepared (mapv (fn [instrument]
                         (let [coin (instruments/normalize-coin instrument)
                               instrument-id (instruments/normalize-instrument-id instrument)
                               vault? (instruments/vault-instrument? instrument)
                               vault-address* (instruments/vault-address instrument)
                               history (if vault?
                                         (normalization/normalize-vault-history
                                          (get vault-details-by-address vault-address*))
                                         (normalization/normalize-candle-history
                                          (get candle-history-by-coin coin)))]
                           (cond
                             (and vault? (not (seq vault-address*)))
                             {:instrument instrument
                              :instrument-id instrument-id
                              :excluded? true
                              :warning {:code :missing-vault-address
                                        :instrument-id instrument-id
                                        :market-type (instruments/market-type instrument)}}

                             (and (not vault?) (not (seq coin)))
                             {:instrument instrument
                              :instrument-id instrument-id
                              :excluded? true
                              :warning {:code :missing-history-coin
                                        :instrument-id instrument-id
                                        :market-type (instruments/market-type instrument)}}

                             (empty? history)
                             (if vault?
                               {:instrument instrument
                                :instrument-id instrument-id
                                :vault-address vault-address*
                                :excluded? true
                                :warning {:code :missing-vault-history
                                          :instrument-id instrument-id
                                          :vault-address vault-address*}}
                               {:instrument instrument
                                :instrument-id instrument-id
                                :coin coin
                                :excluded? true
                                :warning {:code :missing-candle-history
                                          :instrument-id instrument-id
                                          :coin coin}})

                             (< (count history) min-observations*)
                             (if vault?
                               {:instrument instrument
                                :instrument-id instrument-id
                                :vault-address vault-address*
                                :history history
                                :excluded? true
                                :warning {:code :insufficient-vault-history
                                          :instrument-id instrument-id
                                          :vault-address vault-address*
                                          :observations (count history)
                                          :required min-observations*}}
                               {:instrument instrument
                                :instrument-id instrument-id
                                :coin coin
                                :history history
                                :excluded? true
                                :warning {:code :insufficient-candle-history
                                          :instrument-id instrument-id
                                          :coin coin
                                          :observations (count history)
                                          :required min-observations*}})

                             :else
                             (cond-> {:instrument instrument
                                      :instrument-id instrument-id
                                      :history history
                                      :excluded? false}
                               vault? (assoc :vault-address vault-address*)
                               (not vault?) (assoc :coin coin)))))
                       (or universe []))
        eligible (filterv (complement :excluded?) prepared)
        alignment (effective-history-alignment eligible min-observations*)
        effective-calendar (:calendar alignment)
        effective-eligible (:eligible alignment)
        history-warning (when (and (seq eligible)
                                   (empty? effective-calendar))
                          {:code :insufficient-common-history
                           :observations (:observations alignment)
                           :required min-observations*})
        eligible-instruments (if (seq effective-calendar)
                               (mapv :instrument effective-eligible)
                               [])
        excluded-instruments (vec (concat (map :instrument (filter :excluded? prepared))
                                          (when (empty? effective-calendar)
                                            (map :instrument eligible))))
        warnings (vec (concat (keep :warning prepared)
                              (when history-warning [history-warning])))
        price-series-by-instrument (into {}
                                         (map (fn [{:keys [instrument-id history]}]
                                                [instrument-id
                                                 (prices-for-calendar history effective-calendar)]))
                                         effective-eligible)
        return-series-by-instrument (into {}
                                          (map (fn [[instrument-id prices]]
                                                 [instrument-id (return-series prices)]))
                                          price-series-by-instrument)
        funding-by-instrument (into {}
                                    (map (fn [instrument]
                                           [(instruments/normalize-instrument-id instrument)
                                            (funding-summary instrument
                                                             funding-history-by-coin
                                                             funding-periods-per-year*)]))
                                    (or universe []))]
    {:calendar effective-calendar
     :return-calendar (vec (rest effective-calendar))
     :eligible-instruments eligible-instruments
     :excluded-instruments excluded-instruments
     :price-series-by-instrument price-series-by-instrument
     :return-series-by-instrument return-series-by-instrument
     :return-intervals (return-intervals effective-calendar)
     :funding-by-instrument funding-by-instrument
     :warnings warnings
     :freshness (freshness effective-calendar as-of-ms stale-after-ms)}))
