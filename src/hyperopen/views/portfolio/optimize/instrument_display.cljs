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
  (or (when (symbol-first? instrument)
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
  (or (non-blank-text (:base instrument))
      (base-from-symbol (:symbol instrument))
      (when-not (raw-asset-id? (:coin instrument))
        (non-blank-text (:coin instrument)))))
