(ns hyperopen.views.account-equity.panels
  (:require [hyperopen.views.account-equity.format :refer [display-currency
                                                            display-leverage
                                                            display-percent
                                                            metric-row]]
            [hyperopen.views.account-equity.funding-actions :refer [funding-actions-section
                                                                     funding-actions-view]]
            [hyperopen.views.account-equity.metrics :refer [account-equity-metrics
                                                            unified-account?]]))

(def ^:private unified-account-ratio-tooltip
  "Represents the risk of portfolio liquidation. When the value is greater than 95%, your portfolio may be liquidated.")

(def ^:private unified-account-leverage-tooltip
  "Unified Account Leverage = Total Cross Positions Value / Total Collateral Balance.")


(defn- classic-account-equity-view [{:keys [spot-equity
                                            perps-value
                                            account-value-display
                                            base-balance
                                            maintenance-margin
                                            cross-margin-ratio
                                            cross-account-leverage
                                            pnl-info
                                            fill-height?
                                            show-funding-actions?
                                            state]}]
  [:div {:class (into ["bg-base-100" "rounded-none" "spectate-none" "p-3" "space-y-4" "w-full"]
                      (when fill-height?
                        ["h-full"]))
         :data-parity-id "account-equity"}
   [:div.text-sm.font-semibold.text-trading-text "Account Equity"]
   (when show-funding-actions?
     (funding-actions-view state))

   [:div.space-y-2
    (metric-row "Account Value" (display-currency account-value-display)
                :tooltip "Total classic account value (Spot + Perps).")
    (metric-row "Spot" (display-currency spot-equity))
    (metric-row "Perps" (display-currency perps-value)
                :tooltip "Balance + Unrealized PNL (approximate account value if all positions were closed)")]

   [:div.border-t.border-base-300.pt-3.space-y-2
    [:div.text-xs.font-semibold.text-trading-text "Perps Overview"]
    (metric-row "Balance" (display-currency base-balance)
                :tooltip "Total Net Transfers + Total Realized Profit + Total Net Funding Fees")
    (metric-row "Unrealized PNL" (:text pnl-info)
                :value-class (:class pnl-info))
    (metric-row "Cross Margin Ratio" (display-percent cross-margin-ratio)
                :tooltip "Maintenance Margin / Portfolio Value. Your cross positions will be liquidated if Margin Ratio reaches 100%.")
    (metric-row "Maintenance Margin" (display-currency maintenance-margin)
                :tooltip "The minimum portfolio value required to keep your cross positions open")
    (metric-row "Cross Account Leverage" (display-leverage cross-account-leverage)
                :tooltip "Cross Account Leverage = Total Cross Positions Value / Cross Account Value.")]])

(defn- unified-account-summary-view [{:keys [unified-account-ratio
                                             account-value-display
                                             maintenance-margin
                                             unified-account-leverage
                                             pnl-info
                                             fill-height?
                                             show-funding-actions?
                                             state]}]
  [:div {:class (into ["bg-base-100" "rounded-none" "spectate-none" "p-3" "space-y-4" "w-full"]
                      (when fill-height?
                        ["h-full"]))
         :data-parity-id "account-equity"}
   (when show-funding-actions?
     (funding-actions-section state))
   [:div.text-sm.font-semibold.text-trading-text "Unified Account Summary"]
   [:div.space-y-2
    (metric-row "Unified Account Value" (display-currency account-value-display)
                :tooltip "Total portfolio value used for unified account risk and leverage calculations.")
    (metric-row "Unified Account Ratio" (display-percent unified-account-ratio)
                :tooltip unified-account-ratio-tooltip)
    (metric-row "Unrealized PNL" (:text pnl-info)
                :value-class (:class pnl-info))
    (metric-row "Perps Maintenance Margin" (display-currency maintenance-margin)
                :tooltip "The minimum portfolio value required to keep your perps positions open.")
    (metric-row "Unified Account Leverage" (display-leverage unified-account-leverage)
                :tooltip unified-account-leverage-tooltip)]])

(defn account-equity-view
  ([state]
   (account-equity-view state {}))
  ([state {:keys [fill-height? show-funding-actions? metrics]
           :or {fill-height? true
                show-funding-actions? true}}]
   (let [metrics* (or metrics
                      (account-equity-metrics state))]
     (if (unified-account? state)
       (unified-account-summary-view (assoc metrics*
                                            :fill-height? fill-height?
                                            :show-funding-actions? show-funding-actions?
                                            :state state))
       (classic-account-equity-view (assoc metrics*
                                           :fill-height? fill-height?
                                           :show-funding-actions? show-funding-actions?
                                           :state state))))))
