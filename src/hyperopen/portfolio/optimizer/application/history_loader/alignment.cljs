(ns hyperopen.portfolio.optimizer.application.history-loader.alignment
  (:require [clojure.set :as set]
            [hyperopen.portfolio.metrics.history :as metrics-history]
            [hyperopen.portfolio.optimizer.application.history-loader.instruments :as instruments]
            [hyperopen.portfolio.optimizer.application.history-loader.normalization :as normalization]))

(def default-min-observations
  2)

(def default-funding-periods-per-year
  1095)

(def ^:private common-vault-window-preference
  [:one-year :six-month :three-month :month :week :day :all-time])

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

(defn- vault-entry?
  [entry]
  (contains? entry :vault-address))

(defn- candidates-for-window
  [entry window]
  (filterv (fn [candidate]
             (= window (:window candidate)))
           (:vault-history-candidates entry)))

(defn- window-candidate-depth
  [eligible window]
  (let [candidate-counts (map (fn [entry]
                                (count (candidates-for-window entry window)))
                              (filter vault-entry? eligible))]
    (when (and (seq candidate-counts)
               (every? pos? candidate-counts))
      (reduce max candidate-counts))))

(defn- ranked-window-candidate
  [candidates rank]
  (or (nth candidates rank nil)
      (last candidates)))

(defn- with-vault-window
  [eligible window candidate-rank]
  (reduce (fn [acc entry]
            (if (vault-entry? entry)
              (let [candidates (candidates-for-window entry window)]
                (if-let [candidate (ranked-window-candidate candidates candidate-rank)]
                  (conj acc
                        (assoc entry
                               :history (:history candidate)
                               :history-source (select-keys candidate
                                                            [:source :window])))
                  (reduced nil)))
              (conj acc entry)))
          []
          eligible))

(defn- successful-alignment?
  [alignment]
  (seq (:calendar alignment)))

(defn- source-alignment
  [alignment source]
  (assoc alignment
         :alignment-source (assoc source :observations (:observations alignment))))

(defn- common-vault-window-alignments
  [eligible min-observations]
  (when (some vault-entry? eligible)
    (mapcat (fn [window]
              (if-let [depth (window-candidate-depth eligible window)]
                (keep (fn [candidate-rank]
                        (when-let [window-eligible (with-vault-window
                                                     eligible
                                                     window
                                                     candidate-rank)]
                          (source-alignment
                           (effective-history-alignment window-eligible
                                                        min-observations)
                           {:kind :common-vault-window
                            :window window})))
                      (range depth))
                []))
            common-vault-window-preference)))

(defn- best-observations
  [alignments]
  (reduce max 0 (map #(or (:observations %) 0) alignments)))

(defn- resolve-history-alignment
  [eligible min-observations]
  (let [preferred (source-alignment
                   (effective-history-alignment eligible min-observations)
                   {:kind :preferred-history})
        fallbacks (vec (common-vault-window-alignments eligible min-observations))]
    (if (successful-alignment? preferred)
      preferred
      (or (some #(when (successful-alignment? %) %) fallbacks)
          (let [observations (best-observations (into [preferred] fallbacks))]
            (assoc preferred
                   :observations observations
                   :alignment-source {:kind :preferred-history
                                      :observations observations}))))))

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

(defn- expected-return-series-map
  [eligible]
  (into {}
        (keep (fn [{:keys [instrument-id expected-return-history]}]
                (let [history (vec expected-return-history)
                      series (return-series history)]
                  (when (seq series)
                    [instrument-id series]))))
        eligible))

(defn- expected-return-intervals-map
  [eligible]
  (into {}
        (keep (fn [{:keys [instrument-id expected-return-history]}]
                (let [calendar (mapv :time-ms expected-return-history)
                      intervals (return-intervals calendar)]
                  (when (seq intervals)
                    [instrument-id intervals]))))
        eligible))

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
                               vault-candidates (when vault?
                                                  (normalization/vault-history-candidates
                                                   (get vault-details-by-address vault-address*)))
                               history (if vault?
                                         (some-> vault-candidates first :history)
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
                               vault? (assoc :vault-history-candidates vault-candidates)
                               vault? (assoc :expected-return-history history)
                               vault? (assoc :history-source
                                             (select-keys (first vault-candidates)
                                                          [:source :window]))
                               (not vault?) (assoc :coin coin)))))
                       (or universe []))
        eligible (filterv (complement :excluded?) prepared)
        alignment (resolve-history-alignment eligible min-observations*)
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
        expected-return-series-by-instrument (expected-return-series-map effective-eligible)
        expected-return-intervals-by-instrument (expected-return-intervals-map
                                                 effective-eligible)
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
     :expected-return-series-by-instrument expected-return-series-by-instrument
     :expected-return-intervals-by-instrument expected-return-intervals-by-instrument
     :funding-by-instrument funding-by-instrument
     :warnings warnings
     :freshness (freshness effective-calendar as-of-ms stale-after-ms)
     :alignment-source (:alignment-source alignment)}))
