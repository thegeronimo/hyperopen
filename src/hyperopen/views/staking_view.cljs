(ns hyperopen.views.staking-view
  (:require [hyperopen.views.staking.history :as staking-history]
            [hyperopen.views.staking.popovers :as staking-popovers]
            [hyperopen.views.staking.shared :as shared]
            [hyperopen.views.staking.validators :as staking-validators]
            [hyperopen.views.staking.vm :as staking-vm]))

(defn- toolbar-action-button
  [{:keys [label data-role primary? action]}]
  [:button {:type "button"
            :class (into ["h-9"
                          "rounded-[10px]"
                          "border"
                          "px-4"
                          "text-sm"
                          "font-normal"
                          "transition-colors"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"
                          "whitespace-nowrap"]
                         (if primary?
                           ["border-[#50d2c1]"
                            "bg-[#50d2c1]"
                            "text-[#041914]"
                            "hover:bg-[#6de3d5]"]
                           ["border-[#2f7f73]"
                            "bg-[#041a1f]"
                            "text-[#97fce4]"
                            "hover:bg-[#0b262c]"]))
            :data-role data-role
            :on {:click [action]}}
   label])

(defn- connect-wallet-button []
  [:button {:type "button"
            :class ["h-9"
                    "min-w-[90px]"
                    "rounded-lg"
                    "bg-[#50d2c1]"
                    "px-4"
                    "text-xs"
                    "font-normal"
                    "text-[#04060c]"
                    "transition-colors"
                    "hover:bg-[#72e5d7]"]
            :data-role "staking-establish-connection"
            :on {:click [[:actions/connect-wallet]]}}
   "Connect"])

(defn- staking-toolbar
  [connected?]
  (if connected?
    [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
     (toolbar-action-button {:label "Spot <-> Staking Balance Transfer"
                             :data-role "staking-action-transfer-button"
                             :action [:actions/open-staking-action-popover
                                      :transfer
                                      :event.currentTarget/bounds]})
     (toolbar-action-button {:label "Unstake"
                             :data-role "staking-action-unstake-button"
                             :action [:actions/open-staking-action-popover
                                      :unstake
                                      :event.currentTarget/bounds]})
     (toolbar-action-button {:label "Stake"
                             :primary? true
                             :data-role "staking-action-stake-button"
                             :action [:actions/open-staking-action-popover
                                      :stake
                                      :event.currentTarget/bounds]})]
    (connect-wallet-button)))

(defn- staking-hero
  [connected?]
  [:div {:class ["bg-[#04251f]"
                 "px-4"
                 "py-3"
                 "space-y-3"
                 "rounded-[10px]"]}
   [:div {:class ["flex" "flex-wrap" "items-start" "justify-between" "gap-3"]}
    [:div {:class ["space-y-2" "max-w-[980px]"]}
     [:h1 {:class ["text-[24px]" "md:text-[34px]" "font-normal" "leading-[1.08]" "text-[#ffffff]"]}
      "Staking"]
     [:p {:class ["text-base" "leading-5" "text-[#f6fefd]" "max-w-[1200px]"]}
      "The Hyperliquid L1 is a proof-of-stake blockchain where stakers delegate the native token HYPE to validators to earn staking rewards. Stakers only receive rewards when the validator successfully participates in consensus, so stakers should only delegate to reputable and trusted validators."]]
    (staking-toolbar connected?)]])

(defn- summary-and-balance-panels
  [summary balances]
  [:div {:class ["grid" "gap-2" "lg:grid-cols-[340px_minmax(0,1fr)]"]}
   [:div {:class ["grid" "gap-2"]}
    (shared/summary-card "Total Staked" (shared/format-summary-hype (:total-staked summary)) "staking-summary-total")
    (shared/summary-card "Your Stake" (shared/format-summary-hype (:your-stake summary)) "staking-summary-user")]
   [:div {:class ["rounded-[10px]" "border" "border-[#1b2429]" "bg-[#0f1a1f]" "p-4" "space-y-2"]
          :data-role "staking-balance-panel"}
    [:div {:class ["text-sm" "leading-[15px]" "font-normal" "text-[#878c8f]"]}
     "Staking Balance"]
    (shared/key-value-row "Available to Transfer to Staking Balance"
                          (shared/format-balance-hype (:available-transfer balances)))
    (shared/key-value-row "Available to Stake" (shared/format-balance-hype (:available-stake balances)))
    (shared/key-value-row "Total Staked" (shared/format-balance-hype (:total-staked balances)))
    (shared/key-value-row "Pending Transfers to Spot Balance"
                          (shared/format-balance-hype (:pending-withdrawals balances)))]])

