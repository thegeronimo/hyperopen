(ns hyperopen.domain.trading.indicators.flow
  (:require [hyperopen.domain.trading.indicators.catalog.flow :as catalog]
            [hyperopen.domain.trading.indicators.family-runtime :as family-runtime]
            [hyperopen.domain.trading.indicators.flow.money :as money]
            [hyperopen.domain.trading.indicators.flow.volume :as volume]))

(def ^:private flow-indicator-definitions catalog/flow-indicator-definitions)

(declare flow-family)

(defn get-flow-indicators
  []
  (family-runtime/indicators flow-family))

(def ^:private flow-calculators
  {:accumulation-distribution volume/calculate-accumulation-distribution
   :accumulative-swing-index volume/calculate-accumulative-swing-index
   :volume volume/calculate-volume
   :net-volume volume/calculate-net-volume
   :on-balance-volume volume/calculate-on-balance-volume
   :price-volume-trend volume/calculate-price-volume-trend
   :volume-oscillator volume/calculate-volume-oscillator
   :chaikin-money-flow money/calculate-chaikin-money-flow
   :chaikin-oscillator money/calculate-chaikin-oscillator
   :ease-of-movement money/calculate-ease-of-movement
   :elders-force-index money/calculate-elders-force-index
   :money-flow-index money/calculate-money-flow-index})

(def ^:private flow-family
  (family-runtime/build-family :flow
                               flow-indicator-definitions
                               flow-calculators))

(defn supported-flow-indicator-ids
  []
  (family-runtime/supported-indicator-ids flow-family))

(defn calculate-flow-indicator
  [indicator-type data params]
  (family-runtime/calculate flow-family indicator-type data params))
