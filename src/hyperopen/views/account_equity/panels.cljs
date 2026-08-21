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
  "Represents the risk of portfolio liquidation. When the value is greater than 95%, your portfolio may be liquidated. Cross positions only: isolated positions liquidate one at a time and cannot take the portfolio with them, so this reads -- when every position is isolated.")

(def ^:private unified-account-leverage-tooltip
  "Unified Account Leverage = Total Cross Positions Value / Total Collateral Balance. Isolated positions are excluded: each carries its own margin and liquidates on its own.")

(def ^:private unified-maintenance-margin-tooltip
  "The minimum portfolio value required to keep your cross positions open")

(def ^:private unified-portfolio-value-tooltip
  "Total value of your spot holdings at current prices.")

;; The venue turns the ratio red at 80%, well before the 95% at which a
;; portfolio may actually be liquidated, so the colour is a warning rather
;; than a verdict.
(def ^:private unified-ratio-alert-threshold 0.8)

(defn- unified-ratio-gauge-icon
  [alert?]
  [:svg {:viewBox "0 0 20 20"
         :fill "none"
         :stroke "currentColor"
         :stroke-width "1.8"
         :class ["h-3.5" "w-3.5" "shrink-0"]
         :data-role (if alert?
                      "unified-account-ratio-gauge-alert"
                      "unified-account-ratio-gauge")}
   [:path {:stroke-linecap "round"
           :d "M3.5 14.5a7 7 0 1 1 13 0"}]
   [:path {:stroke-linecap "round"
           :d (if alert? "M10 14.5l4.1-4.1" "M10 14.5l-4.1-4.1")}]])

(defn- unified-ratio-value
  "Ratio with the venue's gauge glyph and its green/red split. A ratio that
   could not be derived stays a plain \"--\": there is no reading to colour."
  [ratio]
  (if (number? ratio)
    (let [alert? (>= ratio unified-ratio-alert-threshold)]
      [:span {:class ["inline-flex" "items-center" "gap-1"
                      (if alert? "text-error" "text-success")]}
       (unified-ratio-gauge-icon alert?)
       [:span (display-percent ratio)]])
    (display-percent ratio)))

(defn- unified-isolated-notional-note
  "Names the exposure the cross-only rows above leave out. Without it an
   all-isolated book renders a bare 0.00x and $0.00 next to a real position,
   which reads as \"no exposure\" rather than \"measured on a different lens\".

   The slot is always rendered and merely hidden when there is nothing to say.
   A conditional child that appears and disappears leaves a nil hole in
   Replicant's child walk, which has previously misaligned sibling updates."
  [isolated-notional]
  (let [show? (and (number? isolated-notional)
                   (pos? isolated-notional))]
    [:div {:class (cond-> ["flex" "justify-end" "text-xs" "text-trading-text-secondary"]
                    (not show?) (conj "hidden"))
           :data-role (when show? "unified-isolated-notional-note")}
     (if show?
       (str "Excludes " (display-currency isolated-notional) " isolated")
       "")]))

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
                                             isolated-notional
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
   ;; Row order follows the venue's own panel so the two can be read side by
   ;; side without hunting: ratio, portfolio value, PNL, maintenance, leverage.
   [:div.space-y-2
    (metric-row "Unified Account Ratio" (unified-ratio-value unified-account-ratio)
                :tooltip unified-account-ratio-tooltip)
    (metric-row "Portfolio Value" (display-currency account-value-display)
                :tooltip unified-portfolio-value-tooltip)
    (metric-row "Unrealized PNL" (:text pnl-info)
                :value-class (:class pnl-info))
    (metric-row "Perps Maintenance Margin" (display-currency maintenance-margin)
                :tooltip unified-maintenance-margin-tooltip)
    (metric-row "Unified Account Leverage" (display-leverage unified-account-leverage)
                :tooltip unified-account-leverage-tooltip)
    (unified-isolated-notional-note isolated-notional)]])

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
