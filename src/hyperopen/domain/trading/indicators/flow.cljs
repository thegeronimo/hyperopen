(ns hyperopen.domain.trading.indicators.flow
  (:require [hyperopen.domain.trading.indicators.catalog.flow :as catalog]
            [hyperopen.domain.trading.indicators.contracts :as contracts]
            [hyperopen.domain.trading.indicators.math-adapter :as math-adapter]
            [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private flow-indicator-definitions catalog/flow-indicator-definitions)

(defn get-flow-indicators
  []
  flow-indicator-definitions)

(def ^:private field-values imath/field-values)
(def ^:private parse-period imath/parse-period)
(def ^:private normalize-values imath/normalize-values)

(defn- calculate-accumulation-distribution
  [data _params]
  (let [highs (field-values data :high)
        lows (field-values data :low)
        closes (field-values data :close)
        volumes (field-values data :volume)
        size (count data)
        values (loop [idx 0
                      running 0
                      result []]
                 (if (= idx size)
                   result
                   (let [high (nth highs idx)
                         low (nth lows idx)
                         close (nth closes idx)
                         volume (nth volumes idx)
                         range-value (- high low)
                         multiplier (if (zero? range-value)
                                      0
                                      (/ (- (- close low)
                                            (- high close))
                                         range-value))
                         next-value (+ running (* multiplier volume))]
                     (recur (inc idx) next-value (conj result next-value)))))]
    (result/indicator-result :accumulation-distribution
                             :separate
                             [(result/line-series :adl values)])))

(defn- calculate-accumulative-swing-index
  [data _params]
  (let [opens (field-values data :open)
        highs (field-values data :high)
        lows (field-values data :low)
        closes (field-values data :close)
        size (count data)
        values (loop [idx 0
                      running 0
                      result []]
                 (if (= idx size)
                   result
                   (if (zero? idx)
                     (recur (inc idx) 0 (conj result 0))
                     (let [open (nth opens idx)
                           high (nth highs idx)
                           low (nth lows idx)
                           close (nth closes idx)
                           prev-open (nth opens (dec idx))
                           prev-close (nth closes (dec idx))
                           distance-a (js/Math.abs (- high prev-close))
                           distance-b (js/Math.abs (- low prev-close))
                           distance-c (js/Math.abs (- high low))
                           distance-d (js/Math.abs (- prev-close prev-open))
                           range-value (cond
                                         (and (> distance-a distance-b)
                                              (> distance-a distance-c))
                                         (+ (- distance-a (/ distance-b 2))
                                            (/ distance-d 4))

                                         (and (> distance-b distance-a)
                                              (> distance-b distance-c))
                                         (+ (- distance-b (/ distance-a 2))
                                            (/ distance-d 4))

                                         :else
                                         (+ distance-c (/ distance-d 4)))
                           k (max distance-a distance-b)
                           t (max distance-a distance-b distance-c)
                           swing-index (if (or (zero? range-value) (zero? t))
                                         0
                                         (* 50
                                            (/ (+ (- close prev-close)
                                                  (/ (- close open) 2)
                                                  (/ (- prev-close prev-open) 4))
                                               range-value)
                                            (/ k t)))
                           next-value (+ running swing-index)]
                       (recur (inc idx) next-value (conj result next-value))))))]
    (result/indicator-result :accumulative-swing-index
                             :separate
                             [(result/line-series :asi values)])))

(defn- calculate-volume
  [data _params]
  (let [values (field-values data :volume)]
    (result/indicator-result :volume
                             :separate
                             [(result/histogram-series :volume values)])))

(defn- calculate-net-volume
  [data _params]
  (let [close-values (field-values data :close)
        volume-values (field-values data :volume)
        values (mapv (fn [idx]
                       (if (zero? idx)
                         0
                         (let [volume (nth volume-values idx)]
                           (cond
                             (> (nth close-values idx) (nth close-values (dec idx))) volume
                             (< (nth close-values idx) (nth close-values (dec idx))) (- volume)
                             :else 0))))
                     (range (count close-values)))]
    (result/indicator-result :net-volume
                             :separate
                             [(result/histogram-series :net-volume values)])))

(defn- calculate-on-balance-volume
  [data _params]
  (let [values (normalize-values
                (math-adapter/on-balance-volume (field-values data :close)
                                                (field-values data :volume)))]
    (result/indicator-result :on-balance-volume
                             :separate
                             [(result/line-series :obv values)])))

(defn- calculate-price-volume-trend
  [data _params]
  (let [values (normalize-values
                (math-adapter/price-volume-trend (field-values data :close)
                                                 (field-values data :volume)))]
    (result/indicator-result :price-volume-trend
                             :separate
                             [(result/line-series :pvt values)])))

(defn- calculate-volume-oscillator
  [data params]
  (let [fast (parse-period (:fast params) 12 1 200)
        slow (parse-period (:slow params) 26 2 400)
        signal (parse-period (:signal params) 9 1 200)
        result (math-adapter/percentage-volume-oscillator (field-values data :volume)
                                                          {:fast fast
                                                           :slow slow
                                                           :signal signal})
        pvo-line (normalize-values (:pvoResult result))
        signal-line (normalize-values (:signal result))
        histogram (normalize-values (:histogram result))]
    (result/indicator-result :volume-oscillator
                             :separate
                             [(result/histogram-series :hist histogram)
                              (result/line-series :pvo pvo-line)
                              (result/line-series :signal signal-line)])))

(defn- calculate-chaikin-money-flow
  [data params]
  (let [period (parse-period (:period params) 20 2 200)
        values (normalize-values
                (math-adapter/chaikin-money-flow (field-values data :high)
                                                 (field-values data :low)
                                                 (field-values data :close)
                                                 (field-values data :volume)
                                                 {:period period}))]
    (result/indicator-result :chaikin-money-flow
                             :separate
                             [(result/line-series :cmf values)])))

(defn- calculate-chaikin-oscillator
  [data params]
  (let [fast (parse-period (:fast params) 3 1 200)
        slow (parse-period (:slow params) 10 2 400)
        result (math-adapter/chaikin-oscillator (field-values data :high)
                                                (field-values data :low)
                                                (field-values data :close)
                                                (field-values data :volume)
                                                {:fast fast :slow slow})
        ad-line (normalize-values (:adResult result))
        osc-line (normalize-values (:cmoResult result))]
    (result/indicator-result :chaikin-oscillator
                             :separate
                             [(result/line-series :chaikin-osc osc-line)
                              (result/line-series :ad-line ad-line)])))

(defn- calculate-ease-of-movement
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        values (normalize-values
                (math-adapter/ease-of-movement (field-values data :high)
                                               (field-values data :low)
                                               (field-values data :volume)
                                               {:period period}))]
    (result/indicator-result :ease-of-movement
                             :separate
                             [(result/line-series :eom values)])))

