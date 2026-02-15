(ns hyperopen.domain.trading.indicators.registry
  (:require [hyperopen.domain.trading.indicators.flow :as flow]
            [hyperopen.domain.trading.indicators.oscillators :as oscillators]
            [hyperopen.domain.trading.indicators.price :as price]
            [hyperopen.domain.trading.indicators.structure :as structure]
            [hyperopen.domain.trading.indicators.trend :as trend]
            [hyperopen.domain.trading.indicators.volatility :as volatility]))

(def ^:private built-in-families
  [{:id :trend
    :get-indicators trend/get-trend-indicators
    :calculate-indicator trend/calculate-trend-indicator}
   {:id :structure
    :get-indicators structure/get-structure-indicators
    :calculate-indicator structure/calculate-structure-indicator}
   {:id :oscillators
    :get-indicators oscillators/get-oscillator-indicators
    :calculate-indicator oscillators/calculate-oscillator-indicator}
   {:id :volatility
    :get-indicators volatility/get-volatility-indicators
    :calculate-indicator volatility/calculate-volatility-indicator}
   {:id :flow
    :get-indicators flow/get-flow-indicators
    :calculate-indicator flow/calculate-flow-indicator}
   {:id :price
    :get-indicators price/get-price-indicators
    :calculate-indicator price/calculate-price-indicator}])

(defonce ^:private registered-families (atom []))

(defn register-domain-family!
  "Register an additional indicator family descriptor.

  Family descriptor shape:
  {:id keyword
   :get-indicators (fn [] [indicator-def ...])
   :calculate-indicator (fn [indicator-type data params] -> result-or-nil)}"
  [{:keys [id get-indicators calculate-indicator] :as family}]
  (when (and (keyword? id)
             (fn? get-indicators)
             (fn? calculate-indicator))
    (swap! registered-families
           (fn [families]
             (let [without-id (remove #(= id (:id %)) families)]
               (conj (vec without-id) family)))))
  nil)

(defn reset-registered-domain-families!
  "Clear dynamically registered indicator families.
  Intended for tests and deterministic startup."
  []
  (reset! registered-families [])
  nil)

(defn- all-families
  []
  (concat built-in-families @registered-families))

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

(defn get-domain-indicators
  []
  (->> (all-families)
       (mapcat (fn [{:keys [get-indicators]}]
                 (get-indicators)))
       dedupe-indicators))

(defn calculate-domain-indicator
  [indicator-type data params]
  (let [config (or params {})]
    (some (fn [{:keys [calculate-indicator]}]
            (calculate-indicator indicator-type data config))
          (all-families))))
