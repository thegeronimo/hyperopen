(ns hyperopen.views.account-equity-view
  (:require [hyperopen.views.account-equity.format :as format]
            [hyperopen.views.account-equity.funding-actions :as funding-actions]
            [hyperopen.views.account-equity.metrics :as metrics]
            [hyperopen.views.account-equity.panels :as panels]))

(def display-currency format/display-currency)
(def pnl-display format/pnl-display)
(def tooltip format/tooltip)
(def metric-row format/metric-row)
(def funding-actions-view funding-actions/funding-actions-view)
(def account-equity-metrics metrics/account-equity-metrics)
(def account-equity-hyperevm-funds metrics/account-equity-hyperevm-funds)
(def account-equity-outcome-shares metrics/account-equity-outcome-shares)
(def reset-account-equity-metrics-cache! metrics/reset-account-equity-metrics-cache!)

(defn- token-price-usd
  [balance-row-by-token market-by-key token]
  (metrics/token-price-usd balance-row-by-token market-by-key token))

(def account-equity-view panels/account-equity-view)
