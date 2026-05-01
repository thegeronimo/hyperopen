(ns hyperopen.api.endpoints.vaults.common
  (:require [clojure.string :as str]))

(defn non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn normalize-address
  [value]
  (some-> value non-blank-text str/lower-case))

(defn parse-optional-num
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/Number (str/trim value))
              :else js/NaN)]
    (when (and (number? num)
               (js/isFinite num))
      num)))

(defn parse-optional-int
  [value]
  (when-let [n (parse-optional-num value)]
    (js/Math.floor n)))

(defn boolean-value
  [value]
  (cond
    (true? value) true
    (false? value) false

    (string? value)
    (case (some-> value str str/trim str/lower-case)
      "true" true
      "false" false
      nil)

    :else nil))
