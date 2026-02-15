(ns hyperopen.domain.trading.indicators.flow
  (:require [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.result :as result]
            ["indicatorts" :refer [obv pvo vpt]]))

(def ^:private flow-indicator-definitions
  [{:id :accumulation-distribution
    :name "Accumulation/Distribution"
    :short-name "A/D"
    :description "Cumulative money flow volume line"
    :supports-period? false
    :default-config {}
    :migrated-from :indicators}
   {:id :accumulative-swing-index
    :name "Accumulative Swing Index"
    :short-name "ASI"
    :description "Wilder swing index accumulated over time"
    :supports-period? false
    :default-config {}
    :migrated-from :indicators}
   {:id :volume
    :name "Volume"
    :short-name "VOL"
    :description "Raw traded volume"
    :supports-period? false
    :default-config {}
    :migrated-from :wave3}
   {:id :net-volume
    :name "Net Volume"
    :short-name "Net Vol"
    :description "Signed per-bar volume based on price direction"
    :supports-period? false
    :default-config {}
    :migrated-from :wave2}
   {:id :on-balance-volume
    :name "On Balance Volume"
    :short-name "OBV"
    :description "Cumulative signed volume"
    :supports-period? false
    :default-config {}
    :migrated-from :wave2}
   {:id :price-volume-trend
    :name "Price Volume Trend"
    :short-name "PVT"
    :description "Cumulative volume scaled by price change"
    :supports-period? false
    :default-config {}
    :migrated-from :wave2}
   {:id :volume-oscillator
    :name "Volume Oscillator"
    :short-name "PVO"
    :description "Percentage volume oscillator"
   :supports-period? false
   :default-config {:fast 12
                     :slow 26
                     :signal 9}
    :migrated-from :wave2}])

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
                (obv (into-array (field-values data :close))
                     (into-array (field-values data :volume))))]
    (result/indicator-result :on-balance-volume
                             :separate
                             [(result/line-series :obv values)])))

(defn- calculate-price-volume-trend
  [data _params]
  (let [values (normalize-values
                (vpt (into-array (field-values data :close))
                     (into-array (field-values data :volume))))]
    (result/indicator-result :price-volume-trend
                             :separate
                             [(result/line-series :pvt values)])))

(defn- calculate-volume-oscillator
  [data params]
  (let [fast (parse-period (:fast params) 12 1 200)
        slow (parse-period (:slow params) 26 2 400)
        signal (parse-period (:signal params) 9 1 200)
        result (js->clj
                (pvo (into-array (field-values data :volume))
                     #js {:fast fast
                          :slow slow
                          :signal signal})
                :keywordize-keys true)
        pvo-line (normalize-values (:pvoResult result))
        signal-line (normalize-values (:signal result))
        histogram (normalize-values (:histogram result))]
    (result/indicator-result :volume-oscillator
                             :separate
                             [(result/histogram-series :hist histogram)
                              (result/line-series :pvo pvo-line)
                              (result/line-series :signal signal-line)])))

(def ^:private flow-calculators
  {:accumulation-distribution calculate-accumulation-distribution
   :accumulative-swing-index calculate-accumulative-swing-index
   :volume calculate-volume
   :net-volume calculate-net-volume
   :on-balance-volume calculate-on-balance-volume
   :price-volume-trend calculate-price-volume-trend
   :volume-oscillator calculate-volume-oscillator})

(defn calculate-flow-indicator
  [indicator-type data params]
  (let [config (or params {})
        calculator (get flow-calculators indicator-type)]
    (when calculator
      (calculator data config))))
