(ns hyperopen.account.history.position-reduce
  (:require [clojure.string :as str]
            [hyperopen.account.history.position-identity :as position-identity]
            [hyperopen.api.gateway.orders.commands :as order-commands]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.domain.trading :as trading-domain]
            [hyperopen.utils.parse :as parse-utils]))

(def ^:private anchor-keys
  [:left :right :top :bottom :width :height :viewport-width :viewport-height])

(defn- parse-num
  [value]
  (trading-domain/parse-num value))

(defn- parse-popover-num
  [popover value]
  (or (parse-utils/parse-localized-decimal value (:locale popover))
      (parse-num value)))

(defn- parse-int-value
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseInt value 10)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num)))
      (js/Math.floor num))))

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
  (let [anchor* (cond
                  (map? anchor) anchor
                  (some? anchor) (js->clj anchor :keywordize-keys true)
                  :else nil)]
    (when (map? anchor*)
      (let [normalized (reduce (fn [acc k]
                                 (if-let [n (parse-num (get anchor* k))]
                                   (assoc acc k n)
                                   acc))
                               {}
                               anchor-keys)]
        (when (seq normalized)
          normalized)))))

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
   :locale nil
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
        parsed (parse-popover-num popover raw)]
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
            parsed (parse-popover-num popover text)]
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

(defn- positive-price
  ([value]
   (let [price (parse-num value)]
     (when (and (number? price)
                (pos? price))
       price)))
  ([popover value]
   (let [price (parse-popover-num popover value)]
     (when (and (number? price)
                (pos? price))
       price))))

(defn- popover-limit-price
  [popover]
  (positive-price popover (:limit-price popover)))

(defn- position-open-side
  [position-side]
  (case position-side
    :short :sell
    :buy))

(defn- reduce-order-side
  [popover]
  (trading-domain/opposite-side (position-open-side (:position-side popover))))

(defn- active-close-size
  [popover]
  (let [position-size (parse-num (:position-size popover))
        size-percent (configured-size-percent popover)]
    (when (and (number? position-size)
               (pos? position-size)
               (number? size-percent)
               (pos? size-percent))
      (* position-size
         (/ size-percent 100)))))

(defn- candidate-market?
  [market coin dex]
  (let [coin* (normalize-display-text coin)
        dex* (normalize-display-text dex)
        market-coin* (normalize-display-text (:coin market))
        market-dex* (normalize-display-text (:dex market))]
    (and (= :perp (:market-type market))
         (= coin* market-coin*)
         (= (or dex* "")
            (or market-dex* "")))))

(defn- resolve-market-by-coin-and-dex
  [market-by-key coin dex]
  (let [markets* (vals (or market-by-key {}))
        exact (some #(when (candidate-market? % coin dex) %) markets*)
        fallback (markets/resolve-market-by-coin market-by-key coin)]
    (or exact fallback)))

(defn- resolve-market-asset-id
  [market]
  (or (some parse-int-value
            [(:asset-id market)
             (:assetId market)])
      (let [idx (parse-int-value (:idx market))
            dex (normalize-display-text (:dex market))]
        (when (and (number? idx)
                   (or (nil? dex) (= "" dex)))
          idx))))

(defn- resolve-market-price
  [popover market]
  (some positive-price
        [(:mid-price popover)
         (:limit-price popover)
         (:midPx market)
         (:mid-px market)
         (:mid-price market)
         (:mark market)
         (:markPx market)
         (:mark-px market)
         (:markRaw market)
         (:markPrice market)
         (:oraclePx market)
         (:oracle-px market)
         (:oracle-price market)]))

(defn- submit-price
  [popover market]
  (if (limit-close? popover)
    (popover-limit-price popover)
    (resolve-market-price popover market)))

(defn validate-popover
  [popover]
  (let [popover-open? (open? popover)
        close-size (active-close-size popover)
        close-side (reduce-order-side popover)
        limit-price (popover-limit-price popover)]
    (cond
      (not popover-open?)
      {:is-ok false
       :display-message "Place Order"}

      (not (contains? #{:buy :sell} close-side))
      {:is-ok false
       :display-message "Place Order"}

      (or (nil? close-size) (<= close-size 0))
      {:is-ok false
       :display-message "Size must be greater than 0."}

      (and (limit-close? popover)
           (nil? limit-price))
      {:is-ok false
       :display-message "Price is required for limit orders."}

      :else
      {:is-ok true
       :display-message "Place Order"})))

(defn- submit-form
  [popover market]
  {:type (close-type popover)
   :side (reduce-order-side popover)
   :size (active-close-size popover)
   :price (submit-price popover market)
   :reduce-only true})

(defn prepare-submit
  [state popover]
  (let [validation (validate-popover popover)
        market-by-key (get-in state [:asset-selector :market-by-key] {})
        market (resolve-market-by-coin-and-dex market-by-key
                                               (:coin popover)
                                               (:dex popover))
        asset-id (resolve-market-asset-id market)
        form (submit-form popover market)
        request (when (number? asset-id)
                  (order-commands/build-order-request
                   {:active-asset (:coin popover)
                    :asset-idx asset-id
                    :market market}
                   form))]
    (cond
      (not (:is-ok validation))
      {:ok? false
       :display-message (:display-message validation)}

      (not (number? asset-id))
      {:ok? false
       :display-message "Select an asset and ensure market data is loaded."}

      (and (= :market (close-type popover))
           (nil? (:price form)))
      {:ok? false
       :display-message "Market price unavailable. Try again after market data is loaded."}

      (nil? request)
      {:ok? false
       :display-message "Place Order"}

      :else
      {:ok? true
       :display-message (:display-message validation)
       :request {:action (:action request)}})))

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
