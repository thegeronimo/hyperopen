(ns hyperopen.views.trade.order-form-summary-display
  (:require [hyperopen.utils.formatting :as fmt]))

(defn format-usdc [value]
  (if (and (number? value) (not (js/isNaN value)))
    (str (fmt/format-fixed-number value 2)
         " USDC")
    "N/A"))

(defn format-position-label [position sz-decimals]
  (let [size (:abs-size position)
        coin (:coin position)]
    (if (and (number? size) (pos? size) (seq coin))
      (str (fmt/format-fixed-number size (or sz-decimals 4))
           " "
           coin)
      (str "0.0000 " (or coin "--")))))

(defn format-percent
  ([value]
   (format-percent value 2))
  ([value decimals]
   (if (and (number? value) (not (js/isNaN value)))
     (str (fmt/safe-to-fixed value decimals) "%")
     "N/A")))

(defn format-currency-or-na [value]
  (if (number? value)
    (or (fmt/format-currency value) "N/A")
    "N/A"))

(defn format-trade-price-or-na [value]
  (if (number? value)
    (or (fmt/format-trade-price value) "N/A")
    "N/A"))

(defn- format-fee-line [fees]
  (when (and (map? fees)
             (number? (:taker fees))
             (number? (:maker fees)))
    (str (fmt/safe-to-fixed (:taker fees) 4)
         "% / "
         (fmt/safe-to-fixed (:maker fees) 4)
         "%")))

(defn format-fees [fees]
  (let [effective (or (format-fee-line (:effective fees))
                      (format-fee-line fees))]
    {:effective (or effective "N/A")
     :baseline (format-fee-line (:baseline fees))}))

(defn format-slippage [est max]
  (str "Est " (format-percent est 4)
       " / Max " (format-percent max 2)))

(defn summary-display
  [summary sz-decimals]
  {:available-to-trade (format-usdc (:available-to-trade summary))
   :current-position (format-position-label (:current-position summary) sz-decimals)
   :liquidation-price (format-trade-price-or-na (:liquidation-price summary))
   :order-value (format-currency-or-na (:order-value summary))
   :margin-required (format-currency-or-na (:margin-required summary))
   :slippage (format-slippage (:slippage-est summary) (:slippage-max summary))
   :fees (format-fees (:fees summary))})
