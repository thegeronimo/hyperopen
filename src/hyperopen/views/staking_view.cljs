(ns hyperopen.views.staking-view
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.staking.vm :as staking-vm]))

(defn- format-summary-hype
  [value]
  (if (number? value)
    (or (fmt/format-integer value) "0")
    "--"))

(defn- format-balance-hype
  [value]
  (if (number? value)
    (str (fmt/format-fixed-number value 8) " HYPE")
    "--"))

(defn- format-table-hype
  [value]
  (if (number? value)
    (or (fmt/format-integer value) "0")
    "--"))

(defn- format-percent
  [value]
  (if (number? value)
    (str (fmt/format-fixed-number (* value 100) 2) "%")
    "--"))

(defn- status-pill
  [status]
  (let [[label classes]
        (case status
          :active ["Active" ["text-[#3fd9c0]"]]
          :jailed ["Jailed" ["text-[#ff99ac]"]]
          ["Inactive" ["text-trading-text-secondary"]])]
    [:span {:class (into ["text-sm" "font-medium"]
                         classes)}
     label]))

(defn- summary-card
  [label value data-role]
  [:div {:class ["rounded-xl"
                 "border"
                 "border-base-300"
                 "bg-base-100"
                 "px-4"
                 "py-3.5"
                 "space-y-2"]
         :data-role data-role}
   [:div {:class ["text-lg" "leading-none" "font-medium" "text-trading-text-secondary"]}
    label]
   [:div {:class ["text-5xl" "leading-none" "font-semibold" "text-white" "num"]}
    value]])

(defn- key-value-row
  [label value]
  [:div {:class ["flex" "items-start" "justify-between" "gap-3" "text-sm"]}
   [:span {:class ["text-trading-text-secondary" "leading-5"]}
    label]
   [:span {:class ["num" "text-trading-text" "font-semibold"]}
    value]])

(defn- action-card
  [{:keys [title
           description
           input-id
           amount
           submitting?
           connected?
           on-change
           on-max
           on-submit
           button-label]}]
  [:div {:class ["rounded-xl"
                 "border"
                 "border-base-300"
                 "bg-base-100"
                 "p-4"
                 "space-y-3"]}
   [:div {:class ["space-y-1"]}
    [:h3 {:class ["text-sm" "font-semibold" "text-white"]}
     title]
    [:p {:class ["text-xs" "text-trading-text-secondary"]}
     description]]
   [:div {:class ["flex" "items-center" "gap-2"]}
    [:input {:id input-id
             :type "text"
             :inputmode "decimal"
             :placeholder "0.0"
             :value amount
             :class ["h-9"
                     "w-full"
                     "rounded-xl"
                     "border"
                     "border-base-300"
                     "bg-base-100"
                     "px-3"
                     "text-sm"
                     "text-trading-text"
                     "focus:outline-none"
                     "focus:ring-0"
                     "focus:ring-offset-0"]
             :on {:input [on-change]}}]
    [:button {:type "button"
              :class ["h-9"
                      "rounded-lg"
                      "border"
                      "border-base-300"
                      "px-2.5"
                      "text-xs"
                      "font-semibold"
                      "text-trading-text-secondary"
                      "transition-colors"
                      "hover:bg-base-200"
                      "focus:outline-none"
                      "focus:ring-0"
                      "focus:ring-offset-0"]
              :on {:click [on-max]}}
     "Max"]]
   [:button {:type "button"
             :class ["h-9"
                     "w-full"
                     "rounded-xl"
                     "border"
                     "border-[#2f7f73]"
                     "bg-[#123a36]"
                     "text-sm"
                     "font-semibold"
                     "text-[#97fce4]"
                     "transition-colors"
                     "hover:bg-[#185047]"
                     "disabled:cursor-not-allowed"
                     "disabled:opacity-60"]
             :disabled (or submitting?
                           (not connected?))
             :on {:click [on-submit]}}
    (if submitting?
      "Submitting..."
      button-label)]])

