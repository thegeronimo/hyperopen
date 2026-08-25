(ns hyperopen.views.portfolio.header
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.views.ui.focus-return :as focus-return]
            [hyperopen.wallet.core :as wallet]))

(def ^:private action-items
  [{:label "Optimize"
    :data-role "portfolio-action-optimize"
    :action [:actions/navigate (portfolio-routes/portfolio-optimize-path)]}
   {:label "Link Staking"
    :mobile-label "Staking"
    :data-role "portfolio-action-link-staking"
    :action [:actions/navigate "/staking"]}
   {:label "Perps ↔ Spot"
    :mobile-label "Perp Spot"
    :data-role "portfolio-action-perps-spot"
    :action [:actions/open-funding-transfer-modal
             :event.currentTarget/bounds
             "portfolio-action-perps-spot"]}
   {:label "Withdraw"
    :data-role "portfolio-action-withdraw"
    :action [:actions/open-funding-withdraw-modal
             :event.currentTarget/bounds
             "portfolio-action-withdraw"]}
   {:label "Deposit"
    :primary? true
    :data-role "portfolio-action-deposit"
    :action [:actions/open-funding-deposit-modal
             :event.currentTarget/bounds
             "portfolio-action-deposit"]}])

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
                            ["bg-ho-accent-soft-hi" "text-trading-text" "hover:bg-ho-accent-soft-hi/80"]))
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

(defn background-status-banner
  "Ambient \"still syncing\" status for the portfolio page.

  This region is deliberately FIXED rather than an in-flow sibling. It used to
  be `(when visible? ...)` inside the page's `space-y-4` column, which meant
  every appearance and disappearance translated the summary grid, the chart card
  and the whole performance-metrics tearsheet down by roughly 86px and back.
  During a timeframe switch that happens twice in under a second, and because
  the tearsheet renders its own range selector far down the page, the control the
  trader just clicked moved out from under the cursor and returned. That layout
  shift was the single loudest symptom of the \"switching timeframes is janky\"
  report, and no CPU profile would ever have shown it.

  The outer region is always present (so the live region exists before content
  is inserted into it, which is what makes the announcement work) and is fixed,
  so whether it has content or not the flow above and below is identical.
  `backdrop-blur-sm` is gone on purpose: a backdrop filter forces the compositor
  to read back and re-blur whatever is behind it on every frame the spinner
  paints, which is precisely the frames where the main thread is already busy."
  [{:keys [visible? title detail items]}]
  [:div {:class ["pointer-events-none"
                 "fixed"
                 "left-3"
                 "bottom-16"
                 "z-[270]"
                 "w-[min(24rem,calc(100vw-1.5rem))]"]
         :role "status"
         :aria-live "polite"
         :data-role "portfolio-background-status-region"}
   (when visible?
     [:div {:class ["rounded-xl"
                    "border"
                    "px-4"
                    "py-3"
                    "shadow-lg"]
            :style {:border-color "rgba(46, 91, 98, 0.9)"
                    :background "linear-gradient(135deg, rgba(8, 24, 30, 0.96) 0%, rgba(9, 35, 42, 0.96) 54%, rgba(14, 44, 37, 0.92) 100%)"}
            :data-role "portfolio-background-status"}
      [:div {:class ["flex" "flex-col" "gap-3"]}
       [:div {:class ["flex" "items-start" "gap-3"]}
        [:span {:class ["mt-0.5" "ho-spinner" "ho-spinner-sm" "text-trading-green"]
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
           label])]]])])
