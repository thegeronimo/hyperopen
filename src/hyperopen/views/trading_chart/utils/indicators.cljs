(ns hyperopen.views.trading-chart.utils.indicators
  (:require [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.registry :as domain-registry]
            [hyperopen.views.trading-chart.utils.indicator-catalog :as indicator-catalog]
            [hyperopen.views.trading-chart.utils.indicator-view-adapter :as view-adapter]))

(defn calculate-sma
  "Calculate Simple Moving Average for given data and period"
  [data period]
  (let [time-values (imath/times data)
        normalized-data (mapv (fn [{:keys [close] :as candle}]
                                (assoc candle
                                       :open (or (:open candle) close)
                                       :high (or (:high candle) close)
                                       :low (or (:low candle) close)))
                              data)
        domain-result (domain-registry/calculate-domain-indicator :sma normalized-data {:period period})
        values (get-in domain-result [:series 0 :values])]
    (view-adapter/points-from-values time-values values)))

(defn get-available-indicators
  "Return list of available indicators"
  []
  (indicator-catalog/get-available-indicators))

(defn calculate-indicator
  "Calculate indicator based on type and parameters"
  [indicator-type data params]
  (let [config (or params {})
        domain-result (domain-registry/calculate-domain-indicator indicator-type data config)]
    (when domain-result
      (view-adapter/project-domain-indicator data domain-result))))
