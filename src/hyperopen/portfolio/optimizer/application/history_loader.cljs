(ns hyperopen.portfolio.optimizer.application.history-loader
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hyperopen.portfolio.metrics.history :as metrics-history]))

(def default-interval
  :1d)

(def default-bars
  365)

(def default-priority
  :high)

(def default-min-observations
  2)

(def default-funding-periods-per-year
  1095)

(def ^:private vault-instrument-prefix
  "vault:")

(def ^:private vault-summary-preference
  [:all-time :one-year :six-month :three-month :month :week :day])

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- parse-number
  [value]
  (cond
    (finite-number? value)
    value

    (string? value)
    (let [text (str/trim value)
          parsed (js/parseFloat text)]
      (when (and (seq text)
                 (finite-number? parsed))
        parsed))

    :else
    nil))

(defn- parse-ms
  [value]
  (when-let [parsed (parse-number value)]
    (js/Math.floor parsed)))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- normalize-coin
  [instrument]
  (non-blank-text (or (:coin instrument)
                      (:asset instrument))))

(defn- normalize-instrument-id
  [instrument]
  (or (non-blank-text (:instrument-id instrument))
      (normalize-coin instrument)))

(defn- normalize-vault-address
  [value]
  (some-> value str str/trim str/lower-case not-empty))

(defn- vault-address-from-value
  [value]
  (let [text (some-> value str str/trim)
        lower (some-> text str/lower-case)]
    (when (and (seq lower)
               (str/starts-with? lower vault-instrument-prefix))
      (normalize-vault-address (subs text (count vault-instrument-prefix))))))

(defn- market-type
  [instrument]
  (let [value (:market-type instrument)]
    (cond
      (keyword? value) value
      (string? value) (keyword (str/lower-case (str/trim value)))
      :else nil)))

(defn- perp-instrument?
  [instrument]
  (= :perp (market-type instrument)))

(defn- vault-address
  [instrument]
  (or (normalize-vault-address (:vault-address instrument))
      (vault-address-from-value (:instrument-id instrument))
      (vault-address-from-value (:coin instrument))))

(defn- vault-instrument?
  [instrument]
  (boolean
   (or (= :vault (market-type instrument))
       (vault-address-from-value (:instrument-id instrument))
       (vault-address-from-value (:coin instrument)))))

(defn- instrument-warning-context
  [instrument]
  (cond-> {:instrument-id (normalize-instrument-id instrument)}
    (market-type instrument) (assoc :market-type (market-type instrument))))

(defn- group-instruments-by-coin
  [universe]
  (reduce (fn [acc instrument]
            (if-let [coin (normalize-coin instrument)]
              (update-in acc [:by-coin coin] (fnil conj []) instrument)
              (update acc :warnings conj
                      (assoc (instrument-warning-context instrument)
                             :code :missing-history-coin))))
          {:by-coin {}
           :warnings []}
          (or universe [])))

(defn- sorted-coin-groups
  [universe predicate]
  (let [{:keys [by-coin warnings]} (group-instruments-by-coin universe)
        ordered-coins (->> universe
                           (filter predicate)
                           (keep normalize-coin)
                           distinct
                           vec)]
    {:groups (mapv (fn [coin]
                     [coin (vec (get by-coin coin))])
                   ordered-coins)
     :warnings warnings}))

(defn- group-vault-instruments-by-address
  [universe]
  (reduce (fn [{:keys [by-address ordered-addresses] :as acc} instrument]
            (if-not (vault-instrument? instrument)
              acc
              (if-let [address (vault-address instrument)]
                (cond-> (update-in acc [:by-address address] (fnil conj []) instrument)
                  (not (contains? by-address address))
                  (update :ordered-addresses conj address))
                (update acc :warnings conj
                        (assoc (instrument-warning-context instrument)
                               :code :missing-vault-address)))))
          {:by-address {}
           :ordered-addresses []
           :warnings []}
          (or universe [])))

(defn- sorted-vault-groups
  [universe]
  (let [{:keys [by-address ordered-addresses warnings]}
        (group-vault-instruments-by-address universe)]
    {:groups (mapv (fn [address]
                     [address (vec (get by-address address))])
                   ordered-addresses)
     :warnings warnings}))

