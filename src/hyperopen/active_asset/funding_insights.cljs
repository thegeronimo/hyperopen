(ns hyperopen.active-asset.funding-insights
  "Pure derivations behind the funding tooltip: who pays, cost as a share of
  margin, funding already paid, the price move that covers funding, the
  baseline/premium split and the 30-day chart model.

  Rate units follow the active-asset context: `funding-rate` is a percent per
  hour (0.00125 means 0.00125%/hr)."
  (:require [clojure.string :as str]
            [hyperopen.utils.parse :as parse-utils]))

(def baseline-hourly-rate-pct
  "Hyperliquid's fixed interest component for core perps: 0.01% per 8h."
  0.00125)

(def ^:private hours-per-year
  (* 24 365))

(def ^:private breakeven-days
  30)

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- parse-number
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (parse-utils/parse-localized-currency-decimal value)
              :else nil)]
    (when (finite-number? num)
      num)))

(defn payer-verdict
  "Who pays at `hourly-rate` for a position in `direction`: :pays, :receives,
  :neutral (zero rate) or nil (flat or no rate). Positive rates mean longs pay."
  [direction hourly-rate]
  (when (and (finite-number? hourly-rate)
             (contains? #{:long :short} direction))
    (cond
      (zero? hourly-rate) :neutral
      (= (pos? hourly-rate) (= direction :long)) :pays
      :else :receives)))

(defn margin-model
  "Leverage and margin behind a position. Live positions use the venue's own
  leverage and margin; hypothetical ones divide the value by `order-leverage`."
  [{:keys [live? position position-value order-leverage]}]
  (let [leverage (if live?
                   (parse-number (get-in position [:leverage :value]))
                   (parse-number order-leverage))
        leverage* (when (and (number? leverage) (pos? leverage)) leverage)
        margin-used (when live?
                      (parse-number (:marginUsed position)))
        margin (cond
                 (and (number? margin-used) (pos? margin-used)) margin-used
                 (and leverage* (number? position-value) (pos? position-value))
                 (/ position-value leverage*)
                 :else nil)]
    (when (number? margin)
      {:leverage leverage*
       :margin margin})))

(defn share-of-margin-pct
  "`payment` as an unsigned percent of `margin`."
  [payment margin]
  (when (and (finite-number? payment)
             (finite-number? margin)
             (pos? margin))
    (* 100 (/ (js/Math.abs payment) margin))))

(defn since-open-funding
  "Funding received since the position opened (negative = paid). Hyperliquid
  reports `cumFunding.sinceOpen` as a cost, so the sign is flipped."
  [position]
  (when-let [cost (or (parse-number (get-in position [:cumFunding :sinceOpen]))
                      (parse-number (get-in position [:cumFunding :since-open])))]
    (if (zero? cost) 0 (- cost))))

(defn breakeven
  "The price move over 30 days that funding at `hourly-rate` is worth. When the
  position pays, it is the move needed to cover funding; when it receives, it is
  the adverse move funding cushions."
  [direction hourly-rate mark]
  (let [verdict (payer-verdict direction hourly-rate)
        mark* (parse-number mark)]
    (when (and (contains? #{:pays :receives} verdict)
               (number? mark*)
               (pos? mark*))
      (let [pct (* (js/Math.abs hourly-rate) 24 breakeven-days)
            move (case [verdict direction]
                   [:pays :long] :rise
                   [:pays :short] :fall
                   [:receives :long] :fall
                   [:receives :short] :rise)
            target (* mark* (if (= move :rise)
                              (+ 1 (/ pct 100))
                              (- 1 (/ pct 100))))]
        {:kind (if (= verdict :pays) :cover :cushion)
         :move move
         :days breakeven-days
         :pct pct
         :mark mark*
         :target-price target}))))

(defn base-dex-coin?
  [coin]
  (and (string? coin)
       (seq coin)
       (not (str/includes? coin ":"))))

(defn rate-breakdown
  "Split an hourly rate into the fixed interest baseline and the premium, as
  APYs. Only core perps use the default baseline, so named-DEX coins return nil."
  [coin hourly-rate]
  (when (and (base-dex-coin? coin)
             (finite-number? hourly-rate))
    (let [premium (- hourly-rate baseline-hourly-rate-pct)]
      {:total-apy (* hourly-rate hours-per-year)
       :baseline-apy (* baseline-hourly-rate-pct hours-per-year)
       :premium-apy (* (if (< (js/Math.abs premium) 1e-12) 0 premium)
                       hours-per-year)})))

(defn- daily-rate->apy
  [daily-rate]
  (when (finite-number? daily-rate)
    (* daily-rate 365 100)))

(defn- sample-stddev
  [values]
  (let [n (count values)]
    (when (> n 1)
      (let [mean (/ (reduce + values) n)
            squares (reduce + (map (fn [v] (let [d (- v mean)] (* d d))) values))]
        (js/Math.sqrt (/ squares (dec n)))))))

(defn history-chart-model
  "Chart-ready daily APYs, oldest first, plus the stats shown under the chart."
  [daily-series]
  (let [points (->> (or daily-series [])
                    (filter map?)
                    (sort-by (fn [{:keys [day-index day-start-ms]}]
                               (or day-start-ms day-index 0)))
                    (mapv (fn [{:keys [day-index day-start-ms daily-rate]}]
                            (let [apy (daily-rate->apy (parse-number daily-rate))]
                              {:day-index day-index
                               :day-start-ms day-start-ms
                               :apy apy
                               :undefined? (nil? apy)}))))
        values (vec (keep :apy points))]
    (when (seq points)
      {:points points
       :day-count (count points)
       :days-with-data (count values)
       :mean-apy (when (seq values) (/ (reduce + values) (count values)))
       :min-apy (when (seq values) (apply min values))
       :max-apy (when (seq values) (apply max values))
       :sd-apy (sample-stddev values)
       :positive-days (count (filter pos? values))})))
