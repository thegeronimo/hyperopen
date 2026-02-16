(ns hyperopen.views.account-equity-view
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.projections :as account-projections]
            [hyperopen.utils.formatting :as fmt]))

(defn parse-num [value]
  (cond
    (number? value) value
    (string? value) (let [s (str/trim value)
                          n (js/parseFloat s)]
                      (when (and (not (str/blank? s)) (not (js/isNaN n))) n))
    :else nil))

(defn safe-div [num denom]
  (when (and (number? num) (number? denom) (not (zero? denom)))
    (/ num denom)))

(defn display-currency [value]
  (if (number? value)
    (fmt/format-currency value)
    "--"))

(defn display-percent [ratio]
  (if (number? ratio)
    (str (fmt/safe-to-fixed (* ratio 100) 2) "%")
    "--"))

(defn display-leverage [ratio]
  (if (number? ratio)
    (str (fmt/safe-to-fixed ratio 2) "x")
    "--"))

(defn pnl-display [value]
  (if (number? value)
    (let [formatted (fmt/format-currency (js/Math.abs value))
          sign (cond
                 (pos? value) "+"
                 (neg? value) "-"
                 :else "")]
      {:text (str sign formatted)
       :class (cond
                (pos? value) "text-success"
                (neg? value) "text-error"
                :else "text-trading-text")})
    {:text "--" :class "text-trading-text-secondary"}))

(defn tooltip [trigger text & [position]]
  (let [pos (or position "top")]
    [:div.relative.inline-flex.group
     trigger
     [:div {:class (into ["absolute" "opacity-0" "group-hover:opacity-100" "transition-opacity" "duration-200"
                          "pointer-events-none" "z-50"]
                         (case pos
                           "top" ["bottom-full" "left-0" "mb-2"]
                           "bottom" ["top-full" "left-0" "mt-2"]
                           "left" ["right-full" "top-1/2" "-translate-y-1/2" "mr-2"]
                           "right" ["left-full" "top-1/2" "-translate-y-1/2" "ml-2"]))
            :style {:max-width "520px" :min-width "300px"}}
      [:div.bg-gray-800.text-gray-100.text-xs.rounded-md.px-3.py-2.shadow-lg.leading-snug.whitespace-normal
       text
       [:div {:class (into ["absolute" "w-0" "h-0" "border-4" "border-transparent"]
                           (case pos
                             "top" ["top-full" "left-3" "border-t-gray-800"]
                             "bottom" ["bottom-full" "left-3" "border-b-gray-800"]
                             "left" ["left-full" "top-1/2" "-translate-y-1/2" "border-l-gray-800"]
                             "right" ["right-full" "top-1/2" "-translate-y-1/2" "border-r-gray-800"]))}]]]]))

(defn label-with-tooltip [label tooltip-text]
  (tooltip
    [:span.text-sm.text-trading-text-secondary.border-b.border-dashed.border-gray-600.cursor-help
     label]
    tooltip-text
    "top"))

(defn default-metric-value-class [value]
  (if (= value "--")
    "text-trading-text-secondary"
    "text-trading-text"))

(defn metric-row [label value & {:keys [tooltip value-class]}]
  [:div.flex.items-center.justify-between.text-sm
   (if tooltip
     (label-with-tooltip label tooltip)
     [:span.text-sm.text-trading-text-secondary label])
   [:span {:class ["num" (or value-class (default-metric-value-class value))]}
    value]])

(defn- unified-account? [state]
  (= :unified (get-in state [:account :mode])))

