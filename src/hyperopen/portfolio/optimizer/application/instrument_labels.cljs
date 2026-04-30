(ns hyperopen.portfolio.optimizer.application.instrument-labels
  (:require [clojure.string :as str]))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- universe-by-id
  [universe]
  (into {}
        (map (fn [instrument]
               [(:instrument-id instrument) instrument]))
        universe))

(defn- vault-instrument-id?
  [instrument-id]
  (str/starts-with? (or (some-> instrument-id str) "") "vault:"))

(defn- vault-instrument?
  [instrument instrument-id]
  (or (= :vault (:market-type instrument))
      (vault-instrument-id? instrument-id)))

(defn- label-for-instrument
  [instrument-id instrument]
  (if (vault-instrument? instrument instrument-id)
    (or (non-blank-text (:name instrument))
        (non-blank-text (:symbol instrument))
        (non-blank-text (:coin instrument))
        instrument-id)
    (or (non-blank-text (:coin instrument))
        instrument-id)))

(defn labels-by-instrument
  [universe instrument-ids]
  (let [by-id (universe-by-id universe)]
    (into {}
          (map (fn [instrument-id]
                 [instrument-id
                  (label-for-instrument instrument-id (get by-id instrument-id))]))
          instrument-ids)))
