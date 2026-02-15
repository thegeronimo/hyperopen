(ns hyperopen.domain.trading.indicators.oscillators.classic
  (:require [hyperopen.domain.trading.indicators.math-adapter :as math-adapter]
            [hyperopen.domain.trading.indicators.oscillators.helpers :as helpers]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private finite-number? helpers/finite-number?)
(def ^:private parse-period helpers/parse-period)
(def ^:private field-values helpers/field-values)
(def ^:private mean helpers/mean)
(def ^:private normalize-values helpers/normalize-values)
(def ^:private sma-values helpers/sma-values)
(def ^:private rolling-apply helpers/rolling-apply-lagged)
(def ^:private sma-aligned-values helpers/sma-aligned-values)
(def ^:private rolling-sum-aligned helpers/rolling-sum-aligned)
(def ^:private rolling-max-aligned helpers/rolling-max-aligned)
(def ^:private rolling-min-aligned helpers/rolling-min-aligned)
(def ^:private rsi-values helpers/rsi-values)

(defn calculate-awesome-oscillator
  [data _params]
  (let [highs (field-values data :high)
        lows (field-values data :low)
        size (count data)
        median-values (mapv (fn [idx]
                              (/ (+ (nth highs idx) (nth lows idx)) 2))
                            (range size))
        fast-values (sma-values median-values 5)
        slow-values (sma-values median-values 34)
        values (mapv (fn [idx]
                       (let [fast (nth fast-values idx)
                             slow (nth slow-values idx)]
                         (when (and (finite-number? fast)
                                    (finite-number? slow))
                           (- fast slow))))
                     (range size))]
    (result/indicator-result :awesome-oscillator
                             :separate
                             [(result/histogram-series :ao values)])))

(defn calculate-accelerator-oscillator
  [data _params]
  (let [highs (field-values data :high)
        lows (field-values data :low)
        size (count data)
        median-values (mapv (fn [idx]
                              (/ (+ (nth highs idx) (nth lows idx)) 2))
                            (range size))
        fast-values (sma-values median-values 5)
        slow-values (sma-values median-values 34)
        ao-values (mapv (fn [idx]
                          (let [fast (nth fast-values idx)
                                slow (nth slow-values idx)]
                            (when (and (finite-number? fast)
                                       (finite-number? slow))
                              (- fast slow))))
                        (range size))
        ao-signal (rolling-apply ao-values 5 mean)
        values (mapv (fn [idx]
                       (let [ao (nth ao-values idx)
                             signal (nth ao-signal idx)]
                         (when (and (finite-number? ao)
                                    (finite-number? signal))
                           (- ao signal))))
                     (range size))]
    (result/indicator-result :accelerator-oscillator
                             :separate
                             [(result/histogram-series :ac values)])))

(defn calculate-balance-of-power
  [data _params]
  (let [opens (field-values data :open)
        highs (field-values data :high)
        lows (field-values data :low)
        closes (field-values data :close)
        size (count data)
        values (mapv (fn [idx]
                       (let [high (nth highs idx)
                             low (nth lows idx)
                             open (nth opens idx)
                             close (nth closes idx)
                             denominator (- high low)]
                         (if (zero? denominator)
                           0
                           (/ (- close open) denominator))))
                     (range size))]
    (result/indicator-result :balance-of-power
                             :separate
                             [(result/line-series :bop values)])))

(defn calculate-stochastic
  [data params]
  (let [k-period (parse-period (:kPeriod params) 14 1 200)
        d-period (parse-period (:dPeriod params) 3 1 200)
        result (math-adapter/stochastic (field-values data :high)
                                        (field-values data :low)
                                        (field-values data :close)
                                        {:k-period k-period :d-period d-period})
        k-values (normalize-values (:k result))
        d-values (normalize-values (:d result))]
    (result/indicator-result :stochastic
                             :separate
                             [(result/line-series :k k-values)
                              (result/line-series :d d-values)])))

(defn calculate-stochastic-rsi
  [data params]
  (let [rsi-period (parse-period (:rsiPeriod params) 14 2 200)
        stoch-period (parse-period (:stochPeriod params) 14 2 200)
        k-smoothing (parse-period (:kSmoothing params) 3 1 50)
        d-smoothing (parse-period (:dSmoothing params) 3 1 50)
        rsi-series (normalize-values
                    (math-adapter/relative-strength-index (field-values data :close)
                                                          {:period rsi-period}))
        min-rsi (rolling-min-aligned rsi-series stoch-period)
        max-rsi (rolling-max-aligned rsi-series stoch-period)
        raw-k (mapv (fn [idx]
                      (let [r (nth rsi-series idx)
                            mn (nth min-rsi idx)
                            mx (nth max-rsi idx)
                            range-value (- (or mx 0) (or mn 0))]
                        (when (and (finite-number? r)
                                   (finite-number? mn)
                                   (finite-number? mx)
                                   (pos? range-value))
                          (* 100 (/ (- r mn) range-value)))))
                    (range (count rsi-series)))
        k-values (sma-aligned-values raw-k k-smoothing)
        d-values (sma-aligned-values k-values d-smoothing)]
    (result/indicator-result :stochastic-rsi
                             :separate
                             [(result/line-series :k k-values)
                              (result/line-series :d d-values)])))

(defn calculate-commodity-channel-index
  [data params]
  (let [period (parse-period (:period params) 20 2 200)
        values (normalize-values
                (math-adapter/commodity-channel-index (field-values data :high)
                                                      (field-values data :low)
                                                      (field-values data :close)
                                                      {:period period}))]
    (result/indicator-result :commodity-channel-index
                             :separate
                             [(result/line-series :cci values)])))

(defn calculate-macd
  [data params]
  (let [fast (parse-period (:fast params) 12 1 200)
        slow (parse-period (:slow params) 26 2 400)
        signal (parse-period (:signal params) 9 1 200)
        result (math-adapter/macd (field-values data :close)
                                  {:fast fast
                                   :slow slow
                                   :signal signal})
        macd-line (normalize-values (:macdLine result))
        signal-line (normalize-values (:signalLine result))
        histogram (mapv (fn [m s]
                          (when (and (finite-number? m)
                                     (finite-number? s))
                            (- m s)))
                        macd-line signal-line)]
    (result/indicator-result :macd
                             :separate
                             [(result/histogram-series :hist histogram)
                              (result/line-series :macd macd-line)
                              (result/line-series :signal signal-line)])))

(defn calculate-mass-index
  [data params]
  (let [ema-period (parse-period (:emaPeriod params) 9 1 200)
        mi-period (parse-period (:miPeriod params) 25 2 400)
        values (normalize-values
                (math-adapter/mass-index (field-values data :high)
                                         (field-values data :low)
                                         {:ema-period ema-period
                                          :mi-period mi-period}))]
    (result/indicator-result :mass-index
                             :separate
                             [(result/line-series :mi values)])))

(defn calculate-relative-strength-index
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        values (rsi-values (field-values data :close) period)]
    (result/indicator-result :relative-strength-index
                             :separate
                             [(result/line-series :rsi values)])))

