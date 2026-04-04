(ns hyperopen.views.portfolio.header
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.views.ui.focus-return :as focus-return]
            [hyperopen.wallet.core :as wallet]))

(def ^:private action-items
  [{:label "Link Staking"
    :mobile-label "Staking"
    :data-role "portfolio-action-link-staking"
    :action [:actions/navigate "/staking"]}
   {:label "Swap Stablecoins"
    :mobile-label "Swap"
    :data-role "portfolio-action-swap-stablecoins"
    :action [:actions/navigate "/trade"]}
   {:label "Perps ↔ Spot"
    :mobile-label "Perp Spot"
    :data-role "portfolio-action-perps-spot"
    :action [:actions/navigate "/trade"]}
   {:label "EVM ↔ Core"
    :mobile-label "EVM Core"
    :data-role "portfolio-action-evm-core"
    :action [:actions/navigate "/trade"]}
   {:label "Portfolio Margin"
    :mobile-label "PM"
    :data-role "portfolio-action-portfolio-margin"
    :action [:actions/navigate "/portfolio"]}
   {:label "Send"
    :data-role "portfolio-action-send"
    :action [:actions/open-funding-transfer-modal
             :event.currentTarget/bounds
             :event.currentTarget/data-role]}
   {:label "Withdraw"
    :data-role "portfolio-action-withdraw"
    :action [:actions/open-funding-withdraw-modal
             :event.currentTarget/bounds
             :event.currentTarget/data-role]}
   {:label "Deposit"
    :primary? true
    :data-role "portfolio-action-deposit"
    :action [:actions/open-funding-deposit-modal
             :event.currentTarget/bounds
             :event.currentTarget/data-role]}])

(defn action-button [{:keys [label mobile-label action primary? data-role focus-request]}]
  [:button (merge
            {:type "button"
             :class (into ["btn"
                           "h-8"
                           "min-h-8"
                           "rounded-lg"
                           "border"
                           "border-base-300"
                           "bg-base-100"
                           "px-2.5"
                           "text-xs"
                           "text-trading-text-secondary"
                           "hover:text-trading-text"
                           "hover:bg-base-200"
                           "sm:btn-sm"
                           "sm:px-3"
                           "sm:text-xs"]
                          (when primary?
                            ["bg-[#1f5b55]" "text-trading-text" "hover:bg-[#267067]"]))
             :data-role data-role
             :on {:click [action]}}
            (focus-return/data-role-return-focus-props data-role
                                                       (:data-role focus-request)
                                                       (:token focus-request)))
   [:span {:class ["sm:hidden"]} (or mobile-label label)]
   [:span {:class ["hidden" "sm:inline"]} label]])

(defn- inspected-trader-display-name
  [state inspected-address]
  (some (fn [row]
          (when (= inspected-address (:eth-address row))
            (:display-name row)))
        (get-in state [:leaderboard :rows])))

(defn- trader-explorer-url
  [address]
  (when (seq address)
    (str "https://app.hyperliquid.xyz/explorer/address/" address)))

(def ^:private portfolio-header-button-classes
  ["inline-flex"
   "items-center"
   "justify-center"
   "rounded-lg"
   "border"
   "px-3"
   "py-2"
   "text-sm"
   "font-medium"
   "transition-colors"
   "focus:outline-none"
   "focus:ring-0"
   "focus:ring-offset-0"
   "focus-visible:outline-none"
   "focus-visible:ring-0"
   "focus-visible:ring-offset-0"])

