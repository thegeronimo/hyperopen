(ns hyperopen.schema.api-market-contracts
  (:require [cljs.spec.alpha :as s]
            [clojure.string :as str]))

(def ^:private required-perp-dex-metadata-keys
  #{:dex-names :fee-config-by-name})

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

(s/def ::dex-name non-empty-string?)
(s/def ::dex-names
  (s/and vector?
         (s/coll-of ::dex-name :kind vector?)))
(s/def ::deployer-fee-scale finite-number?)
(s/def ::fee-config
  (s/and map?
         #(= required-fee-config-keys (set (keys %)))
         #(s/valid? ::deployer-fee-scale (:deployer-fee-scale %))))
(s/def ::fee-config-by-name
  (s/and map?
         (fn [value]
           (every? (fn [[dex config]]
                     (and (s/valid? ::dex-name dex)
                          (s/valid? ::fee-config config)))
                   value))))
(s/def ::normalized-perp-dex-metadata
  (s/and map?
         #(= required-perp-dex-metadata-keys (set (keys %)))
         #(s/valid? ::dex-names (:dex-names %))
         #(s/valid? ::fee-config-by-name (:fee-config-by-name %))
         (fn [{:keys [dex-names fee-config-by-name]}]
           (let [dex-names* (set dex-names)]
             (every? dex-names* (keys fee-config-by-name))))))

(defn normalized-perp-dex-metadata-valid?
  [payload]
  (s/valid? ::normalized-perp-dex-metadata payload))

(defn assert-normalized-perp-dex-metadata!
  [payload context]
  (when-not (normalized-perp-dex-metadata-valid? payload)
    (throw (js/Error.
            (str "perp DEX metadata contract validation failed. "
                 "context=" (pr-str context)
                 " payload=" (pr-str payload)
                 " explain=" (pr-str (s/explain-data ::normalized-perp-dex-metadata payload))))))
  payload)