(defn- calculate-elders-force-index
  [data params]
  (let [period (parse-period (:period params) 13 2 200)
        values (normalize-values
                (math-adapter/elders-force-index (field-values data :close)
                                                 (field-values data :volume)
                                                 {:period period}))]
    (result/indicator-result :elders-force-index
                             :separate
                             [(result/line-series :efi values)])))

(defn- calculate-money-flow-index
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        values (normalize-values
                (math-adapter/money-flow-index (field-values data :high)
                                               (field-values data :low)
                                               (field-values data :close)
                                               (field-values data :volume)
                                               {:period period}))]
    (result/indicator-result :money-flow-index
                             :separate
                             [(result/line-series :mfi values)])))

(def ^:private flow-calculators
  {:accumulation-distribution calculate-accumulation-distribution
   :accumulative-swing-index calculate-accumulative-swing-index
   :volume calculate-volume
   :net-volume calculate-net-volume
   :on-balance-volume calculate-on-balance-volume
   :price-volume-trend calculate-price-volume-trend
   :volume-oscillator calculate-volume-oscillator
   :chaikin-money-flow calculate-chaikin-money-flow
   :chaikin-oscillator calculate-chaikin-oscillator
   :ease-of-movement calculate-ease-of-movement
   :elders-force-index calculate-elders-force-index
   :money-flow-index calculate-money-flow-index})

(defn supported-flow-indicator-ids
  []
  (set (keys flow-calculators)))

(defn calculate-flow-indicator
  [indicator-type data params]
  (let [config (or params {})
        calculator (get flow-calculators indicator-type)]
    (when (and calculator
               (contracts/valid-indicator-input? indicator-type data config))
      (contracts/enforce-indicator-result indicator-type
                                          (count data)
                                          (calculator data config)))))
