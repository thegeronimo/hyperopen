(ns hyperopen.views.portfolio.optimize.instrument-display
  (:require [clojure.string :as str]))

(defn non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn raw-asset-id?
  [value]
  (let [text (non-blank-text value)]
    (boolean
     (and text
          (or (str/starts-with? text "@")
              (re-matches #"\d+" text))))))

(def ^:private vault-prefix
  "vault:")

(defn vault-instrument?
  [instrument]
  (= :vault (:market-type instrument)))

(defn vault-address
  [instrument]
  (let [direct (non-blank-text (:vault-address instrument))
        from-value (fn [value]
                     (let [text (non-blank-text value)
                           lower (some-> text str/lower-case)]
                       (when (and lower
                                  (str/starts-with? lower vault-prefix))
                         (subs text (count vault-prefix)))))]
    (some-> (or direct
                (from-value (:coin instrument))
                (from-value (:instrument-id instrument)))
            str/lower-case)))

(defn hip3-instrument?
  [instrument]
  (boolean
   (or (:dex instrument)
       (:hip3? instrument)
       (:hip3-eligible? instrument))))

(defn spot-instrument?
  [instrument]
  (= :spot (:market-type instrument)))

(defn symbol-first?
  [instrument]
  (or (spot-instrument? instrument)
      (hip3-instrument? instrument)
      (raw-asset-id? (:coin instrument))))

(defn base-from-symbol
  [symbol]
  (let [symbol* (non-blank-text symbol)]
    (cond
      (and symbol* (str/includes? symbol* "/"))
      (non-blank-text (first (str/split symbol* #"/" 2)))

      (and symbol* (str/includes? symbol* "-"))
      (non-blank-text (first (str/split symbol* #"-" 2)))

      :else nil)))

(defn primary-label
  [instrument]
  (or (when (vault-instrument? instrument)
        (or (non-blank-text (:name instrument))
            (non-blank-text (:symbol instrument))
            (vault-address instrument)))
      (when (symbol-first? instrument)
        (non-blank-text (:symbol instrument)))
      (when (raw-asset-id? (:coin instrument))
        (or (non-blank-text (:base instrument))
            (base-from-symbol (:symbol instrument))))
      (non-blank-text (:coin instrument))
      (non-blank-text (:symbol instrument))
      (non-blank-text (:instrument-id instrument))
      "--"))

(defn base-label
  [instrument]
  (or (when (vault-instrument? instrument)
        (vault-address instrument))
      (non-blank-text (:base instrument))
      (base-from-symbol (:symbol instrument))
      (when-not (raw-asset-id? (:coin instrument))
        (non-blank-text (:coin instrument)))))
