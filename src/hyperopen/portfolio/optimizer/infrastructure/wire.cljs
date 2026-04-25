(ns hyperopen.portfolio.optimizer.infrastructure.wire
  (:require [clojure.string :as str]))

(def ^:private enum-value-keys
  #{:code
    :default-order-type
    :fee-mode
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
