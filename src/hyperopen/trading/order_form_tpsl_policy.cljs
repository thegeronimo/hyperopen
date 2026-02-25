(ns hyperopen.trading.order-form-tpsl-policy
  (:require [clojure.string :as str]
            [hyperopen.domain.trading :as trading-domain]))

(def default-unit :usd)
(def trigger-price-decimals 5)
(def valid-units #{:usd :roe-percent :position-percent})

(defn parse-num [value]
  (trading-domain/parse-num value))

(defn- positive-number? [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)
       (pos? value)))

(defn normalize-unit [value]
  (let [candidate (cond
                    (keyword? value) value
                    (string? value) (keyword (str/lower-case (str/trim value)))
                    (true? value) :usd
                    (false? value) :roe-percent
                    :else default-unit)]
    (cond
      (contains? valid-units candidate) candidate
      ;; Legacy compatibility: :percent was historically ROE-based.
      (= candidate :percent) :roe-percent
      :else default-unit)))

(defn unit-symbol [unit]
  (case (normalize-unit unit)
    :roe-percent "%(E)"
    :position-percent "%(P)"
    "$"))

(defn unit-menu-label
  [unit]
  (case (normalize-unit unit)
    :roe-percent "%(E): percent of margin/equity used (ROE)."
    :position-percent "%(P): percent of position value (notional)."
    "$: profit/loss in USDC."))

(defn trigger-present? [value]
  (boolean (seq (str/trim (str (or value ""))))))

(defn baseline-price
  [form pricing-policy limit-like?]
  (let [entered-price (parse-num (:price form))
        mid-price (parse-num (:mid-price pricing-policy))]
    (if (and limit-like? (positive-number? entered-price))
      entered-price
      mid-price)))

(defn inverse-for-leg [side leg]
  (case leg
    :tp (not= side :buy)
    :sl (= side :buy)
    false))

(defn offset-input-ready?
  [{:keys [unit baseline size leverage]}]
  (let [unit* (normalize-unit unit)]
    (and (positive-number? baseline)
         (case unit*
           :usd (positive-number? size)
           :roe-percent (positive-number? leverage)
           :position-percent true
           false))))

(defn- round-floor-2 [value]
  ;; Small epsilon avoids float artifacts (for example 0.999999999 -> 1.00).
  (/ (js/Math.floor (+ (* 100 value) 0.0001)) 100))

(defn offset-value-from-trigger
  [{:keys [trigger baseline size leverage inverse unit]}]
  (let [unit* (normalize-unit unit)
        trigger-num (parse-num trigger)]
    (when (and (positive-number? baseline)
               (positive-number? trigger-num))
      (let [delta (- trigger-num baseline)
            raw-offset (case unit*
                         :usd
                         (when (positive-number? size)
                           (* size delta))

                         :roe-percent
                         (when (positive-number? leverage)
                           (* (/ delta baseline) leverage 100))

                         :position-percent
                         (* (/ delta baseline) 100)

                         nil)
            signed-offset (if (number? raw-offset)
                            (if inverse (- raw-offset) raw-offset)
                            nil)]
        (when (number? signed-offset)
          (round-floor-2 signed-offset))))))

(defn offset-display-from-trigger
  [{:keys [trigger baseline size leverage inverse unit]}]
  (let [offset (offset-value-from-trigger {:trigger trigger
                                           :baseline baseline
                                           :size size
                                           :leverage leverage
                                           :inverse inverse
                                           :unit unit})]
    (if (number? offset)
      (trading-domain/number->clean-string offset 2)
      "")))

(defn trigger-from-offset-input
  [{:keys [raw-input baseline size leverage inverse unit]}]
  (let [raw-text (str/trim (str (or raw-input "")))
        unit* (normalize-unit unit)]
    (if (str/blank? raw-text)
      ""
      (let [offset (parse-num raw-text)]
        (if (or (not (number? offset))
                (not (positive-number? baseline)))
          ""
          (let [price (case unit*
                        :usd
                        (when (positive-number? size)
                          (let [delta (/ offset size)]
                            (if inverse
                              (- baseline delta)
                              (+ baseline delta))))

                        :roe-percent
                        (when (positive-number? leverage)
                          (let [delta (/ offset leverage 100)]
                            (if inverse
                              (* baseline (- 1 delta))
                              (* baseline (+ 1 delta)))))

                        :position-percent
                        (let [delta (/ offset 100)]
                          (if inverse
                            (* baseline (- 1 delta))
                            (* baseline (+ 1 delta))))

                        nil)]
            (if (and (number? price)
                     (positive-number? price))
              (trading-domain/number->clean-string price trigger-price-decimals)
              "")))))))

(defn offset-display
  [{:keys [offset-input trigger baseline size leverage inverse unit]}]
  (let [raw-text (str (or offset-input ""))
        raw-trigger (trigger-from-offset-input {:raw-input raw-text
                                                :baseline baseline
                                                :size size
                                                :leverage leverage
                                                :inverse inverse
                                                :unit unit})
        trigger-text (str/trim (str (or trigger "")))]
    (if (and (seq (str/trim raw-text))
             (or (not (seq raw-trigger))
                 (str/blank? trigger-text)
                 (= raw-trigger trigger-text)))
      raw-text
      (offset-display-from-trigger {:trigger trigger
                                    :baseline baseline
                                    :size size
                                    :leverage leverage
                                    :inverse inverse
                                    :unit unit}))))
