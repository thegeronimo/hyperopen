(ns hyperopen.portfolio.optimizer.infrastructure.prior-data
  (:require [clojure.string :as str]))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- parse-number
  [value]
  (cond
    (finite-number? value)
    value

    (string? value)
    (let [text (str/trim value)
          parsed (js/parseFloat text)]
      (when (and (seq text)
                 (finite-number? parsed))
        parsed))

    :else
    nil))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- instrument-id
  [instrument]
  (or (non-blank-text (:instrument-id instrument))
      (non-blank-text (:coin instrument))))

(defn- coin
  [instrument]
  (non-blank-text (:coin instrument)))

(defn- positive-number?
  [value]
  (and (number? value)
       (pos? value)))

(defn- normalize-weights
  [weights-by-instrument]
  (let [positive-weights (into {}
                               (keep (fn [[id weight]]
                                       (when (and (seq id)
                                                  (positive-number? weight))
                                         [id weight])))
                               weights-by-instrument)
        total (reduce + (vals positive-weights))]
    (when (pos? total)
      (into {}
            (map (fn [[id weight]]
                   [id (/ weight total)]))
            positive-weights))))

(defn- market-cap-prior
  [universe market-cap-by-coin]
  (let [missing (->> universe
                     (keep (fn [instrument]
                             (let [coin* (coin instrument)
                                   cap (parse-number (get market-cap-by-coin coin*))]
                               (when-not (and (seq coin*)
                                              (positive-number? cap))
                                 coin*))))
                     vec)]
    (when (empty? missing)
      (normalize-weights
       (into {}
             (map (fn [instrument]
                    [(instrument-id instrument)
                     (parse-number (get market-cap-by-coin (coin instrument)))]))
             universe)))))

(defn- current-portfolio-prior
  [universe current-portfolio]
  (normalize-weights
   (into {}
         (map (fn [instrument]
                (let [id (instrument-id instrument)]
                  [id (js/Math.abs
                       (or (parse-number (get-in current-portfolio [:by-instrument id :weight]))
                           0))])))
         universe)))

(defn- equal-weight-prior
  [universe]
  (when (seq universe)
    (let [weight (/ 1 (count universe))]
      (into {}
            (map (fn [instrument]
                   [(instrument-id instrument) weight]))
            universe))))

(defn- missing-market-cap-coins
  [universe market-cap-by-coin]
  (->> universe
       (keep (fn [instrument]
               (let [coin* (coin instrument)
                     cap (parse-number (get market-cap-by-coin coin*))]
                 (when-not (and (seq coin*)
                                (positive-number? cap))
                   coin*))))
       distinct
       vec))

(defn resolve-black-litterman-prior
  [{:keys [universe market-cap-by-coin current-portfolio]}]
  (let [universe* (vec (or universe []))
        market-cap-by-coin* (or market-cap-by-coin {})
        market-cap-weights (market-cap-prior universe* market-cap-by-coin*)
        missing-coins (missing-market-cap-coins universe* market-cap-by-coin*)]
    (if (seq market-cap-weights)
      {:source :market-cap
       :weights-by-instrument market-cap-weights
       :warnings []}
      (let [current-weights (current-portfolio-prior universe* current-portfolio)
            fallback-warning {:code :missing-market-cap-prior
                              :missing-coins missing-coins
                              :fallback (if (seq current-weights)
                                          :current-portfolio
                                          :equal-weight)}]
        (if (seq current-weights)
          {:source :fallback-current-portfolio
           :weights-by-instrument current-weights
           :warnings [fallback-warning]}
          {:source :fallback-equal-weight
           :weights-by-instrument (or (equal-weight-prior universe*) {})
           :warnings [fallback-warning
                      {:code :missing-current-portfolio-prior
                       :fallback :equal-weight}]})))))
