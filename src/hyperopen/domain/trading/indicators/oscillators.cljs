(ns hyperopen.domain.trading.indicators.oscillators
  (:require [hyperopen.domain.trading.indicators.catalog.oscillators :as catalog]
            [hyperopen.domain.trading.indicators.family-runtime :as family-runtime]
            [hyperopen.domain.trading.indicators.oscillators.classic :as classic]
            [hyperopen.domain.trading.indicators.oscillators.momentum :as momentum]
            [hyperopen.domain.trading.indicators.oscillators.patterns :as patterns]
            [hyperopen.domain.trading.indicators.oscillators.statistics :as statistics]
            [hyperopen.domain.trading.indicators.oscillators.structure :as structure]))

(def ^:private oscillator-indicator-definitions catalog/oscillator-indicator-definitions)

(declare oscillator-family)

(defn get-oscillator-indicators
  []
  (family-runtime/indicators oscillator-family))

(def ^:private oscillator-calculators
  {:accelerator-oscillator classic/calculate-accelerator-oscillator
   :advance-decline structure/calculate-advance-decline
   :awesome-oscillator classic/calculate-awesome-oscillator
   :balance-of-power classic/calculate-balance-of-power
   :coppock-curve patterns/calculate-coppock-curve
   :chande-momentum-oscillator momentum/calculate-chande-momentum-oscillator
   :choppiness-index structure/calculate-choppiness-index
   :commodity-channel-index classic/calculate-commodity-channel-index
   :detrended-price-oscillator momentum/calculate-detrended-price-oscillator
   :fisher-transform patterns/calculate-fisher-transform
   :macd classic/calculate-macd
   :mass-index classic/calculate-mass-index
   :majority-rule structure/calculate-majority-rule
   :chaikin-volatility structure/calculate-chaikin-volatility
   :chande-kroll-stop structure/calculate-chande-kroll-stop
   :chop-zone structure/calculate-chop-zone
   :connors-rsi patterns/calculate-connors-rsi
   :correlation-log statistics/calculate-correlation-log
   :klinger-oscillator patterns/calculate-klinger-oscillator
   :know-sure-thing patterns/calculate-know-sure-thing
   :momentum momentum/calculate-momentum
   :price-oscillator momentum/calculate-price-oscillator
   :rate-of-change momentum/calculate-rate-of-change
   :relative-strength-index classic/calculate-relative-strength-index
   :ratio structure/calculate-ratio
   :correlation-coefficient statistics/calculate-correlation-coefficient
   :relative-vigor-index structure/calculate-relative-vigor-index
   :relative-volatility-index structure/calculate-relative-volatility-index
   :smi-ergodic statistics/calculate-smi-ergodic
   :spread structure/calculate-spread
   :stochastic classic/calculate-stochastic
   :stochastic-rsi classic/calculate-stochastic-rsi
   :trix momentum/calculate-trix
   :true-strength-index statistics/calculate-true-strength-index
   :trend-strength-index statistics/calculate-trend-strength-index
   :ultimate-oscillator classic/calculate-ultimate-oscillator
   :williams-r momentum/calculate-williams-r})

(def ^:private oscillator-family
  (family-runtime/build-family :oscillators
                               oscillator-indicator-definitions
                               oscillator-calculators))

(defn supported-oscillator-indicator-ids
  []
  (family-runtime/supported-indicator-ids oscillator-family))

(defn calculate-oscillator-indicator
  [indicator-type data params]
  (family-runtime/calculate oscillator-family indicator-type data params))