(defn calculate-ultimate-oscillator
  [data params]
  (let [short-period (parse-period (:short params) 7 2 100)
        medium-period (parse-period (:medium params) 14 2 200)
        long-period (parse-period (:long params) 28 2 400)
        highs-v (field-values data :high)
        lows-v (field-values data :low)
        closes-v (field-values data :close)
        size (count data)
        bp (mapv (fn [idx]
                   (let [close (nth closes-v idx)
                         low (nth lows-v idx)
                         prev-close (if (pos? idx)
                                      (nth closes-v (dec idx))
                                      close)]
                     (- close (min low prev-close))))
                 (range size))
        tr (mapv (fn [idx]
                   (let [high (nth highs-v idx)
                         low (nth lows-v idx)
                         prev-close (if (pos? idx)
                                      (nth closes-v (dec idx))
                                      (nth closes-v idx))]
                     (- (max high prev-close)
                        (min low prev-close))))
                 (range size))
        bp-short (rolling-sum-aligned bp short-period)
        tr-short (rolling-sum-aligned tr short-period)
        bp-medium (rolling-sum-aligned bp medium-period)
        tr-medium (rolling-sum-aligned tr medium-period)
        bp-long (rolling-sum-aligned bp long-period)
        tr-long (rolling-sum-aligned tr long-period)
        values (mapv (fn [bs ts bm tm bl tl]
                       (when (every? finite-number? [bs ts bm tm bl tl])
                         (let [a (if (zero? ts) nil (/ bs ts))
                               b (if (zero? tm) nil (/ bm tm))
                               c (if (zero? tl) nil (/ bl tl))]
                           (when (every? finite-number? [a b c])
                             (* 100 (/ (+ (* 4 a) (* 2 b) c)
                                       7))))))
                     bp-short tr-short bp-medium tr-medium bp-long tr-long)]
    (result/indicator-result :ultimate-oscillator
                             :separate
                             [(result/line-series :uo values)])))
