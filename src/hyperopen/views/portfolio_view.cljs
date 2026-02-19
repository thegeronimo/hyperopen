(ns hyperopen.views.portfolio-view
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.portfolio.vm :as portfolio-vm]))

(def ^:private compact-currency-formatter
  (js/Intl.NumberFormat.
   "en-US"
   #js {:notation "compact"
        :maximumFractionDigits 1}))

(def ^:private action-items
  [{:label "Link Staking"
    :action [:actions/navigate "/staking"]}
   {:label "Swap Stablecoins"
    :action [:actions/navigate "/trade"]}
   {:label "Perps ↔ Spot"
    :action [:actions/navigate "/trade"]}
   {:label "EVM ↔ Core"
    :action [:actions/navigate "/trade"]}
   {:label "Portfolio Margin"
    :action [:actions/navigate "/portfolio"]}
   {:label "Send"
    :action [:actions/set-funding-modal :send]}
   {:label "Withdraw"
    :action [:actions/set-funding-modal :withdraw]}
   {:label "Deposit"
    :primary? true
    :action [:actions/set-funding-modal :deposit]}])

(defn- format-currency [value]
  (or (fmt/format-currency value)
      "$0.00"))

(defn- format-compact-currency [value]
  (let [n (if (number? value) value 0)]
    (str "$" (.format compact-currency-formatter n))))

(defn- format-fee-pct [pct]
  (let [n (if (number? pct) pct 0)]
    (str (.toFixed n 3) "%")))

(defn- format-percent [pct]
  (let [n (if (number? pct) pct 0)]
    (str (.toFixed n 2) "%")))

(defn- action-button [{:keys [label action primary?]}]
  [:button {:type "button"
            :class (into ["btn" "btn-sm" "rounded-lg" "border" "border-base-300" "bg-base-100" "text-trading-text-secondary" "hover:text-trading-text" "hover:bg-base-200"]
                         (when primary?
                           ["bg-[#1f5b55]" "text-trading-text" "hover:bg-[#267067]"]))
            :on {:click [action]}}
   label])

(defn- summary-row [label value & [value-class]]
  [:div {:class ["grid" "grid-cols-[1fr_auto]" "items-center" "gap-3"]}
   [:span {:class ["text-sm" "text-trading-text-secondary"]}
    label]
   [:span {:class (into ["num" "text-sm" "text-trading-text"] (or value-class []))}
    value]])

(defn- pnl-summary [pnl]
  (let [n (if (number? pnl) pnl 0)
        color-class (cond
                      (pos? n) "text-success"
                      (neg? n) "text-error"
                      :else "text-trading-text")]
    {:value (str (cond
                   (pos? n) "+"
                   (neg? n) "-"
                   :else "")
                (format-currency (js/Math.abs n)))
     :class [color-class]}))

(defn- section-card [data-role & children]
  (into [:div {:class ["rounded-xl"
                       "border"
                       "border-base-300"
                       "bg-base-100/95"
                       "overflow-hidden"]
               :data-role data-role}]
        children))

(defn- summary-card [{:keys [summary]}]
  (let [pnl-info (pnl-summary (:pnl summary))]
    (section-card
     "portfolio-account-summary-card"
     [:div {:class ["flex" "items-center" "justify-between" "border-b" "border-base-300" "px-4" "py-3"]}
      [:button {:class ["btn" "btn-xs" "btn-ghost" "font-normal" "text-trading-text"]}
       "Perps + Spot + Vaults"]
      [:button {:class ["btn" "btn-xs" "btn-ghost" "font-normal" "text-trading-text"]}
       "30D"]]
     [:div {:class ["space-y-2.5" "px-4" "py-3"]}
      (summary-row "PNL" (:value pnl-info) (:class pnl-info))
      (summary-row "Volume" (format-currency (:volume summary)))
      (summary-row "Max Drawdown" (format-percent (:max-drawdown-pct summary)))
      (summary-row "Total Equity" (format-currency (:total-equity summary)))
      (summary-row "Perps Account Equity" (format-currency (:perps-account-equity summary)))
      (summary-row "Spot Account Equity" (format-currency (:spot-account-equity summary)))
      (summary-row "Earn Balance" (format-currency (:earn-balance summary)))])))

