(ns hyperopen.funding.domain.amounts
  (:require [clojure.string :as str]
            [hyperopen.domain.trading :as trading-domain]))

(defn non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn parse-num
  [value]
  (trading-domain/parse-num value))

(defn finite-number?
  [value]
  (and (number? value)
       (js/isFinite value)
       (not (js/isNaN value))))

(defn amount->text
  [value]
  (if (finite-number? value)
    (trading-domain/number->clean-string (max 0 value) 6)
    "0"))

(defn normalize-amount-input
  [value]
  (-> (or value "")
      str
      (str/replace #"," "")
      (str/replace #"\s+" "")))

(defn parse-input-amount
  [value]
  (let [parsed (parse-num (normalize-amount-input value))]
    (when (finite-number? parsed)
      (max 0 parsed))))

(defn normalize-evm-address
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (when (and (string? text)
               (re-matches #"^0x[0-9a-f]{40}$" text))
      text)))

(defn normalize-withdraw-destination
  [value]
  (non-blank-text value))

(defn format-usdc-display
  [value]
  (.toLocaleString (js/Number. (max 0 (or (parse-num value) 0)))
                   "en-US"
                   #js {:minimumFractionDigits 2
                        :maximumFractionDigits 2}))

(defn format-usdc-input
  [value]
  (amount->text (max 0 (or (parse-num value) 0))))
