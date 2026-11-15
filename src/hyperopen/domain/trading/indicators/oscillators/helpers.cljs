(ns hyperopen.domain.trading.indicators.oscillators.helpers
  (:require [hyperopen.domain.trading.indicators.math :as imath]))

(def finite-number? imath/finite-number?)
(def parse-period imath/parse-period)
(def parse-number imath/parse-number)
(def field-values imath/field-values)
(def mean imath/mean)
(def normalize-values imath/normalize-values)
(def roc-percent-values imath/roc-percent-values)
(def true-range-at imath/true-range-at)

(defn sma-values
  [values period]
  (imath/sma-values values period :lagged))

(defn rolling-apply-lagged
  [values period f]
  (imath/rolling-apply values period f :lagged))

(defn rma-values
  [values period]
  (imath/rma-values values period :lagged))

(defn sma-aligned-values
  [values period]
  (imath/sma-values values period :aligned))

(defn rolling-sum-aligned
  [values period]
  (imath/rolling-sum values period :aligned))

(defn rolling-max-aligned
  [values period]
  (imath/rolling-max values period :aligned))

(defn rolling-min-aligned
  [values period]
  (imath/rolling-min values period :aligned))

(defn rma-aligned-values
  [values period]
  (imath/rma-values values period :aligned))

(defn stddev-aligned-values
  [values period]
  (imath/stddev-values values period :aligned))

(defn- rsi-core
  [values period rma-fn]
  (let [size (count values)
        diffs (mapv (fn [idx]
                      (if (pos? idx)
                        (- (nth values idx) (nth values (dec idx)))
                        nil))
                    (range size))
        gains (mapv (fn [d]
                      (when (finite-number? d)
                        (max d 0)))
                    diffs)
        losses (mapv (fn [d]
                       (when (finite-number? d)
                         (max (- d) 0)))
                     diffs)
        avg-gains (rma-fn gains period)
        avg-losses (rma-fn losses period)]
    (mapv (fn [g l]
            (when (and (finite-number? g)
                       (finite-number? l))
              (if (zero? l)
                100
                (- 100 (/ 100 (+ 1 (/ g l)))))))
          avg-gains avg-losses)))

(defn rsi-values
  [values period]
  (rsi-core values period rma-values))

(defn rsi-aligned-values
  [values period]
  (rsi-core values period rma-aligned-values))

(defn true-range-values
  [data]
  (let [high-values (field-values data :high)
        low-values (field-values data :low)
        close-values (field-values data :close)
        size (count data)]
    (mapv (fn [idx]
            (let [prev-close (when (pos? idx)
                               (nth close-values (dec idx)))]
              (true-range-at (nth high-values idx)
                             (nth low-values idx)
                             prev-close)))
          (range size))))
