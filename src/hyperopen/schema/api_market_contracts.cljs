(ns hyperopen.schema.api-market-contracts
  (:require [clojure.string :as str]))

(def ^:private required-perp-dex-metadata-keys
  #{:dex-names :fee-config-by-name})

(def ^:private optional-perp-dex-metadata-keys
  #{:full-name-by-name})

(def ^:private required-fee-config-keys
  #{:deployer-fee-scale})

(defn- non-empty-string?
  [value]
  (and (string? value)
       (seq (str/trim value))))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- dex-names?
  [value]
  (and (vector? value)
       (every? non-empty-string? value)))

(defn- fee-config?
  [value]
  (and (map? value)
       (= required-fee-config-keys (set (keys value)))
       (finite-number? (:deployer-fee-scale value))))

(defn- fee-config-by-name?
  [value]
  (and (map? value)
       (every? (fn [[dex config]]
                 (and (non-empty-string? dex)
                      (fee-config? config)))
               value)))

(defn- full-name-by-name?
  [value]
  (and (map? value)
       (every? (fn [[dex full-name]]
                 (and (non-empty-string? dex)
                      (non-empty-string? full-name)))
               value)))

(defn normalized-perp-dex-metadata-valid?
  [payload]
  (and (map? payload)
       (let [payload-keys (set (keys payload))]
         (and (every? payload-keys required-perp-dex-metadata-keys)
              (every? (into required-perp-dex-metadata-keys
                            optional-perp-dex-metadata-keys)
                      payload-keys)))
       (dex-names? (:dex-names payload))
       (fee-config-by-name? (:fee-config-by-name payload))
       (or (not (contains? payload :full-name-by-name))
           (full-name-by-name? (:full-name-by-name payload)))
       (let [dex-names* (set (:dex-names payload))]
         (every? dex-names* (keys (:fee-config-by-name payload))))))

(defn assert-normalized-perp-dex-metadata!
  [payload context]
  (when-not (normalized-perp-dex-metadata-valid? payload)
    (throw (js/Error.
            (str "perp DEX metadata contract validation failed. "
                 "context=" (pr-str context)
                 " payload=" (pr-str payload)))))
  payload)
