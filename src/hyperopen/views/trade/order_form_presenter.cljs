(ns hyperopen.views.trade.order-form-presenter
  (:require [hyperopen.views.trade.order-form-summary-display :as summary-display]))

(defn format-usdc [value]
  (summary-display/format-usdc value))

(defn format-position-label [position sz-decimals]
  (summary-display/format-position-label position sz-decimals))

(defn format-percent
  ([value]
   (summary-display/format-percent value))
  ([value decimals]
   (summary-display/format-percent value decimals)))

(defn format-currency-or-na [value]
  (summary-display/format-currency-or-na value))

(defn format-trade-price-or-na [value]
  (summary-display/format-trade-price-or-na value))

(defn format-fees [fees]
  (summary-display/format-fees fees))

(defn format-slippage [est max]
  (summary-display/format-slippage est max))

(defn summary-display
  [summary sz-decimals]
  (summary-display/summary-display summary sz-decimals))