(defn- tab-button
  [active? label action]
  [:button {:type "button"
            :class (into ["border-b-2"
                          "px-2"
                          "pb-2"
                          "text-sm"
                          "font-medium"
                          "transition-colors"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"]
                         (if active?
                           ["border-[#2f7f73]" "text-[#d8f4ef]"]
                           ["border-transparent" "text-trading-text-secondary" "hover:text-trading-text"]))
            :on {:click [action]}}
   label])

(defn- validator-row
  [{:keys [validator
           name
           description
           stake
           your-stake
           uptime-fraction
           predicted-apr
           status
           commission]}
   selected-validator]
  (let [selected? (and (seq validator)
                       (= validator selected-validator))]
    [:tr {:class (into ["border-b"
                        "border-base-300/50"
                        "text-sm"
                        "cursor-pointer"
                        "transition-colors"
                        "hover:bg-base-200/40"]
                       (when selected?
                         ["bg-[#103a35]/50"]))
          :on {:click [[:actions/select-staking-validator validator]]}
        :data-role "staking-validator-row"}
     [:td {:class ["px-3" "py-2.5" "font-medium" "text-white"]}
      name]
     [:td {:class ["px-3" "py-2.5" "text-trading-text-secondary" "max-w-[260px]" "truncate"]}
      (or description "--")]
     [:td {:class ["px-3" "py-2.5" "num"]} (format-table-hype stake)]
     [:td {:class ["px-3" "py-2.5" "num"]}
      (if (pos? (or your-stake 0))
        (format-table-hype your-stake)
        "-")]
     [:td {:class ["px-3" "py-2.5" "num"]} (format-percent uptime-fraction)]
     [:td {:class ["px-3" "py-2.5" "num"]} (format-percent predicted-apr)]
     [:td {:class ["px-3" "py-2.5"]} (status-pill status)]
     [:td {:class ["px-3" "py-2.5" "num"]} (format-percent commission)]]))

(defn- history-table
  [rows columns empty-text row-render]
  [:div {:class ["overflow-x-auto" "rounded-xl" "border" "border-base-300"]}
   [:table {:class ["min-w-full" "bg-base-100"]}
    [:thead
     [:tr {:class ["text-xs" "text-trading-text-secondary"]}
      (for [column columns]
        ^{:key column}
        [:th {:class ["px-3" "py-2" "text-left"]}
         column])]]
    [:tbody
     (if (seq rows)
       (map row-render rows)
       [:tr
        [:td {:col-span (count columns)
              :class ["px-3" "py-6" "text-center" "text-sm" "text-trading-text-secondary"]}
         empty-text]])]]])

(defn staking-view
  [state]
  (let [{:keys [connected?
                effective-address
                active-tab
                tabs
                validator-timeframe
                timeframe-options
                loading?
                error
                summary
                balances
                validators
                rewards
                history
                selected-validator
                form
                submitting
                delegations]} (staking-vm/staking-vm state)]
    [:div {:class ["flex"
                   "h-full"
                   "w-full"
                   "flex-col"
                   "gap-3"
                   "app-shell-gutter"
                   "pt-4"
                   "pb-16"]
           :data-parity-id "staking-root"}
     [:div {:class ["rounded-xl"
                    "border"
                    "border-[#165049]/70"
                    "bg-[#02262a]/70"
                    "p-4"
                    "space-y-3"
                    "backdrop-blur-[1px]"]}
      [:div {:class ["flex" "flex-wrap" "items-start" "justify-between" "gap-3"]}
       [:div {:class ["space-y-2" "max-w-[980px]"]}
        [:h1 {:class ["text-5xl" "font-semibold" "tracking-tight" "text-[#e4f3f1]"]}
         "Staking"]
        [:p {:class ["text-base" "leading-tight" "text-[#d4ece7]" "sm:text-lg" "lg:text-[31px]"]}
         "The Hyperliquid L1 is a proof-of-stake blockchain where stakers delegate the native token HYPE to validators to earn staking rewards. Stakers only receive rewards when the validator successfully participates in consensus, so stakers should only delegate to reputable and trusted validators."]
        (when (seq effective-address)
          [:p {:class ["text-xs" "text-trading-text-secondary" "num"]}
           (str "Account: " effective-address)])]
       (when-not connected?
         [:button {:type "button"
                   :class ["h-9"
                           "w-full"
                           "sm:w-auto"
                           "rounded-xl"
                           "border"
                           "border-[#2f7f73]"
                           "bg-[#174f49]"
                           "px-5"
                           "text-sm"
                           "font-semibold"
                           "text-[#97fce4]"
                           "transition-colors"
                           "hover:bg-[#1c5d55]"]
                   :data-role "staking-establish-connection"
                   :on {:click [[:actions/connect-wallet]]}}
          "Establish Connection"])]

      [:div {:class ["grid" "gap-2" "lg:grid-cols-[340px_minmax(0,1fr)]"]}
       [:div {:class ["grid" "gap-2"]}
        (summary-card "Total Staked" (format-summary-hype (:total-staked summary)) "staking-summary-total")
        (summary-card "Your Stake" (format-summary-hype (:your-stake summary)) "staking-summary-user")]
       [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100" "p-4" "space-y-2"]
              :data-role "staking-balance-panel"}
        [:div {:class ["text-lg" "leading-none" "font-medium" "text-trading-text-secondary"]}
         "Staking Balance"]
        (key-value-row "Available to Transfer to Staking Balance"
                       (format-balance-hype (:available-transfer balances)))
        (key-value-row "Available to Stake" (format-balance-hype (:available-stake balances)))
        (key-value-row "Total Staked" (format-balance-hype (:total-staked balances)))
        (key-value-row "Pending Transfers to Spot Balance"
                       (format-balance-hype (:pending-withdrawals balances)))]]]

     (when connected?
       [:div {:class ["space-y-3"]}
        [:div {:class ["grid" "gap-3" "lg:grid-cols-2"]}
         (action-card {:title "Transfer to Staking Balance"
                       :description "Move HYPE from spot to staking balance."
                       :input-id "staking-deposit-amount"
                       :amount (:deposit-amount form)
                       :submitting? (true? (:deposit? submitting))
                       :connected? connected?
                       :on-change [:actions/set-staking-form-field :deposit-amount [:event.target/value]]
                       :on-max :actions/set-staking-deposit-amount-to-max
                       :on-submit :actions/submit-staking-deposit
                       :button-label "Transfer In"})
         (action-card {:title "Transfer to Spot Balance"
                       :description "Move HYPE from staking balance back to spot."
                       :input-id "staking-withdraw-amount"
                       :amount (:withdraw-amount form)
                       :submitting? (true? (:withdraw? submitting))
                       :connected? connected?
                       :on-change [:actions/set-staking-form-field :withdraw-amount [:event.target/value]]
                       :on-max :actions/set-staking-withdraw-amount-to-max
                       :on-submit :actions/submit-staking-withdraw
                       :button-label "Transfer Out"})
         (action-card {:title "Stake"
                       :description "Delegate staking balance to a validator."
                       :input-id "staking-delegate-amount"
                       :amount (:delegate-amount form)
                       :submitting? (true? (:delegate? submitting))
                       :connected? connected?
                       :on-change [:actions/set-staking-form-field :delegate-amount [:event.target/value]]
                       :on-max :actions/set-staking-delegate-amount-to-max
                       :on-submit :actions/submit-staking-delegate
                       :button-label "Stake"})
         (action-card {:title "Unstake"
                       :description "Undelegate a validator position back to staking balance."
                       :input-id "staking-undelegate-amount"
                       :amount (:undelegate-amount form)
                       :submitting? (true? (:undelegate? submitting))
                       :connected? connected?
                       :on-change [:actions/set-staking-form-field :undelegate-amount [:event.target/value]]
                       :on-max :actions/set-staking-undelegate-amount-to-max
                       :on-submit :actions/submit-staking-undelegate
                       :button-label "Unstake"})]

        [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
         [:label {:for "staking-selected-validator"
                  :class ["text-xs" "uppercase" "tracking-[0.08em]" "text-trading-text-secondary"]}
          "Selected Validator"]
         [:input {:id "staking-selected-validator"
                  :type "text"
                  :value selected-validator
                  :placeholder "0x..."
                  :class ["h-9"
                          "w-full"
                          "max-w-xl"
                          "rounded-xl"
                          "border"
                          "border-base-300"
                          "bg-base-100"
                          "px-3"
                          "text-sm"
                          "text-trading-text"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"]
                  :on {:input [[:actions/set-staking-form-field :selected-validator [:event.target/value]]]}}]

        (when (and (empty? selected-validator)
                   (seq delegations))
          [:p {:class ["text-xs" "text-trading-text-secondary"]}
           "Select a validator from the table to prefill stake/unstake actions."])]])

     [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100" "overflow-hidden"]}
      [:div {:class ["flex" "flex-wrap" "items-end" "justify-between" "gap-2" "px-3" "pt-3" "pb-0"]}
       [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
        (for [{:keys [value label]} tabs]
          ^{:key value}
          (tab-button (= value active-tab)
                      label
                      [:actions/set-staking-active-tab value]))]
       (when (= :validator-performance active-tab)
         [:div {:class ["relative"]
                :data-role "staking-timeframe-toggle"}
          [:select {:value (name validator-timeframe)
                    :class ["h-8"
                            "rounded-lg"
                            "border"
                            "border-base-300"
                            "bg-base-100"
                            "pl-2.5"
                            "pr-7"
                            "text-sm"
                            "font-medium"
                            "text-trading-text"
                            "appearance-none"
                            "focus:outline-none"
                            "focus:ring-0"
                            "focus:ring-offset-0"]
                    :on {:change [[:actions/set-staking-validator-timeframe [:event.target/value]]]}}
           (for [{:keys [value label]} timeframe-options]
             ^{:key value}
             [:option {:value (name value)}
              label])]
          [:span {:class ["pointer-events-none"
                          "absolute"
                          "right-2.5"
                          "top-1/2"
                          "-translate-y-1/2"
                          "text-xs"
                          "text-trading-text-secondary"]}
           "▾"]])]

      (case active-tab
        :staking-reward-history
        (history-table
         rewards
         ["Time" "Source" "Amount"]
         (if loading?
           "Loading staking rewards..."
           "No staking rewards found.")
         (fn [{:keys [time-ms source total-amount]}]
           ^{:key (str "reward-" time-ms "-" source)}
           [:tr {:class ["border-b" "border-base-300/50" "text-sm"]}
            [:td {:class ["px-3" "py-2.5" "num"]}
             (or (fmt/format-local-date-time time-ms) "--")]
            [:td {:class ["px-3" "py-2.5"]}
             (or source "--")]
            [:td {:class ["px-3" "py-2.5" "num"]}
             (format-balance-hype total-amount)]]))

        :staking-action-history
        (history-table
         history
         ["Time" "Action" "Amount" "Status" "Tx"]
         (if loading?
           "Loading staking action history..."
           "No staking actions found.")
         (fn [{:keys [time-ms kind amount status hash]}]
           ^{:key (str "history-" time-ms "-" hash)}
           [:tr {:class ["border-b" "border-base-300/50" "text-sm"]}
            [:td {:class ["px-3" "py-2.5" "num"]}
             (or (fmt/format-local-date-time time-ms) "--")]
            [:td {:class ["px-3" "py-2.5"]}
             kind]
            [:td {:class ["px-3" "py-2.5" "num"]}
             (format-balance-hype amount)]
            [:td {:class ["px-3" "py-2.5"]}
             (or status "--")]
            [:td {:class ["px-3" "py-2.5" "num"]}
             (or (some-> hash (subs 0 (min 10 (count hash)))) "--")]]))

        ;; Default: validator performance table
        [:div {:class ["overflow-x-auto"]}
         [:table {:class ["min-w-full" "bg-base-100"]
                  :data-role "staking-validator-table"}
          [:thead
           [:tr {:class ["text-xs" "text-trading-text-secondary"]}
            [:th {:class ["px-3" "py-2.5" "text-left"]} "Name"]
            [:th {:class ["px-3" "py-2.5" "text-left"]} "Description"]
            [:th {:class ["px-3" "py-2.5" "text-left"]} "Stake"]
            [:th {:class ["px-3" "py-2.5" "text-left"]} "Your Stake"]
            [:th {:class ["px-3" "py-2.5" "text-left"]} "Uptime"]
            [:th {:class ["px-3" "py-2.5" "text-left"]} "Est. APR"]
            [:th {:class ["px-3" "py-2.5" "text-left"]} "Status"]
            [:th {:class ["px-3" "py-2.5" "text-left"]} "Commission"]]]
          [:tbody
           (if (seq validators)
             (for [row validators]
               ^{:key (:validator row)}
               (validator-row row selected-validator))
             [:tr
               [:td {:col-span 8
                    :class ["px-3" "py-6" "text-center" "text-sm" "text-trading-text-secondary"]}
               (if loading?
                 "Loading validators..."
                 "No validator data available.")]])]]])]

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
        error])]))