(defn- chart-card []
  (section-card
   "portfolio-chart-card"
   [:div {:class ["grid" "grid-cols-[auto_auto_1fr]" "items-center" "border-b" "border-base-300"]}
    [:button {:class ["border-b" "border-base-300" "px-4" "py-3" "text-sm" "text-trading-text-secondary"]}
     "Account Value"]
    [:button {:class ["border-b-2" "border-primary" "px-4" "py-3" "text-sm" "text-trading-text"]}
     "PNL"]
    [:div {:class ["border-b" "border-base-300" "h-full"]}]]
   [:div {:class ["h-[182px]" "px-4" "py-3" "relative"]
          :data-role "portfolio-chart-shell"}
    [:div {:class ["absolute" "left-4" "right-4" "bottom-7" "border-b" "border-base-300"]}]
    [:div {:class ["absolute" "left-4" "top-5" "bottom-7" "border-l" "border-base-300"]}]
    [:div {:class ["absolute" "left-0" "top-4" "text-xs" "text-trading-text-secondary"]} "3"]
    [:div {:class ["absolute" "left-0" "top-[35%]" "text-xs" "text-trading-text-secondary"]} "2"]
    [:div {:class ["absolute" "left-0" "top-[56%]" "text-xs" "text-trading-text-secondary"]} "1"]
    [:div {:class ["absolute" "left-0" "bottom-6" "text-xs" "text-trading-text-secondary"]} "0"]]))

(defn- metric-cards [{:keys [volume-14d-usd fees]}]
  [:div {:class ["grid" "grid-cols-1" "gap-3" "md:grid-cols-2" "xl:grid-cols-1"]}
   (section-card
    "portfolio-14d-volume-card"
    [:div {:class ["space-y-3" "px-4" "py-3"]}
     [:div {:class ["text-sm" "text-trading-text-secondary"]}
      "14 Day Volume"]
     [:div {:class ["num" "text-4xl" "font-medium" "text-trading-text"]}
      (format-compact-currency volume-14d-usd)]
     [:button {:class ["btn" "btn-xs" "btn-ghost" "justify-start" "px-0" "text-trading-green" "hover:bg-transparent"]}
      "View Volume"]])
   (section-card
    "portfolio-fees-card"
    [:div {:class ["space-y-3" "px-4" "py-3"]}
     [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
      [:span {:class ["text-sm" "text-trading-text-secondary"]}
       "Fees (Taker / Maker)"]
      [:button {:class ["btn" "btn-ghost" "btn-xs" "text-trading-text"]}
       "Perps"]]
     [:div {:class ["num" "text-4xl" "font-medium" "leading-tight" "text-trading-text"]}
      (str (format-fee-pct (:taker fees)) " / " (format-fee-pct (:maker fees)))]
     [:button {:class ["btn" "btn-xs" "btn-ghost" "justify-start" "px-0" "text-trading-green" "hover:bg-transparent"]}
      "View Fee Schedule"]])])

(defn- top-banner []
  [:div {:class ["rounded-lg" "bg-[#b3002f]" "px-3" "py-2" "text-xs" "font-medium" "text-white"]}
   "You are accessing our products and services from a restricted jurisdiction. If this is an error, please refresh the page or contact support."])

(defn- header-actions []
  [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-3"]}
   [:h1 {:class ["text-5xl" "font-medium" "tracking-tight" "text-trading-text"]}
    "Portfolio"]
   [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]
          :data-role "portfolio-actions-row"}
    (for [{:keys [label] :as item} action-items]
      ^{:key label}
      (action-button item))]])

(defn portfolio-view [state]
  (let [view-model (portfolio-vm/portfolio-vm state)]
    [:div {:class ["flex-1"
                   "min-h-0"
                   "overflow-y-auto"
                   "app-shell-gutter"
                   "py-4"
                   "space-y-4"
                   "md:py-5"]
           :style {:background-image "radial-gradient(circle at 15% 0%, rgba(0, 212, 170, 0.10), transparent 35%), radial-gradient(circle at 85% 100%, rgba(0, 212, 170, 0.08), transparent 40%)"}
           :data-parity-id "portfolio-root"}
     (top-banner)
     (header-actions)
     [:div {:class ["grid"
                    "grid-cols-1"
                    "gap-3"
                    "xl:grid-cols-[320px_minmax(340px,1fr)_minmax(420px,1.35fr)]"]}
      (metric-cards view-model)
      (summary-card view-model)
      (chart-card)]
     [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "overflow-hidden"]
            :data-role "portfolio-account-table"}
      (account-info-view/account-info-view state)]]))
