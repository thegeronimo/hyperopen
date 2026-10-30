(ns hyperopen.vol-range.domain.volatility
  "Daily Parkinson volatility for Vol Range (HO-SPEC-014 v2.1 sections 5.1-5.3).

  Pure domain code. The estimate uses exactly the completed UTC daily candles
  before the current UTC day; a missing, invalid or conflicting day makes the
  estimate unavailable and no older day is ever substituted. Volatility never
  depends on the anchor. Floats appear only in the estimator's log and sqrt."
  (:require [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.contracts :as contracts]))

(def day-ms 86400000)

(defn utc-day-start-ms
  "Floor `ms` to the start of its UTC day."
  [ms]
  (* day-ms (js/Math.floor (/ ms day-ms))))

(defn completed-window
  "The `lookback-days` completed UTC days strictly before the current UTC day."
  [now-ms lookback-days]
  (let [current (utc-day-start-ms now-ms)
        first-day (- current (* lookback-days day-ms))
        days (vec (map #(+ first-day (* % day-ms)) (range lookback-days)))]
    {:days days
     :first-day-ms first-day
     :last-day-ms (- current day-ms)
     :current-day-ms current}))

;; ---------------------------------------------------------------------------
;; Candle normalization
;; ---------------------------------------------------------------------------

(defn- field [row k]
  (let [v (get row k)]
    (if (nil? v) (get row (name k)) v)))

(defn- price->dec [x]
  (cond
    (string? x) (precision/parse-decimal x)
    (number? x) (precision/number->decimal x)
    :else nil))

(defn- day-boundary? [ms]
  (and (number? ms) (js/Number.isInteger ms) (zero? (mod ms day-ms))))

(defn normalize-candle
  "Normalize one exchange candle row. Adds :invalid? true when unusable."
  [row]
  (let [open-ms (field row :t)
        interval (field row :i)
        open (price->dec (field row :o))
        high (price->dec (field row :h))
        low (price->dec (field row :l))
        close (price->dec (field row :c))
        invalid? (or (nil? high) (nil? low)
                     (not (precision/pos-dec? high))
                     (not (precision/pos-dec? low))
                     (pos? (precision/cmp low high))
                     (not (day-boundary? open-ms))
                     (and (some? interval) (not= "1d" interval)))]
    (cond-> {:open-ms (when (number? open-ms) open-ms)
             :close-ms (let [c (field row :T)] (when (number? c) c))
             :coin (field row :s)
             :interval interval
             :open open
             :high high
             :low low
             :close close}
      invalid? (assoc :invalid? true))))

(defn- canon [candle]
  (let [s (fn [d] (when d (precision/decimal->string d)))]
    [(:open-ms candle) (:close-ms candle) (:coin candle) (:interval candle)
     (s (:open candle)) (s (:high candle)) (s (:low candle)) (s (:close candle))
     (boolean (:invalid? candle))]))

(defn- rows-seq [rows]
  (cond
    (map? rows) (seq (:rows rows))
    (sequential? rows) (seq rows)
    :else nil))

(defn- normalized-for-coin [rows coin]
  (->> (rows-seq rows)
       (filter map?)
       (remove (fn [row] (let [s (field row :s)] (and (some? s) (not= s coin)))))
       (map normalize-candle)))

(defn select-window
  "Pick exactly the required completed days from `rows` (vector, or the map
  stored at [:candles coin :1d] with :rows)."
  [rows {:keys [now-ms coin lookback-days]}]
  (let [{:keys [days first-day-ms last-day-ms]} (completed-window now-ms lookback-days)
        by-day (group-by :open-ms (normalized-for-coin rows coin))]
    (loop [remaining days picked []]
      (if-let [d (first remaining)]
        (let [candidates (get by-day d)
              distinct-rows (vec (vals (into {} (map (juxt canon identity)) candidates)))]
          (cond
            (empty? distinct-rows) {:status :unavailable :reason :missing-day :day-ms d}
            (> (count distinct-rows) 1) {:status :unavailable :reason :conflicting-duplicate :day-ms d}
            (:invalid? (first distinct-rows)) {:status :unavailable :reason :invalid-day :day-ms d}
            :else (recur (rest remaining) (conj picked (first distinct-rows)))))
        {:status :ok
         :days picked
         :window {:first-day-ms first-day-ms :last-day-ms last-day-ms}}))))

;; ---------------------------------------------------------------------------
;; Estimators
;; ---------------------------------------------------------------------------

(defn- dec->number [d] (js/Number (precision/decimal->string d)))

(defn parkinson-daily
  "Daily Parkinson volatility from exactly the policy lookback of candles.
  Not annualized. Canonical fraction is 12 places, half-up."
  [days]
  (let [n (get-in contracts/product-policy [:lookback-days])]
    (when (= n (count days))
      (let [sum-sq (reduce (fn [acc {:keys [high low]}]
                             (let [ln (js/Math.log (/ (dec->number high) (dec->number low)))]
                               (+ acc (* ln ln))))
                           0 days)
            v-raw (js/Math.sqrt (/ sum-sq (* n 4 (js/Math.log 2))))
            canonical (precision/to-fixed
                       (precision/quantize (precision/number->decimal v-raw) 12 :half-up) 12)]
        {:raw v-raw
         :daily-fraction canonical
         :zero? (precision/zero-dec? (precision/parse-decimal canonical))
         :estimator-version contracts/estimator-version}))))

(defn manual-daily-fraction
  "Convert a manual daily percent string to a 12-place fraction string. This is
  the only percent->fraction conversion for volatility."
  [pct-string]
  (if-let [pct (precision/parse-decimal pct-string)]
    (let [{:keys [min max]} (:manual-daily-pct contracts/product-policy)]
      (if (or (neg? (precision/cmp pct (precision/parse-decimal min)))
              (pos? (precision/cmp pct (precision/parse-decimal max))))
        {:error :input/manual-vol-out-of-range}
        {:daily-fraction (precision/to-fixed
                          (precision/divide pct (precision/parse-decimal "100") 12 :half-up) 12)}))
    {:error :input/invalid-number}))

(defn resolve-volatility
  "Resolve volatility from :manual percent or :auto candles. Takes no anchor."
  [{:keys [mode manual-daily-pct rows now-ms coin]}]
  (if (= mode :manual)
    (let [{:keys [daily-fraction error]} (manual-daily-fraction manual-daily-pct)]
      (if error
        {:status :invalid :error error}
        {:status :ok :source :manual :daily-fraction daily-fraction}))
    (let [selected (select-window rows {:now-ms now-ms :coin coin
                                        :lookback-days (:lookback-days contracts/product-policy)})]
      (if (= :ok (:status selected))
        (let [est (parkinson-daily (:days selected))]
          {:status :ok
           :source :auto
           :daily-fraction (:daily-fraction est)
           :zero? (:zero? est)
           :window (:window selected)
           :estimator-version (:estimator-version est)})
        {:status :unavailable
         :reason (:reason selected)
         :day-ms (:day-ms selected)
         :message "14 completed daily candles required"}))))

(defn utc-open-anchor
  "Open of the current UTC day's candle, or nil. Its partial high/low is never
  used by volatility."
  [rows now-ms coin]
  (let [today (utc-day-start-ms now-ms)]
    (when-let [c (->> (normalized-for-coin rows coin)
                      (filter #(and (= today (:open-ms %))
                                    (:open %)
                                    (precision/pos-dec? (:open %))))
                      first)]
      {:price (precision/decimal->string (:open c))
       :observed-at-ms today})))
