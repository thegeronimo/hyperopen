(ns hyperopen.domain.trading.indicators.volatility.range
  (:require [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private seconds-per-week (* 7 24 60 60))

(def ^:private finite-number? imath/finite-number?)
(def ^:private parse-period imath/parse-period)
(def ^:private field-values imath/field-values)

(defn- rma-values
  [values period]
  (imath/rma-values values period :lagged))

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

(defn calculate-52-week-high-low
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

(defn calculate-atr
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

(defn calculate-volatility-index
  [data params]
  (let [period (parse-period (:period params) 14 2 400)
        atr (rma-values (true-range-values (field-values data :high)
                                           (field-values data :low)
                                           (field-values data :close))
                        period)
        close-values (field-values data :close)
        values (mapv (fn [a c]
                       (when (and (finite-number? a)
                                  (finite-number? c)
                                  (not= c 0))
                         (* 100 (/ a c))))
                     atr close-values)]
    (result/indicator-result :volatility-index
                             :separate
                             [(result/line-series :vol-index values)])))
