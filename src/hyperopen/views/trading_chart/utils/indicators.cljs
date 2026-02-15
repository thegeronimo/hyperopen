(ns hyperopen.views.trading-chart.utils.indicators
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.registry :as domain-registry]
            [hyperopen.domain.trading.indicators.trend :as domain-trend]
            [hyperopen.views.trading-chart.utils.indicator-view-adapter :as view-adapter]))

(defn calculate-sma
  "Calculate Simple Moving Average for given data and period"
  [data period]
  (let [time-values (imath/times data)
        values (domain-trend/calculate-sma-values data period)]
    (view-adapter/points-from-values time-values values)))

(defn- dedupe-indicators
  [definitions]
  (loop [remaining definitions
         seen #{}
         out []]
    (if-let [indicator (first remaining)]
      (let [indicator-id (:id indicator)]
        (if (contains? seen indicator-id)
          (recur (rest remaining) seen out)
          (recur (rest remaining) (conj seen indicator-id) (conj out indicator))))
      (vec out))))

(defn get-available-indicators
  "Return list of available indicators"
  []
  (->> (domain-registry/get-domain-indicators)
       dedupe-indicators
       (sort-by (fn [{:keys [id] :as indicator}]
                  [(str/lower-case (or (:name indicator) ""))
                   (clojure.core/name id)]))
       vec))

(defn calculate-indicator
  "Calculate indicator based on type and parameters"
  [indicator-type data params]
  (let [config (or params {})
        domain-result (domain-registry/calculate-domain-indicator indicator-type data config)]
    (when domain-result
      (view-adapter/project-domain-indicator data domain-result))))
