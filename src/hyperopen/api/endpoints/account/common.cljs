(ns hyperopen.api.endpoints.account.common
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]))

(defn wait-ms
  [delay-ms]
  (js/Promise.
   (fn [resolve _reject]
     (platform/set-timeout! resolve (max 0 (or delay-ms 0))))))

(defn parse-ms
  [value]
  (let [parsed (cond
                 (integer? value) value
                 (and (number? value)
                      (not (js/isNaN value))) value
                 (string? value) (js/parseInt (str/trim value) 10)
                 :else js/NaN)]
    (when (and (number? parsed)
               (not (js/isNaN parsed)))
      (js/Math.floor parsed))))

(defn normalize-address
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (when (and (seq text)
               (re-matches #"^0x[0-9a-f]{40}$" text))
      text)))

(defn optional-number
  [value]
  (let [parsed (cond
                 (number? value) value
                 (string? value) (js/Number (str/trim value))
                 :else js/NaN)]
    (when (and (number? parsed)
               (not (js/isNaN parsed))
               (js/isFinite parsed))
      parsed)))
