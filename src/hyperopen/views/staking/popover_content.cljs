(ns hyperopen.views.staking.popover-content
  "Bodies of the staking action popovers (Transfer / Stake / Unstake).

  Split out of hyperopen.views.staking.popovers, which keeps the geometry,
  anchoring and dialog chrome. The split exists so both files stay under the
  500-line namespace-size gate."
  (:require [clojure.string :as str]
            [hyperopen.staking.unstaking :as unstaking]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.staking.shared :as shared]
            [hyperopen.views.staking.unstaking-panel :as unstaking-panel]))
(defn- popover-amount-input
  [{:keys [input-id amount on-change on-max]}]
  [:div {:class ["relative"]}
   [:input {:id input-id
            :type "text"
            :inputmode "decimal"
            :placeholder "Amount"
            :value amount
            :class (into ["h-10"
                          "w-full"
                          "rounded-[10px]"
                          "border"
                          "border-ho-surface"
                          "bg-[#08161f]"
                          "px-3"
                          "pr-16"
                          "text-sm"
                          "text-ho-text"]
                         shared/neutral-input-focus-classes)
            :on {:input [on-change]}}]
   [:button {:type "button"
             :class ["absolute"
                     "right-3"
                     "top-1/2"
                     "-translate-y-1/2"
                     "text-xs"
                     "font-medium"
                     "leading-none"
                     "text-ho-accent"
                     "focus:outline-none"
                     "focus:ring-0"
                     "focus:ring-offset-0"]
             :on {:click [[on-max]]}}
    "MAX"]])
(defn- popover-cta-button
  [{:keys [label submitting? on-submit]}]
  [:button {:type "button"
            :class ["h-10"
                    "w-full"
                    "rounded-[10px]"
                    "bg-[#0f544b]"
                    "text-sm"
                    "font-normal"
                    "text-[#021510]"
                    "transition-colors"
                    "hover:bg-[#1a6f63]"
                    "disabled:cursor-not-allowed"
                    "disabled:opacity-65"]
            :disabled submitting?
            :on {:click [[on-submit]]}}
   (if submitting?
     "Submitting..."
     label)])
(defn- validator-options
  [validators selected-validator]
  (let [validators* (reduce (fn [acc {:keys [validator name stake]}]
                              (if (seq validator)
                                (conj acc {:validator validator
                                           :name (or name validator)
                                           :stake stake})
                                acc))
                            []
                            (or validators []))
        selected-present? (boolean (some #(= selected-validator (:validator %))
                                         validators*))]
    (cond-> validators*
      (and (seq selected-validator)
           (not selected-present?))
      (conj {:validator selected-validator
             :name selected-validator
             :stake nil}))))
(defn- validator-matches-search?
  [search-token {:keys [name validator]}]
  (or (str/includes? (str/lower-case (str (or name ""))) search-token)
      (str/includes? (str/lower-case (str (or validator ""))) search-token)))