(defn- tab-button
  [active? label action]
  [:button {:type "button"
            :class (into ["border-b"
                          "px-0"
                          "mr-4"
                          "text-xs"
                          "font-normal"
                          "leading-[34px]"
                          "transition-colors"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"]
                         (if active?
                           ["border-[#303030]" "text-[#f6fefd]"]
                           ["border-[#303030]" "text-[#949e9c]" "hover:text-[#c5d0ce]"]))
            :on {:click [action]}}
   label])

(defn- tab-bar
  [{:keys [tabs
           active-tab
           validator-timeframe
           timeframe-options
           validator-timeframe-dropdown-open?]}]
  [:div {:class ["flex" "flex-wrap" "items-end" "justify-between" "gap-2" "px-3" "pt-2" "pb-0"]}
   [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
    (for [{:keys [value label]} tabs]
      ^{:key value}
      (tab-button (= value active-tab)
                  label
                  [:actions/set-staking-active-tab value]))]
   (when (= :validator-performance active-tab)
     (staking-validators/validator-timeframe-menu validator-timeframe
                                                  timeframe-options
                                                  validator-timeframe-dropdown-open?))])

(defn- active-tab-panel
  [{:keys [active-tab
           loading?
           rewards
           history
           validators
           selected-validator
           validator-page
           validator-show-all?
           validator-page-count
           validators-total-count
           validator-page-range-start
           validator-page-range-end
           validator-sort]}]
  (case active-tab
    :staking-reward-history
    (staking-history/rewards-history-panel {:rewards rewards
                                            :loading? loading?})

    :staking-action-history
    (staking-history/action-history-panel {:history history
                                           :loading? loading?})

    (staking-validators/validator-performance-panel {:loading? loading?
                                                     :validators validators
                                                     :selected-validator selected-validator
                                                     :validator-page validator-page
                                                     :validator-show-all? validator-show-all?
                                                     :validator-page-count validator-page-count
                                                     :validators-total-count validators-total-count
                                                     :validator-page-range-start validator-page-range-start
                                                     :validator-page-range-end validator-page-range-end
                                                     :validator-sort validator-sort})))

(defn- tabbed-content
  [view-state]
  [:div {:class ["rounded-[10px]" "border" "border-[#1b2429]" "bg-[#0f1a1f]" "overflow-hidden"]}
   (tab-bar view-state)
   (active-tab-panel view-state)])

(defn- error-banner
  [error]
  (when (seq error)
    [:div {:class ["rounded-xl"
                   "border"
                   "border-[#7a2836]"
                   "bg-[#2b1118]"
                   "px-3"
                   "py-2"
                   "text-sm"
                   "text-[#ff9db2]"]
           :data-role "staking-error"}
     error]))

(defn staking-view
  [state]
  (let [view-state (staking-vm/staking-vm state)
        {:keys [connected?
                summary
                balances
                error
                action-popover
                form
                submitting
                selected-validator
                validator-search-query
                validator-dropdown-open?
                validators]} view-state]
    [:div {:class ["flex"
                   "flex-1"
                   "min-h-0"
                   "w-full"
                   "overflow-hidden"
                   "flex-col"
                   "app-shell-gutter"
                   "pt-3"]
           :data-parity-id "staking-root"}
     [:div {:class ["w-full"
                    "h-full"
                    "min-h-0"
                    "scrollbar-hide"
                    "overflow-y-auto"
                    "flex"
                    "flex-col"
                    "gap-2"
                    "pb-16"]}
      (staking-hero connected?)
      (summary-and-balance-panels summary balances)
      (tabbed-content view-state)
      (error-banner error)
      (when (and connected?
                 (:open? action-popover))
        (staking-popovers/action-popover-layer {:action-popover action-popover
                                                :form form
                                                :submitting submitting
                                                :balances balances
                                                :selected-validator selected-validator
                                                :validator-search-query validator-search-query
                                                :validator-dropdown-open? validator-dropdown-open?
                                                :validators validators}))]]))

(defn ^:export route-view
  [state]
  (staking-view state))

(goog/exportSymbol "hyperopen.views.staking_view.route_view" route-view)
