(ns hyperopen.domain.trading.indicators.schema
  (:require [hyperopen.domain.trading.indicators.catalog.flow :as flow-catalog]
            [hyperopen.domain.trading.indicators.catalog.oscillators :as oscillators-catalog]
            [hyperopen.domain.trading.indicators.catalog.price :as price-catalog]
            [hyperopen.domain.trading.indicators.catalog.structure :as structure-catalog]
            [hyperopen.domain.trading.indicators.catalog.trend :as trend-catalog]
            [hyperopen.domain.trading.indicators.catalog.volatility :as volatility-catalog]))

(def ^:private all-indicator-definitions
  (vec (concat trend-catalog/trend-indicator-definitions
               structure-catalog/structure-indicator-definitions
               oscillators-catalog/oscillator-indicator-definitions
               volatility-catalog/volatility-indicator-definitions
               flow-catalog/flow-indicator-definitions
               price-catalog/price-indicator-definitions)))

(def ^:private indicator-definitions-by-id
  (into {}
        (map (juxt :id identity) all-indicator-definitions)))

(defn indicator-definitions
  []
  all-indicator-definitions)

(defn indicator-definition
  [indicator-type]
  (get indicator-definitions-by-id indicator-type))

(defn known-indicator?
  [indicator-type]
  (contains? indicator-definitions-by-id indicator-type))

(defn- numeric-default-spec
  [value]
  (when (number? value)
    {:kind :number
     :default value
     :required? false}))

(defn- numeric-vector-default-spec
  [value]
  (when (and (sequential? value)
             (every? number? value))
    {:kind :number-vector
     :default value
     :required? false}))

(defn- infer-param-spec
  [value]
  (or (numeric-default-spec value)
      (numeric-vector-default-spec value)))

(defn indicator-param-specs
  [indicator-type]
  (let [{:keys [supports-period? min-period max-period default-config] :as definition}
        (indicator-definition indicator-type)
        config-specs (into {}
                           (keep (fn [[param-key default-value]]
                                   (when-let [spec (infer-param-spec default-value)]
                                     [param-key spec])))
                           default-config)
        period-spec (when supports-period?
                      {:kind :number
                       :required? false
                       :min min-period
                       :max max-period
                       :default (:default-period definition)})]
    (cond-> config-specs
      period-spec (assoc :period period-spec))))