(defn- validator-toggle-button
  [open?]
  [:button {:type "button"
            :class ["absolute"
                    "right-2.5"
                    "top-1/2"
                    "-translate-y-1/2"
                    "text-sm"
                    "text-ho-text-secondary"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"]
            :on {:click (if open?
                          [[:actions/set-staking-form-field :validator-dropdown-open? false]]
                          [[:actions/set-staking-form-field :validator-search-query ""]
                           [:actions/set-staking-form-field :validator-dropdown-open? true]])}}
   (if open? "⌃" "⌄")])
(defn- empty-validator-option
  [selected-validator]
  [:button {:type "button"
            :class (into ["mb-0.5"
                          "flex"
                          "w-full"
                          "items-center"
                          "gap-2"
                          "rounded-[8px]"
                          "px-2"
                          "py-1.5"
                          "text-left"
                          "text-sm"
                          "leading-none"]
                         (if (empty? selected-validator)
                           ["bg-[#122c37]" "text-ho-text"]
                           ["text-[#c8d5d7]" "hover:bg-[#112733]"]))
            :on {:click [[:actions/select-staking-validator ""]]}}
   (when (empty? selected-validator)
     [:span {:class ["text-ho-accent-bright"]} "✓"])
   [:span "Select a Validator"]])
(defn- validator-option-row
  [{:keys [validator name stake]} selected-validator]
  (let [selected? (= validator selected-validator)]
    ^{:key (str "staking-validator-option-" validator)}
    [:button {:type "button"
              :class (into ["mb-0.5"
                            "flex"
                            "w-full"
                            "items-center"
                            "justify-between"
                            "gap-2"
                            "rounded-[8px]"
                            "px-2"
                            "py-1.5"
                            "text-left"
                            "text-sm"
                            "leading-none"]
                           (if selected?
                             ["bg-[#122c37]" "text-ho-text"]
                             ["text-[#c8d5d7]" "hover:bg-[#112733]"]))
              :on {:click [[:actions/select-staking-validator validator]]}}
     [:span {:class ["truncate"]}
      (str (when selected? "✓ ")
           name)]
     [:span {:class ["num" "shrink-0" "text-xs" "text-ho-text-secondary"]}
      (if (number? stake)
        (str (shared/format-table-hype stake) " HYPE")
        "")]]))
(defn- popover-validator-options
  [filtered-options selected-validator]
  [:div {:class ["absolute"
                 "left-0"
                 "right-0"
                 "top-[calc(100%+6px)]"
                 "z-[12]"
                 "max-h-64"
                 "overflow-y-auto"
                 "rounded-[10px]"
                 "border"
                 "border-[#1d3540]"
                 "bg-ho-bg"
                 "p-1"
                 "shadow-[0_16px_34px_rgba(0,0,0,0.45)]"]}
   (empty-validator-option selected-validator)
   (if (seq filtered-options)
     (for [option filtered-options]
       (validator-option-row option selected-validator))
     [:div {:class ["px-2" "py-2" "text-sm" "text-ho-text-secondary"]}
      "No validators found"])])
(defn- popover-validator-select
  [{:keys [selected-validator validators search-query dropdown-open?]}]
  (let [options (validator-options validators selected-validator)
        selected-option (some #(when (= selected-validator (:validator %))
                                 %)
                              options)
        search-token (-> (or search-query "")
                         str
                         str/trim
                         str/lower-case)
        filtered-options (if (seq search-token)
                           (filterv #(validator-matches-search? search-token %)
                                    options)
                           options)
        open? (true? dropdown-open?)]
    [:div {:class ["relative"]}
     [:div {:class ["relative"]}
      [:input {:type "text"
               :value (or search-query "")
               :placeholder (or (:name selected-option) "Select a Validator")
               :class (into ["h-10"
                             "w-full"
                             "rounded-[10px]"
                             "border"
                             "border-ho-surface"
                             "bg-[#08161f]"
                             "px-3"
                             "pr-9"
                             "text-sm"
                             "text-[#c8d5d7]"
                             "placeholder:text-ho-text-secondary"]
                            shared/neutral-input-focus-classes)
               :on {:focus [[:actions/set-staking-form-field :validator-dropdown-open? true]]
                    :input [[:actions/set-staking-form-field :validator-search-query [:event.target/value]]
                            [:actions/set-staking-form-field :validator-dropdown-open? true]]}}]
      (validator-toggle-button open?)]
     (when open?
       (popover-validator-options filtered-options selected-validator))]))
