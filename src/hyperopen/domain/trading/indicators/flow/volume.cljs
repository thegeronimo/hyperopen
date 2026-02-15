(ns hyperopen.domain.trading.indicators.flow.volume
  (:require [hyperopen.domain.trading.indicators.math-engine :as math-engine]
            [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private field-values imath/field-values)
(def ^:private parse-period imath/parse-period)
(def ^:private normalize-values imath/normalize-values)

(defn calculate-accumulation-distribution
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

(defn calculate-accumulative-swing-index
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

(defn calculate-volume
  [data _params]
  (let [values (field-values data :volume)]
    (result/indicator-result :volume
                             :separate
                             [(result/histogram-series :volume values)])))

(defn calculate-net-volume
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

(defn calculate-on-balance-volume
  [data _params]
  (let [values (normalize-values
                (math-engine/on-balance-volume (field-values data :close)
                                                (field-values data :volume)))]
    (result/indicator-result :on-balance-volume
                             :separate
                             [(result/line-series :obv values)])))

(defn calculate-price-volume-trend
  [data _params]
  (let [values (normalize-values
                (math-engine/price-volume-trend (field-values data :close)
                                                 (field-values data :volume)))]
    (result/indicator-result :price-volume-trend
                             :separate
                             [(result/line-series :pvt values)])))

(defn calculate-volume-oscillator
  [data params]
  (let [fast (parse-period (:fast params) 12 1 200)
        slow (parse-period (:slow params) 26 2 400)
        signal (parse-period (:signal params) 9 1 200)
        result (math-engine/percentage-volume-oscillator (field-values data :volume)
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
