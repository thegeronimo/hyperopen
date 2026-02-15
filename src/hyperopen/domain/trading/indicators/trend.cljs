(ns hyperopen.domain.trading.indicators.trend
  (:require [hyperopen.domain.trading.indicators.catalog.trend :as catalog]
            [hyperopen.domain.trading.indicators.family-runtime :as family-runtime]
            [hyperopen.domain.trading.indicators.math-adapter :as math-adapter]
            [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.trend.clouds :as clouds]
            [hyperopen.domain.trading.indicators.trend.moving-averages :as moving-averages]
            [hyperopen.domain.trading.indicators.trend.regression :as regression]
            [hyperopen.domain.trading.indicators.trend.strength :as strength]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private trend-indicator-definitions catalog/trend-indicator-definitions)

(declare trend-family)

(defn get-trend-indicators
  []
  (family-runtime/indicators trend-family))

(def ^:private finite-number? imath/finite-number?)
(def ^:private parse-period imath/parse-period)
(def ^:private parse-number imath/parse-number)
(def ^:private field-values imath/field-values)
(def ^:private normalize-values imath/normalize-values)

(defn- sma-values
  [values period]
  (imath/sma-values values period :lagged))

(defn- sma-aligned-values
  [values period]
  (imath/sma-values values period :aligned))

(defn calculate-sma-values
  [data period]
  (let [length (parse-period period 20 2 1000)
        closes (field-values data :close)]
    (sma-values closes length)))

(defn- calculate-envelopes
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        percent (parse-number (:percent params) 0.025)
        close-values (field-values data :close)
        basis (sma-aligned-values close-values period)
        upper (mapv (fn [value]
                      (when (finite-number? value)
                        (* value (+ 1 percent))))
                    basis)
        lower (mapv (fn [value]
                      (when (finite-number? value)
                        (* value (- 1 percent))))
                    basis)]
    (result/indicator-result :envelopes
                             :overlay
                             [(result/line-series :upper upper)
                              (result/line-series :basis basis)
                              (result/line-series :lower lower)])))

(defn- calculate-parabolic-sar
  [data params]
  (let [step (parse-number (:step params) 0.02)
        max-value (parse-number (:max params) 0.2)
        result (math-adapter/parabolic-sar (field-values data :high)
                                           (field-values data :low)
                                           (field-values data :close)
                                           {:step step :max-value max-value})
        values (normalize-values (:psarResult result))]
    (result/indicator-result :parabolic-sar
                             :overlay
                             [(result/line-series :psar values)])))

(defn- calculate-vwap
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        values (normalize-values
                (math-adapter/vwap (field-values data :close)
                                   (field-values data :volume)
                                   {:period period}))]
    (result/indicator-result :vwap
                             :overlay
                             [(result/line-series :vwap values)])))

(defn- calculate-vwma
  [data params]
  (let [period (parse-period (:period params) 20 2 400)
        values (normalize-values
                (math-adapter/vwma (field-values data :close)
                                   (field-values data :volume)
                                   {:period period}))]
    (result/indicator-result :vwma
                             :overlay
                             [(result/line-series :vwma values)])))

(def ^:private trend-calculators
  {:alma moving-averages/calculate-alma
   :aroon strength/calculate-aroon
   :adx strength/calculate-adx
   :double-ema moving-averages/calculate-double-ema
   :directional-movement strength/calculate-directional-movement
   :ema-cross moving-averages/calculate-ema-cross
   :envelopes calculate-envelopes
   :guppy-multiple-moving-average moving-averages/calculate-guppy-multiple-moving-average
   :hull-moving-average moving-averages/calculate-hull-moving-average
   :ichimoku-cloud clouds/calculate-ichimoku-cloud
   :least-squares-moving-average regression/calculate-least-squares-moving-average
   :linear-regression-curve regression/calculate-linear-regression-curve
   :linear-regression-slope regression/calculate-linear-regression-slope
   :ma-cross moving-averages/calculate-ma-cross
   :ma-with-ema-cross moving-averages/calculate-ma-with-ema-cross
   :mcginley-dynamic moving-averages/calculate-mcginley-dynamic
   :moving-average-adaptive moving-averages/calculate-moving-average-adaptive
   :moving-average-double moving-averages/calculate-moving-average-double
   :moving-average-exponential moving-averages/calculate-moving-average-exponential
   :moving-average-hamming moving-averages/calculate-moving-average-hamming
   :moving-average-multiple moving-averages/calculate-moving-average-multiple
   :moving-average-triple moving-averages/calculate-moving-average-triple
   :moving-average-weighted moving-averages/calculate-moving-average-weighted
   :parabolic-sar calculate-parabolic-sar
   :sma (fn [data params]
          (result/indicator-result :sma
                                   :overlay
                                   [(result/line-series :sma
                                                        (calculate-sma-values data (:period params 20)))]))
   :smoothed-moving-average moving-averages/calculate-smoothed-moving-average
   :supertrend strength/calculate-supertrend
   :triple-ema moving-averages/calculate-triple-ema
   :vortex-indicator strength/calculate-vortex-indicator
   :vwap calculate-vwap
   :vwma calculate-vwma
   :williams-alligator moving-averages/calculate-williams-alligator})

(def ^:private trend-family
  (family-runtime/build-family :trend
                               trend-indicator-definitions
                               trend-calculators))

(defn supported-trend-indicator-ids
  []
  (family-runtime/supported-indicator-ids trend-family))

(defn calculate-trend-indicator
  [indicator-type data params]
  (family-runtime/calculate trend-family indicator-type data params))
