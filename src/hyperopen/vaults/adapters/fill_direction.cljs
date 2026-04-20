(ns hyperopen.vaults.adapters.fill-direction
  (:require [clojure.string :as str]))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- canonical-direction-label
  [value]
  (when-let [text (non-blank-text value)]
    (let [normalized (-> text
                         str/lower-case
                         (str/replace #"\s+" " "))]
      (cond
        (= "open long" normalized) "Open Long"
        (= "close long" normalized) "Close Long"
        (= "open short" normalized) "Open Short"
        (= "close short" normalized) "Close Short"
        (or (str/includes? normalized "open long")
            (str/includes? normalized "close long")
            (str/includes? normalized "open short")
            (str/includes? normalized "close short"))
        text
        :else nil))))

(defn direction-label
  [row]
  (or (canonical-direction-label (:dir row))
      (canonical-direction-label (:direction row))))

(defn action-side-key
  [direction-label]
  (let [normalized (some-> direction-label
                           non-blank-text
                           str/lower-case)]
    (cond
      (not normalized) nil
      (or (str/includes? normalized "open short")
          (str/includes? normalized "close long"))
      :short
      (or (str/includes? normalized "open long")
          (str/includes? normalized "close short"))
      :long
      :else nil)))

(defn position-direction-key
  [direction-label]
  (let [normalized (some-> direction-label
                           non-blank-text
                           str/lower-case)]
    (cond
      (not normalized) nil
      (str/includes? normalized "long") :long
      (str/includes? normalized "short") :short
      :else nil)))
