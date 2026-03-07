(ns hyperopen.vaults.domain.identity
  (:require [clojure.string :as str]))

(defn non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn normalize-vault-address
  [value]
  (let [text (some-> value non-blank-text str/lower-case)]
    (when (and (string? text)
               (re-matches #"^0x[0-9a-f]{40}$" text))
      text)))

(defn relationship-child-addresses
  [relationship]
  (->> (or (:child-addresses relationship) [])
       (keep normalize-vault-address)
       distinct
       vec))

(defn vault-wallet-address
  [state]
  (normalize-vault-address (get-in state [:wallet :address])))

(defn merged-vault-row
  [state vault-address]
  (some (fn [row]
          (when (= vault-address (normalize-vault-address (:vault-address row)))
            row))
        (or (get-in state [:vaults :merged-index-rows]) [])))

(defn vault-details-record
  [state vault-address]
  (get-in state [:vaults :details-by-address vault-address]))

(defn vault-entity-name
  [state vault-address]
  (or (some-> (vault-details-record state vault-address) :name non-blank-text)
      (some-> (merged-vault-row state vault-address) :name non-blank-text)))

(defn vault-leader-address
  [state vault-address]
  (or (some-> (vault-details-record state vault-address) :leader normalize-vault-address)
      (some-> (merged-vault-row state vault-address) :leader normalize-vault-address)))

(defn component-vault-addresses
  [state vault-address]
  (let [vault-address* (normalize-vault-address vault-address)
        row (merged-vault-row state vault-address*)
        details (vault-details-record state vault-address*)]
    (->> (concat (relationship-child-addresses (:relationship row))
                 (relationship-child-addresses (:relationship details)))
         (remove #(= % vault-address*))
         distinct
         vec)))