(defn build-history-request-plan
  [universe opts]
  (let [opts* (or opts {})
        interval (or (:interval opts*) default-interval)
        bars (or (:bars opts*) default-bars)
        priority (or (:priority opts*) default-priority)
        now-ms (:now-ms opts*)
        funding-window-ms (:funding-window-ms opts*)
        funding-start-ms (or (:funding-start-ms opts*)
                             (when (and (number? now-ms)
                                        (number? funding-window-ms))
                               (- now-ms funding-window-ms)))
        funding-end-ms (or (:funding-end-ms opts*)
                           now-ms)
        market-universe (vec (remove vault-instrument? (or universe [])))
        all-groups (sorted-coin-groups market-universe (constantly true))
        perp-groups (sorted-coin-groups market-universe perp-instrument?)
        vault-groups (sorted-vault-groups universe)
        candle-request (fn [[coin instruments]]
                         {:coin coin
                          :instrument-ids (mapv normalize-instrument-id instruments)
                          :opts {:interval interval
                                 :bars bars
                                 :priority priority
                                 :cache-key [:portfolio-optimizer :candles coin interval bars]
                                 :dedupe-key [:portfolio-optimizer :candles coin interval bars]}})
        funding-request (fn [[coin instruments]]
                          {:coin coin
                           :instrument-ids (mapv normalize-instrument-id instruments)
                           :opts {:priority priority
                                  :start-time-ms funding-start-ms
                                  :end-time-ms funding-end-ms
                                  :cache-key [:portfolio-optimizer :funding coin funding-start-ms funding-end-ms]
                                  :dedupe-key [:portfolio-optimizer :funding coin funding-start-ms funding-end-ms]}})
        vault-detail-request (fn [[address instruments]]
                               {:vault-address address
                                :instrument-ids (mapv normalize-instrument-id instruments)
                                :opts {:priority priority
                                       :cache-key [:portfolio-optimizer :vault-details address]
                                       :dedupe-key [:portfolio-optimizer :vault-details address]}})]
    {:candle-requests (mapv candle-request (:groups all-groups))
     :funding-requests (mapv funding-request (:groups perp-groups))
     :vault-detail-requests (mapv vault-detail-request (:groups vault-groups))
     :warnings (vec (concat (:warnings all-groups)
                            (:warnings vault-groups)))}))

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

(defn- normalize-candle-history
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
  (let [portfolio (or (:portfolio details) {})]
    (or (some (fn [summary-key]
                (when-let [summary (get portfolio summary-key)]
                  summary))
              vault-summary-preference)
        (some (fn [[_key summary]]
                (when (map? summary)
                  summary))
              portfolio))))

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

(defn- normalize-vault-history
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

(defn- normalize-funding-history
  [rows]
  (->> rows
       (keep normalize-funding-row)
       (reduce (fn [acc row]
                 (assoc acc (:time-ms row) row))
               {})
       vals
       (sort-by :time-ms)
       vec))

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

(defn- funding-summary
  [instrument funding-history-by-coin funding-periods-per-year]
  (if-not (perp-instrument? instrument)
    {:source :not-applicable}
    (let [coin (normalize-coin instrument)
          rows (normalize-funding-history (get funding-history-by-coin coin))
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
                         (let [coin (normalize-coin instrument)
                               instrument-id (normalize-instrument-id instrument)
                               vault? (vault-instrument? instrument)
                               vault-address* (vault-address instrument)
                               history (if vault?
                                         (normalize-vault-history
                                          (get vault-details-by-address vault-address*))
                                         (normalize-candle-history
                                          (get candle-history-by-coin coin)))]
                           (cond
                             (and vault? (not (seq vault-address*)))
                             {:instrument instrument
                              :instrument-id instrument-id
                              :excluded? true
                              :warning {:code :missing-vault-address
                                        :instrument-id instrument-id
                                        :market-type (market-type instrument)}}

                             (and (not vault?) (not (seq coin)))
                             {:instrument instrument
                              :instrument-id instrument-id
                              :excluded? true
                              :warning {:code :missing-history-coin
                                        :instrument-id instrument-id
                                        :market-type (market-type instrument)}}

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
        calendar (common-calendar (map :history eligible))
        effective-calendar (if (>= (count calendar) min-observations*)
                             calendar
                             [])
        history-warning (when (and (seq eligible)
                                   (empty? effective-calendar))
                          {:code :insufficient-common-history
                           :observations (count calendar)
                           :required min-observations*})
        eligible-instruments (if (seq effective-calendar)
                               (mapv :instrument eligible)
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
                                         eligible)
        return-series-by-instrument (into {}
                                          (map (fn [[instrument-id prices]]
                                                 [instrument-id (return-series prices)]))
                                          price-series-by-instrument)
        funding-by-instrument (into {}
                                    (map (fn [instrument]
                                           [(normalize-instrument-id instrument)
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
     :funding-by-instrument funding-by-instrument
     :warnings warnings
     :freshness (freshness effective-calendar as-of-ms stale-after-ms)}))
