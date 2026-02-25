(ns hyperopen.account.history.position-reduce
  (:require [clojure.string :as str]
            [hyperopen.account.history.position-identity :as position-identity]
            [hyperopen.domain.trading :as trading-domain]))

(def ^:private anchor-keys
  [:left :right :top :bottom :width :height :viewport-width :viewport-height])

(defn- parse-num
  [value]
  (trading-domain/parse-num value))

(defn- clamp
  [value min-value max-value]
  (-> value
      (max min-value)
      (min max-value)))

(defn- normalize-display-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- normalize-positive-price-text
  [value]
  (let [price (parse-num value)]
    (when (and (number? price)
               (pos? price))
      (trading-domain/number->clean-string price 6))))

(defn- resolve-mid-price-text
  [position-data]
  (let [position (or (:position position-data) {})]
    (some normalize-positive-price-text
          [(:midPx position)
           (:midPrice position)
           (:midPx position-data)
           (:midPrice position-data)
           (:markPx position)
           (:markPrice position)
           (:markPx position-data)
           (:markPrice position-data)
           (:oraclePx position)
           (:oraclePrice position)
           (:oraclePx position-data)
           (:oraclePrice position-data)
           (:entryPx position)
           (:entryPx position-data)])))

(defn- normalize-anchor
  [anchor]
  (when (map? anchor)
    (let [normalized (reduce (fn [acc k]
                               (if-let [n (parse-num (get anchor k))]
                                 (assoc acc k n)
                                 acc))
                             {}
                             anchor-keys)]
      (when (seq normalized)
        normalized))))

(defn- normalize-close-type
  [value]
  (let [as-keyword (cond
                     (keyword? value) value
                     (string? value) (keyword (str/lower-case (str/trim value)))
                     :else nil)]
    (if (= as-keyword :limit)
      :limit
      :market)))

(defn- percent->input-text
  [percent]
  (if (number? percent)
    (trading-domain/number->clean-string (clamp percent 0 100) 2)
    ""))

(defn default-popover-state
  []
  {:open? false
   :position-key nil
   :anchor nil
   :coin nil
   :dex nil
   :position-side nil
   :position-size 0
   :size-percent-input "100"
   :close-type :market
   :mid-price nil
   :limit-price ""
   :error nil})

(defn open?
  [popover]
  (boolean (:open? popover)))

(defn close-type
  [popover]
  (normalize-close-type (:close-type popover)))

(defn limit-close?
  [popover]
  (= :limit (close-type popover)))

(defn configured-size-percent
  [popover]
  (let [raw (str/trim (str (or (:size-percent-input popover) "")))
        parsed (parse-num raw)]
    (if (number? parsed)
      (clamp parsed 0 100)
      100)))

(defn position-side-label
  [popover]
  (case (:position-side popover)
    :short "Short"
    :long "Long"
    "Position"))

(defn set-popover-field
  [popover path value]
  (let [path* (if (vector? path) path [path])
        value* (str (or value ""))]
    (cond
      (= path* [:size-percent-input])
      (let [text (str/trim value*)
            parsed (parse-num text)]
        (if (str/blank? text)
          (assoc popover :size-percent-input "" :error nil)
          (if (number? parsed)
            (assoc popover :size-percent-input (percent->input-text parsed) :error nil)
            (assoc popover :size-percent-input value* :error nil))))

      (= path* [:limit-price])
      (assoc popover :limit-price value* :error nil)

      (= path* [:close-type])
      (assoc popover :close-type (normalize-close-type value) :error nil)

      :else
      popover)))

(defn set-size-percent
  [popover percent]
  (assoc popover :size-percent-input (percent->input-text percent)
                :error nil))

(defn set-limit-price-to-mid
  [popover]
  (let [mid-price (some-> (:mid-price popover) str str/trim)]
    (if (seq mid-price)
      (assoc popover :limit-price mid-price :error nil)
      (assoc popover :error nil))))

(defn- position-side
  [szi]
  (let [size-num (parse-num szi)]
    (cond
      (and (number? size-num) (neg? size-num)) :short
      (and (number? size-num) (pos? size-num)) :long
      :else :flat)))

(defn- absolute-position-size
  [szi]
  (let [size-num (parse-num szi)]
    (if (number? size-num)
      (js/Math.abs size-num)
      0)))

(defn from-position-row
  ([position-data]
   (from-position-row position-data nil))
  ([position-data anchor]
   (let [position (or (:position position-data) {})
         side (position-side (:szi position))
         size (absolute-position-size (:szi position))]
     (assoc (default-popover-state)
            :open? true
            :position-key (position-identity/position-unique-key position-data)
            :anchor (normalize-anchor anchor)
            :coin (:coin position)
            :dex (normalize-display-text (:dex position-data))
            :position-side side
            :position-size size
            :mid-price (resolve-mid-price-text position-data)
            :size-percent-input (if (pos? size) "100" "0")))))