(defn- transfer-direction-toggle
  [direction]
  (let [spot->staking? (= direction :spot->staking)
        from-label (if spot->staking?
                     "Spot Balance"
                     "Staking Balance")
        to-label (if spot->staking?
                   "Staking Balance"
                   "Spot Balance")
        next-direction (if spot->staking?
                         :staking->spot
                         :spot->staking)]
    [:div {:class ["flex" "justify-center"]}
     [:button {:type "button"
               :class ["h-9"
                       "inline-flex"
                       "items-center"
                       "gap-2"
                       "rounded-[10px]"
                       "bg-[#13242d]"
                       "px-3"
                       "text-[18px]"
                       "font-normal"
                       "leading-none"
                       "text-[#c8d5d7]"
                       "transition-colors"
                       "hover:bg-[#1a3039]"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:ring-offset-0"]
               :data-role "staking-transfer-direction-toggle"
               :on {:click [[:actions/set-staking-transfer-direction next-direction]]}}
      [:span from-label]
      [:span {:class ["text-[16px]" "text-ho-accent"]}
       "->"]
      [:span to-label]]]))
(defn- note-line
  [text]
  [:p {:class ["text-sm" "text-ho-text-secondary"]}
   text])

(defn- caution-note
  [text data-role]
  [:div {:class ["rounded-lg"
                 "border"
                 "border-ho-warn/40"
                 "bg-ho-warn/10"
                 "px-3"
                 "py-2"
                 "text-sm"
                 "leading-5"
                 "text-ho-text"]
         :data-role data-role}
   text])

(defn transfer-popover-content
  [{:keys [form submitting balances unstaking projected-transfer-arrival-ms transfer-direction]}]
  (let [spot->staking? (= transfer-direction :spot->staking)
        amount (if spot->staking?
                 (:deposit-amount form)
                 (:withdraw-amount form))
        on-change (if spot->staking?
                    [:actions/set-staking-form-field :deposit-amount [:event.target/value]]
                    [:actions/set-staking-form-field :withdraw-amount [:event.target/value]])
        on-max (if spot->staking?
                 :actions/set-staking-deposit-amount-to-max
                 :actions/set-staking-withdraw-amount-to-max)
        on-submit (if spot->staking?
                    :actions/submit-staking-deposit
                    :actions/submit-staking-withdraw)
        submitting? (if spot->staking?
                      (true? (:deposit? submitting))
                      (true? (:withdraw? submitting)))
        source-label (if spot->staking?
                       "Available to Transfer to Staking Balance"
                       "Available to Transfer to Spot Balance")
        source-value (if spot->staking?
                       (shared/format-balance-hype (:available-transfer balances))
                       (shared/format-balance-hype (:available-stake balances)))]
    [:div {:class ["space-y-3"]}
     [:div {:class ["space-y-1" "text-center"]}
      (note-line "Transfer HYPE between your staking and spot balances.")
      (note-line (if spot->staking?
                   "Transfers into your Staking Balance are available immediately."
                   "Transfers to your Spot Balance take 7 days. The HYPE leaves your Staking Balance straight away and cannot be traded until it arrives."))]
     (transfer-direction-toggle transfer-direction)
     (popover-amount-input {:input-id "staking-transfer-amount"
                            :amount amount
                            :on-change on-change
                            :on-max on-max})
     [:div {:class ["space-y-1.5"]}
      (shared/key-value-row source-label source-value)
      (shared/key-value-row "Available to Stake"
                            (shared/format-balance-hype (:available-stake balances)))]
     [:div {:data-role "staking-transfer-arrival-note"}
      (when-not spot->staking?
        (caution-note (if-let [stamp (fmt/format-local-date-time projected-transfer-arrival-ms)]
                        (str "A transfer started now would arrive around " stamp ".")
                        "Transfers to your Spot Balance take 7 days.")
                      "staking-transfer-projected-arrival"))]
     (unstaking-panel/unstaking-block unstaking)
     (popover-cta-button {:label "Transfer"
                          :submitting? submitting?
                          :on-submit on-submit})]))
(defn stake-popover-content
  [{:keys [form
           submitting
           balances
           selected-validator
           validators
           validator-search-query
           validator-dropdown-open?]}]
  [:div {:class ["space-y-3"]}
   (popover-amount-input {:input-id "staking-delegate-amount"
                          :amount (:delegate-amount form)
                          :on-change [:actions/set-staking-form-field :delegate-amount [:event.target/value]]
                          :on-max :actions/set-staking-delegate-amount-to-max})
   (popover-validator-select {:selected-validator selected-validator
                              :validators validators
                              :search-query validator-search-query
                              :dropdown-open? validator-dropdown-open?})
   [:div {:class ["space-y-1.5"]}
    (shared/key-value-row "Available to Stake" (shared/format-balance-hype (:available-stake balances)))
    (shared/key-value-row "Total Staked" (shared/format-balance-hype (:total-staked balances)))]
   [:p {:class ["text-sm" "text-ho-text-secondary"]}
    "The staking lockup period is 1 day."]
   (popover-cta-button {:label "Stake"
                        :submitting? (true? (:delegate? submitting))
                        :on-submit :actions/submit-staking-delegate})])
(defn- unstake-lock-notice
  "Warns about the delegation lockup while the user can still change their mind.
  The submit guard in hyperopen.staking.actions is unchanged and still produces
  its own message; this only moves the same fact earlier in the flow."
  [{:keys [locked-until-timestamp remaining-ms]}]
  (caution-note
   (str "This delegation is locked until "
        (fmt/format-local-date-time locked-until-timestamp)
        (when-let [span (fmt/format-duration-approx remaining-ms)]
          (str " · about " span " left"))
        ". You cannot unstake from this validator yet.")
   "staking-unstake-lock-notice"))

(defn unstake-popover-content
  [{:keys [form
           submitting
           error
           balances
           selected-validator
           selected-validator-lock
           validators
           validator-search-query
           validator-dropdown-open?]}]
  [:div {:class ["space-y-3"]}
   (popover-amount-input {:input-id "staking-undelegate-amount"
                          :amount (:undelegate-amount form)
                          :on-change [:actions/set-staking-form-field :undelegate-amount [:event.target/value]]
                          :on-max :actions/set-staking-undelegate-amount-to-max})
   (popover-validator-select {:selected-validator selected-validator
                              :validators validators
                              :search-query validator-search-query
                              :dropdown-open? validator-dropdown-open?})
   [:div {:class ["space-y-1.5"]}
    (shared/key-value-row "Total Staked"
                          (shared/format-balance-hype (:total-staked balances)))
    (shared/key-value-row "Available to Stake after unstaking"
                          (shared/format-balance-hype (:available-stake balances)))]
   [:div {:class ["space-y-2"]
          :data-role "staking-unstake-guidance"}
    [:p {:class ["text-sm" "leading-5" "text-ho-text-secondary"]}
     "Unstaking returns HYPE to your Staking Balance right away. Moving it to your Spot Balance is a separate transfer that then takes 7 days, so unstaked HYPE is not tradable straight away."]
    (when selected-validator-lock
      (unstake-lock-notice selected-validator-lock))]
   (when (seq error)
     [:div {:class ["rounded-lg" "border" "border-ho-border-sell" "bg-ho-sell-soft-deep"
                    "px-3" "py-2" "text-sm" "text-ho-sell-tint"]
            :data-role "staking-unstake-error"}
      error])
   (popover-cta-button {:label "Unstake"
                        :submitting? (true? (:undelegate? submitting))
                        :on-submit :actions/submit-staking-undelegate})])
