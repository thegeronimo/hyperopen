(ns hyperopen.domain.trading.indicators.oscillators
  (:require [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private oscillator-indicator-definitions
  [{:id :accelerator-oscillator
    :name "Accelerator Oscillator"
    :short-name "AC"
    :description "Awesome Oscillator minus its 5-period simple moving average"
    :supports-period? false
    :default-config {}}
   {:id :advance-decline
    :name "Advance/Decline"
    :short-name "A/D (Bars)"
    :description "Single-instrument proxy using cumulative up/down bar count"
    :supports-period? false
    :default-config {}}
   {:id :awesome-oscillator
    :name "Awesome Oscillator"
    :short-name "AO"
    :description "5-period SMA minus 34-period SMA of median price"
    :supports-period? false
    :default-config {}}
   {:id :balance-of-power
    :name "Balance of Power"
    :short-name "BOP"
    :description "(Close - Open) / (High - Low)"
    :supports-period? false
    :default-config {}}])

(defn get-oscillator-indicators
  []
  oscillator-indicator-definitions)

(def ^:private finite-number? imath/finite-number?)
(def ^:private field-values imath/field-values)
(def ^:private mean imath/mean)

(defn- sma-values
  [values period]
  (imath/sma-values values period :lagged))

(defn- rolling-apply
  [values period f]
  (imath/rolling-apply values period f :lagged))

(defn- calculate-awesome-oscillator
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

(defn- calculate-accelerator-oscillator
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

(defn- calculate-balance-of-power
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

(defn- calculate-advance-decline
  [data _params]
  (let [closes (field-values data :close)
        size (count data)
        values (loop [idx 0
                      running 0
                      out []]
                 (if (= idx size)
                   out
                   (if (zero? idx)
                     (recur (inc idx) 0 (conj out 0))
                     (let [change (cond
                                    (> (nth closes idx) (nth closes (dec idx))) 1
                                    (< (nth closes idx) (nth closes (dec idx))) -1
                                    :else 0)
                           next-value (+ running change)]
                       (recur (inc idx) next-value (conj out next-value))))))]
    (result/indicator-result :advance-decline
                             :separate
                             [(result/line-series :ad-bars values)])))

(def ^:private oscillator-calculators
  {:accelerator-oscillator calculate-accelerator-oscillator
   :advance-decline calculate-advance-decline
   :awesome-oscillator calculate-awesome-oscillator
   :balance-of-power calculate-balance-of-power})

(defn calculate-oscillator-indicator
  [indicator-type data params]
  (let [config (or params {})
        calculator (get oscillator-calculators indicator-type)]
    (when calculator
      (calculator data config))))