(defn- derive-account-equity-metrics [state]
  (let [webdata2 (:webdata2 state)
        clearinghouse-state (:clearinghouseState webdata2)
        margin-summary (:marginSummary clearinghouse-state)
        cross-summary (:crossMarginSummary clearinghouse-state)
        perps-summary (or margin-summary cross-summary {})
        cross-summary (or cross-summary perps-summary {})
        account-value (parse-num (:accountValue perps-summary))
        total-raw-usd (parse-num (:totalRawUsd perps-summary))
        total-ntl-pos (parse-num (:totalNtlPos perps-summary))
        cross-account-value (or (parse-num (:accountValue cross-summary)) account-value)
        cross-total-ntl-pos (or (parse-num (:totalNtlPos cross-summary)) total-ntl-pos)
        cross-total-margin-used (parse-num (:totalMarginUsed cross-summary))
        maintenance-margin (parse-num (:crossMaintenanceMarginUsed clearinghouse-state))
        market-by-key (get-in state [:asset-selector :market-by-key] {})
        balance-rows (account-projections/build-balance-rows webdata2 (:spot state) (:account state) market-by-key)
        perps-row (first (filter #(= "perps-usdc" (:key %)) balance-rows))
        perps-row-balance (parse-num (:total-balance perps-row))
        positions (account-projections/collect-positions webdata2 (:perp-dex-clearinghouse state))
        unrealized-from-positions (let [vals (keep #(parse-num (get-in % [:position :unrealizedPnl])) positions)]
                                    (when (seq vals)
                                      (reduce + vals)))
        fallback-balance (or total-raw-usd perps-row-balance)
        cross-derived-balance (when (and (number? cross-account-value)
                                         (number? cross-total-margin-used)
                                         (number? cross-total-ntl-pos))
                                (+ cross-account-value cross-total-margin-used cross-total-ntl-pos))
        base-balance (or cross-derived-balance fallback-balance)
        unrealized-from-summary (when (and (number? account-value) (number? fallback-balance))
                                  (- account-value fallback-balance))
        unrealized-pnl (or unrealized-from-positions unrealized-from-summary)
        perps-value (cond
                      (and (number? base-balance) (number? unrealized-pnl))
                      (+ base-balance unrealized-pnl)
                      (number? account-value) account-value
                      :else nil)
        spot-values (keep (fn [row]
                            (when-not (= "perps-usdc" (:key row))
                              (parse-num (:usdc-value row))))
                          balance-rows)
        spot-equity (when (seq spot-values) (reduce + spot-values))
        portfolio-value (account-projections/portfolio-usdc-value balance-rows)
        cross-margin-ratio (safe-div maintenance-margin cross-account-value)
        unified-account-ratio (safe-div maintenance-margin portfolio-value)
        cross-account-leverage (safe-div cross-total-ntl-pos cross-account-value)
        unified-account-leverage (safe-div cross-total-ntl-pos portfolio-value)
        pnl-info (pnl-display unrealized-pnl)]
    {:spot-equity spot-equity
     :perps-value perps-value
     :base-balance base-balance
     :unrealized-pnl unrealized-pnl
     :cross-margin-ratio cross-margin-ratio
     :unified-account-ratio unified-account-ratio
     :maintenance-margin maintenance-margin
     :cross-account-leverage cross-account-leverage
     :unified-account-leverage unified-account-leverage
     :cross-account-value cross-account-value
     :portfolio-value portfolio-value
     :pnl-info pnl-info}))

(defn- classic-account-equity-view [{:keys [spot-equity
                                            perps-value
                                            base-balance
                                            maintenance-margin
                                            cross-margin-ratio
                                            cross-account-leverage
                                            pnl-info]}]
  [:div {:class ["bg-base-100" "rounded-none" "shadow-none" "p-3" "space-y-4" "w-full" "h-full"]
         :data-parity-id "account-equity"}
   [:div.text-sm.font-semibold.text-trading-text "Account Equity"]

   [:div.space-y-2
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
                                             portfolio-value
                                             maintenance-margin
                                             unified-account-leverage
                                             pnl-info]}]
  [:div {:class ["bg-base-100" "rounded-none" "shadow-none" "p-3" "space-y-4" "w-full" "h-full"]
         :data-parity-id "account-equity"}
   [:div.text-sm.font-semibold.text-trading-text "Unified Account Summary"]

   [:div.space-y-2
    (metric-row "Unified Account Ratio" (display-percent unified-account-ratio)
                :tooltip "Perps Maintenance Margin / Portfolio Value.")
    (metric-row "Portfolio Value" (display-currency portfolio-value)
                :tooltip "Total portfolio value used for unified account risk and leverage calculations.")
    (metric-row "Unrealized PNL" (:text pnl-info)
                :value-class (:class pnl-info))
    (metric-row "Perps Maintenance Margin" (display-currency maintenance-margin)
                :tooltip "The minimum portfolio value required to keep your perps positions open.")
    (metric-row "Unified Account Leverage" (display-leverage unified-account-leverage)
                :tooltip "Total Perps Positions Value / Portfolio Value.")]])

(defn account-equity-view [state]
  (let [metrics (derive-account-equity-metrics state)]
    (if (unified-account? state)
      (unified-account-summary-view metrics)
      (classic-account-equity-view metrics))))
