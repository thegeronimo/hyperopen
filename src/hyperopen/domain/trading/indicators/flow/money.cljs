(ns hyperopen.domain.trading.indicators.flow.money
  (:require [hyperopen.domain.trading.indicators.math-engine :as math-engine]
            [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private field-values imath/field-values)
(def ^:private parse-period imath/parse-period)
(def ^:private normalize-values imath/normalize-values)

(defn calculate-chaikin-money-flow
  [data params]
  (let [period (parse-period (:period params) 20 2 200)
        values (normalize-values
                (math-engine/chaikin-money-flow (field-values data :high)
                                                 (field-values data :low)
                                                 (field-values data :close)
                                                 (field-values data :volume)
                                                 {:period period}))]
    (result/indicator-result :chaikin-money-flow
                             :separate
                             [(result/line-series :cmf values)])))

(defn calculate-chaikin-oscillator
  [data params]
  (let [fast (parse-period (:fast params) 3 1 200)
        slow (parse-period (:slow params) 10 2 400)
        result (math-engine/chaikin-oscillator (field-values data :high)
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

(defn calculate-ease-of-movement
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        values (normalize-values
                (math-engine/ease-of-movement (field-values data :high)
                                               (field-values data :low)
                                               (field-values data :volume)
                                               {:period period}))]
    (result/indicator-result :ease-of-movement
                             :separate
                             [(result/line-series :eom values)])))

(defn calculate-elders-force-index
  [data params]
  (let [period (parse-period (:period params) 13 2 200)
        values (normalize-values
                (math-engine/elders-force-index (field-values data :close)
                                                 (field-values data :volume)
                                                 {:period period}))]
    (result/indicator-result :elders-force-index
                             :separate
                             [(result/line-series :efi values)])))

(defn calculate-money-flow-index
  [data params]
  (let [period (parse-period (:period params) 14 2 200)
        values (normalize-values
                (math-engine/money-flow-index (field-values data :high)
                                               (field-values data :low)
                                               (field-values data :close)
                                               (field-values data :volume)
                                               {:period period}))]
    (result/indicator-result :money-flow-index
                             :separate
                             [(result/line-series :mfi values)])))
