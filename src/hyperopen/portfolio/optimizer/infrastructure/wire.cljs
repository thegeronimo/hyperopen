(ns hyperopen.portfolio.optimizer.infrastructure.wire
  (:require [clojure.string :as str]))

(def ^:private enum-value-keys
  #{:code
    :default-order-type
    :fee-mode
    :funding-source
    :instrument-type
    :kind
    :market-type
    :model
    :objective-kind
    :order-type
    :reason
    :side
    :source
    :status
    :strategy})

(defn- keyword-value
  [value]
  (cond
    (keyword? value) value
    (string? value) (let [text (str/trim value)]
                      (when (seq text)
                        (keyword text)))
    :else value))

(defn instrument-id-key
  [key]
  (cond
    (keyword? key) (subs (str key) 1)
    (string? key) key
    :else (str key)))

(defn stringify-instrument-keyed-map
  [value]
  (if (map? value)
    (into {}
          (map (fn [[key item]]
                 [(instrument-id-key key) item]))
          value)
    value))

(def instrument-keyed-map-paths
  [[:current-portfolio :by-instrument]
   [:history :return-series-by-instrument]
   [:history :price-series-by-instrument]
   [:history :funding-by-instrument]
   [:black-litterman-prior :weights-by-instrument]
   [:constraints :per-asset-overrides]
   [:constraints :per-perp-leverage-caps]
   [:execution-assumptions :prices-by-id]
   [:execution-assumptions :cost-contexts-by-id]
   [:execution-assumptions :fee-bps-by-id]
   [:payload :return-decomposition-by-instrument]
   [:payload :current-weights-by-instrument]
   [:payload :target-weights-by-instrument]
   [:payload :diagnostics :weight-sensitivity-by-instrument]
   [:return-decomposition-by-instrument]
   [:current-weights-by-instrument]
   [:target-weights-by-instrument]
   [:diagnostics :weight-sensitivity-by-instrument]])

(defn normalize-wire-values
  [value]
  (cond
    (map? value)
    (into {}
          (map (fn [[key item]]
                 (let [item* (normalize-wire-values item)]
                   [key (if (contains? enum-value-keys key)
                          (keyword-value item*)
                          item*)])))
          value)

    (vector? value)
    (mapv normalize-wire-values value)

    (seq? value)
    (doall (map normalize-wire-values value))

    :else value))

(defn- update-existing-in
  [value path f]
  (if (nil? (get-in value path))
    value
    (update-in value path f)))

(defn normalize-instrument-keyed-maps
  [value]
  (reduce (fn [value* path]
            (update-existing-in value* path stringify-instrument-keyed-map))
          value
          instrument-keyed-map-paths))

(defn normalize-worker-boundary
  [value]
  (-> value
      normalize-wire-values
      normalize-instrument-keyed-maps))
