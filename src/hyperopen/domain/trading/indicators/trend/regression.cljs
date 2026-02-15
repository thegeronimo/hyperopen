(ns hyperopen.domain.trading.indicators.trend.regression
  (:require [hyperopen.domain.trading.indicators.math-engine :as math-engine]
            [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private parse-period imath/parse-period)
(def ^:private field-values imath/field-values)
(def ^:private normalize-values imath/normalize-values)

(defn- indices
  [n]
  (vec (range n)))

(defn calculate-least-squares-moving-average
  [data params]
  (let [period (parse-period (:period params) 25 2 400)
        close-values (field-values data :close)
        x-values (indices (count close-values))
        regression (normalize-values
                    (math-engine/moving-linear-regression period
                                                           x-values
                                                           close-values))]
    (result/indicator-result :least-squares-moving-average
                             :overlay
                             [(result/line-series :lsma regression)])))

(defn calculate-linear-regression-curve
  [data params]
  (let [period (parse-period (:period params) 25 2 400)
        close-values (field-values data :close)
        x-values (indices (count close-values))
        regression (normalize-values
                    (math-engine/moving-linear-regression period
                                                           x-values
                                                           close-values))]
    (result/indicator-result :linear-regression-curve
                             :overlay
                             [(result/line-series :lrc regression)])))

(defn calculate-linear-regression-slope
  [data params]
  (let [period (parse-period (:period params) 25 2 400)
        close-values (field-values data :close)
        x-values (indices (count close-values))
        result (math-engine/moving-least-square period
                                                 x-values
                                                 close-values)
        slope (normalize-values (:m result))]
    (result/indicator-result :linear-regression-slope
                             :separate
                             [(result/line-series :slope slope)])))
