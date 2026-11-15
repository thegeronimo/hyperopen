(ns hyperopen.portfolio.optimizer.application.history-loader.api-v2.codec
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.coercion :as coercion]))

(def contract-version
  "optimizer-history-api-v2")

(defn non-blank-text
  [value]
  (coercion/non-blank-text value))

(defn finite-number?
  [value]
  (coercion/finite-number? value))

(defn parse-number
  [value]
  (coercion/parse-number value))

(defn parse-ms
  [value]
  (coercion/parse-ms value))

(defn- kebab-token
  [value]
  (-> value
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/replace #"_" "-")
      str/lower-case))

(def ^:private kebab-keyword-cache (js/Map.))

(defn- string->kebab-keyword
  [s]
  (or (.get kebab-keyword-cache s)
      (let [kw (keyword (kebab-token s))]
        (.set kebab-keyword-cache s kw)
        kw)))

(defn- key->kebab-keyword
  [key]
  (cond
    (keyword? key) (string->kebab-keyword (name key))
    (string? key) (string->kebab-keyword key)
    :else key))

(defn normalize-api-map
  [value]
  (cond
    (map? value)
    (into {}
          (map (fn [[k v]]
                 [(key->kebab-keyword k) (normalize-api-map v)]))
          value)

    (sequential? value)
    (mapv normalize-api-map value)

    :else
    value))

(defn keyed-map-field
  [m snake-key kebab-key]
  (or (get m (keyword snake-key))
      (get m (keyword kebab-key))
      (get m snake-key)
      (get m kebab-key)
      {}))

(defn keyed-map-id
  [key]
  (cond
    (keyword? key) (subs (str key) 1)
    (string? key) key
    :else (str key)))

(def ^:private keyword-like-cache (js/Map.))

(defn keyword-like
  [value]
  (if (string? value)
    (or (.get keyword-like-cache value)
        (let [normalized (coercion/normalize-keyword-like value)]
          (.set keyword-like-cache value normalized)
          normalized))
    (coercion/normalize-keyword-like value)))

(defn- attribute-excluded-from-alignment
  "The excluded-from-alignment diagnostic (per-instrument AND top-level)
  keywordizes its reason and, when it arrives top-level with no instrument_id,
  is attributed to its asset via the row key it carries in
  details.client_instrument_id — so per-asset surfaces treat it like every other
  warning instead of losing it."
  [warning]
  (cond-> warning
    (get-in warning [:details :reason])
    (update-in [:details :reason] keyword-like)

    (and (not (non-blank-text (:instrument-id warning)))
         (non-blank-text (get-in warning [:details :client-instrument-id])))
    (assoc :instrument-id (get-in warning [:details :client-instrument-id]))))

(defn normalize-warning
  [warning]
  (let [warning* (normalize-api-map warning)
        warning* (cond-> warning*
                   (:code warning*) (update :code keyword-like))]
    (cond-> warning*
      (= :excluded-from-alignment (:code warning*))
      attribute-excluded-from-alignment)))

(defn normalize-warnings
  [warnings]
  (mapv normalize-warning (or warnings [])))

(defn- warning-key
  [warning]
  [(:code warning)
   (:instrument-id warning)
   (:proxy-mapping-id warning)
   (:source-id warning)
   (:message warning)])

(defn distinct-warnings
  [warnings]
  (vec (vals (reduce (fn [acc warning]
                       (assoc acc (warning-key warning) warning))
                     {}
                     warnings))))

(defn normalize-proxy
  [proxy]
  (when (map? proxy)
    (cond-> (normalize-api-map proxy)
      (:mapping-kind proxy) (update :mapping-kind keyword-like))))
