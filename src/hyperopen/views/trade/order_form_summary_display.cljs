(ns hyperopen.views.trade.order-form-summary-display
  (:require [hyperopen.trading.order-form-display :as display]))

(def format-usdc display/format-usdc)
(def format-position-label display/format-position-label)
(def format-percent display/format-percent)
(def format-currency-or-na display/format-currency-or-na)
(def format-trade-price-or-na display/format-trade-price-or-na)
(def format-fees display/format-fees)
(def format-slippage display/format-slippage)
(def summary-display display/summary-display)
