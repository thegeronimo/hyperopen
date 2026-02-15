(ns hyperopen.domain.trading.indicators.volatility
  (:require [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private seconds-per-week (* 7 24 60 60))

(def ^:private volatility-indicator-definitions
  [{:id :week-52-high-low
    :name "52 Week High/Low"
    :short-name "52W H/L"
    :description "Rolling 52-week high and low levels"
    :supports-period? true
    :default-period 52
    :min-period 1
    :max-period 260
    :default-config {:period 52}}
   {:id :atr
    :name "Average True Range"
    :short-name "ATR"
    :description "Wilder average true range"
    :supports-period? true
    :default-period 14
    :min-period 2
    :max-period 200
    :default-config {:period 14}}
   {:id :bollinger-bands
    :name "Bollinger Bands"
    :short-name "BOLL"
    :description "Upper, basis, and lower volatility bands"
    :supports-period? true
    :default-period 20
    :min-period 2
    :max-period 200
    :default-config {:period 20
                     :multiplier 2}}])

(defn get-volatility-indicators
  []
  volatility-indicator-definitions)

(def ^:private finite-number? imath/finite-number?)
(def ^:private parse-period imath/parse-period)
(def ^:private field-values imath/field-values)

(defn- sma-values
  [values period]
  (imath/sma-values values period :lagged))

(defn- stddev-values
  [values period]
  (imath/stddev-values values period :lagged))

(defn- rma-values
  [values period]
  (imath/rma-values values period :lagged))

(defn- calculate-52-week-high-low
  [data params]
  (let [weeks (parse-period (:period params) 52 1 260)
        lookback-seconds (* weeks seconds-per-week)
        time-values (imath/times data)
        highs (field-values data :high)
        lows (field-values data :low)
        size (count data)
        [high-line low-line]
        (loop [idx 0
               start-idx 0
               high-result []
               low-result []]
          (if (= idx size)
            [high-result low-result]
            (let [cutoff (- (nth time-values idx) lookback-seconds)
                  next-start (loop [cursor start-idx]
                               (if (and (< cursor idx)
                                        (< (nth time-values cursor) cutoff))
                                 (recur (inc cursor))
                                 cursor))
                  high-window (subvec highs next-start (inc idx))
                  low-window (subvec lows next-start (inc idx))]
              (recur (inc idx)
                     next-start
                     (conj high-result (apply max high-window))
                     (conj low-result (apply min low-window))))))]
    (result/indicator-result :week-52-high-low
                             :overlay
                             [(result/line-series :high high-line)
                              (result/line-series :low low-line)])))

(defn- true-range-values
  [highs lows closes]
  (let [size (count highs)]
    (mapv (fn [idx]
            (let [high (nth highs idx)
                  low (nth lows idx)
                  prev-close (if (zero? idx) (nth closes idx) (nth closes (dec idx)))]
              (max (- high low)
                   (js/Math.abs (- high prev-close))
                   (js/Math.abs (- low prev-close)))))
          (range size))))

(defn- calculate-atr
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        highs (field-values data :high)
        lows (field-values data :low)
        closes (field-values data :close)
        tr-values (true-range-values highs lows closes)
        values (rma-values tr-values period)]
    (result/indicator-result :atr
                             :separate
                             [(result/line-series :atr values)])))

(defn- calculate-bollinger-bands
  [data params]
  (let [period (parse-period (:period params) 20 2 200)
        multiplier (or (:multiplier params) 2)
        closes (field-values data :close)
        basis-values (sma-values closes period)
        std-values (stddev-values closes period)
        upper-values (mapv (fn [idx]
                             (let [basis (nth basis-values idx)
                                   stdev (nth std-values idx)]
                               (when (and (finite-number? basis)
                                          (finite-number? stdev))
                                 (+ basis (* multiplier stdev)))))
                           (range (count closes)))
        lower-values (mapv (fn [idx]
                             (let [basis (nth basis-values idx)
                                   stdev (nth std-values idx)]
                               (when (and (finite-number? basis)
                                          (finite-number? stdev))
                                 (- basis (* multiplier stdev)))))
                           (range (count closes)))]
    (result/indicator-result :bollinger-bands
                             :overlay
                             [(result/line-series :upper upper-values)
                              (result/line-series :basis basis-values)
                              (result/line-series :lower lower-values)])))

(def ^:private volatility-calculators
  {:week-52-high-low calculate-52-week-high-low
   :atr calculate-atr
   :bollinger-bands calculate-bollinger-bands})

(defn calculate-volatility-indicator
  [indicator-type data params]
  (let [config (or params {})
        calculator (get volatility-calculators indicator-type)]
    (when calculator
      (calculator data config))))
