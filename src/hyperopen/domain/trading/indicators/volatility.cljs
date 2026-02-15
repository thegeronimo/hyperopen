(ns hyperopen.domain.trading.indicators.volatility
  (:require [hyperopen.domain.trading.indicators.catalog.volatility :as catalog]
            [hyperopen.domain.trading.indicators.family-runtime :as family-runtime]
            [hyperopen.domain.trading.indicators.volatility.channels :as channels]
            [hyperopen.domain.trading.indicators.volatility.dispersion :as dispersion]
            [hyperopen.domain.trading.indicators.volatility.range :as range]))

(def ^:private volatility-indicator-definitions catalog/volatility-indicator-definitions)

(declare volatility-family)

(defn get-volatility-indicators
  []
  (family-runtime/indicators volatility-family))

(def ^:private volatility-calculators
  {:week-52-high-low range/calculate-52-week-high-low
   :atr range/calculate-atr
   :bollinger-bands dispersion/calculate-bollinger-bands
   :bollinger-bands-percent-b dispersion/calculate-bollinger-bands-percent-b
   :bollinger-bands-width dispersion/calculate-bollinger-bands-width
   :donchian-channels channels/calculate-donchian-channels
   :price-channel channels/calculate-price-channel
   :historical-volatility channels/calculate-historical-volatility
   :keltner-channels channels/calculate-keltner-channels
   :moving-average-channel channels/calculate-moving-average-channel
   :standard-deviation dispersion/calculate-standard-deviation
   :standard-error dispersion/calculate-standard-error
   :standard-error-bands dispersion/calculate-standard-error-bands
   :volatility-close-to-close dispersion/calculate-volatility-close-to-close
   :volatility-index range/calculate-volatility-index
   :volatility-ohlc dispersion/calculate-volatility-ohlc
   :volatility-zero-trend-close-to-close dispersion/calculate-volatility-zero-trend-close-to-close})

(def ^:private volatility-family
  (family-runtime/build-family :volatility
                               volatility-indicator-definitions
                               volatility-calculators))

(defn supported-volatility-indicator-ids
  []
  (family-runtime/supported-indicator-ids volatility-family))

(defn calculate-volatility-indicator
  [indicator-type data params]
  (family-runtime/calculate volatility-family indicator-type data params))