(defn portfolio-inspection-header
  [state]
  (let [inspected-address (account-context/trader-portfolio-address state)
        display-name (inspected-trader-display-name state inspected-address)
        explorer-url (trader-explorer-url inspected-address)
        inspected-label (or display-name
                            (wallet/short-addr inspected-address)
                            inspected-address)]
    [:div {:class ["flex" "flex-col" "gap-3" "lg:flex-row" "lg:items-start" "lg:justify-between"]
           :data-role "portfolio-inspection-header"}
     [:div {:class ["space-y-2"]}
      [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
       [:span {:class ["rounded-full"
                       "border"
                       "border-[#2b5d5b]"
                       "bg-[#103c39]"
                       "px-2.5"
                       "py-1"
                       "text-xs"
                       "font-semibold"
                       "uppercase"
                       "tracking-[0.14em]"
                       "text-[#9cf9e2]"]}
        "Trader View"]
       [:span {:class ["rounded-full"
                       "border"
                       "border-base-300"
                       "bg-base-100/95"
                       "px-2.5"
                       "py-1"
                       "text-xs"
                       "font-semibold"
                       "uppercase"
                       "tracking-[0.14em]"
                       "text-trading-text-secondary"]}
        "Read Only"]]
      [:div {:class ["space-y-1"]}
       [:h1 {:class ["text-4xl" "font-medium" "tracking-tight" "text-trading-text" "sm:text-5xl"]}
        "Portfolio"]
       [:p {:class ["max-w-3xl" "text-sm" "leading-6" "text-trading-text-secondary"]
            :data-role "portfolio-inspection-summary"}
        (str "Inspecting "
             (or inspected-label "this trader")
             " without enabling Spectate Mode. Leaving this route returns the app to its normal account context.")]]
      (when inspected-address
        [:div {:class ["inline-flex"
                       "max-w-full"
                       "items-center"
                       "gap-2"
                       "rounded-lg"
                       "border"
                       "border-base-300"
                       "bg-base-100/95"
                       "px-3"
                       "py-2"
                       "text-sm"
                       "text-trading-text-secondary"]
               :data-role "portfolio-inspection-address"}
         [:span {:class ["font-medium" "text-trading-text"]}
          (or display-name "Trader")]
         [:span {:class ["num" "truncate"]}
          inspected-address]])]
     [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]
            :data-role "portfolio-inspection-actions-row"}
      [:button {:type "button"
                :class (into portfolio-header-button-classes
                             ["border-base-300"
                              "bg-base-100"
                              "text-trading-text-secondary"
                              "hover:bg-base-200"
                              "hover:text-trading-text"])
                :on {:click [[:actions/navigate portfolio-routes/canonical-route]]}
                :data-role "portfolio-inspection-own-portfolio"}
       "Your Portfolio"]
      (when explorer-url
        [:a {:href explorer-url
             :target "_blank"
             :rel "noreferrer"
             :class (into portfolio-header-button-classes
                          ["border-[#2f7067]"
                           "bg-[#0f433d]"
                           "text-[#dbf7f2]"
                           "hover:bg-[#14544c]"])
             :data-role "portfolio-inspection-explorer-link"}
         "Hyperliquid Explorer"])]]))

(defn header-actions [state]
  (let [focus-request {:data-role (get-in state [:funding-ui :modal :focus-return-data-role])
                       :token (get-in state [:funding-ui :modal :focus-return-token] 0)}]
    [:div {:class ["flex" "flex-wrap" "items-start" "justify-between" "gap-3" "sm:items-center"]}
     [:h1 {:class ["text-4xl" "font-medium" "tracking-tight" "text-trading-text" "sm:text-5xl"]}
      "Portfolio"]
     [:div {:class ["flex" "flex-wrap" "items-center" "gap-1.5" "sm:gap-2"]
            :data-role "portfolio-actions-row"}
      (for [{:keys [label] :as item} action-items]
        ^{:key label}
        (action-button (assoc item :focus-request focus-request)))]]))

(defn background-status-banner [{:keys [visible? title detail items]}]
  (when visible?
    [:div {:class ["rounded-xl"
                   "border"
                   "px-4"
                   "py-3"
                   "backdrop-blur-sm"]
           :style {:border-color "rgba(46, 91, 98, 0.9)"
                   :background "linear-gradient(135deg, rgba(8, 24, 30, 0.96) 0%, rgba(9, 35, 42, 0.96) 54%, rgba(14, 44, 37, 0.92) 100%)"}
           :data-role "portfolio-background-status"
           :role "status"
           :aria-live "polite"}
     [:div {:class ["flex" "flex-col" "gap-3" "xl:flex-row" "xl:items-center" "xl:justify-between"]}
      [:div {:class ["flex" "items-start" "gap-3"]}
       [:span {:class ["mt-0.5" "loading" "loading-spinner" "loading-sm" "text-trading-green"]
               :aria-hidden true}]
       [:div {:class ["space-y-1"]}
        [:div {:class ["text-sm" "font-medium" "text-trading-text"]}
         title]
        [:div {:class ["text-sm" "leading-5" "text-trading-text-secondary"]}
         detail]]]
      [:div {:class ["flex" "flex-wrap" "gap-2"]}
       (for [{:keys [id label]} items]
         ^{:key (str "portfolio-background-status-item-" (name id))}
         [:span {:class ["rounded-full"
                         "border"
                         "px-2.5"
                         "py-1"
                         "text-xs"
                         "font-medium"
                         "uppercase"
                         "tracking-[0.18em]"]
                 :style {:border-color "rgba(72, 113, 119, 0.88)"
                         :background-color "rgba(12, 29, 35, 0.92)"
                         :color "#9fb6bc"}
                 :data-role (str "portfolio-background-status-item-" (name id))}
          label])]]]))
