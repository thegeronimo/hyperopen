(ns hyperopen.views.account-surfaces-module
  (:require [hyperopen.views.account-equity-view :as account-equity]
            [hyperopen.views.account-equity.hyperevm-line :as hyperevm-line]
            [hyperopen.views.account-info-view :as account-info]))

(defn ^:export account-info-view
  ([state]
   (account-info/account-info-view state))
  ([state opts]
   (account-info/account-info-view state opts)))

(defn ^:export account-equity-view
  ([state]
   (account-equity/account-equity-view state))
  ([state opts]
   (account-equity/account-equity-view state opts)))

(defn ^:export account-equity-metrics
  [state]
  (account-equity/account-equity-metrics state))

(defn ^:export hyperevm-line-model
  [state]
  (hyperevm-line/hyperevm-line-model state))

(defn ^:export funding-actions-view
  ([state]
   (account-equity/funding-actions-view state))
  ([state opts]
   (account-equity/funding-actions-view state opts)))

(goog/exportSymbol "hyperopen.views.account_surfaces_module.account_info_view" account-info-view)
(goog/exportSymbol "hyperopen.views.account_surfaces_module.account_equity_view" account-equity-view)
(goog/exportSymbol "hyperopen.views.account_surfaces_module.account_equity_metrics" account-equity-metrics)
(goog/exportSymbol "hyperopen.views.account_surfaces_module.hyperevm_line_model" hyperevm-line-model)
(goog/exportSymbol "hyperopen.views.account_surfaces_module.funding_actions_view" funding-actions-view)
