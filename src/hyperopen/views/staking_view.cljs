(ns hyperopen.views.staking-view
  (:require [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]
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

(def ^:private inactive-jailed-tooltip-copy
  "The validator does not have enough stake to participate in the active validator set.")

(defn- status-pill
  [status]
  (case status
    :active
    [:span {:class ["text-xs" "font-normal" "leading-6" "text-[#97fce4]"]}
     "Active"]

    :jailed
    [:span {:class ["group" "relative" "inline-flex" "items-center" "leading-6"]}
     [:span {:class ["text-xs" "font-normal" "text-[#9aa3a4]" "cursor-help"]
             :tab-index 0
             :data-role "staking-validator-status-inactive"}
      "Inactive"]
     [:span {:class ["pointer-events-none"
                     "absolute"
                     "left-1/2"
                     "-translate-x-1/2"
                     "bottom-full"
                     "mb-2"
                     "z-20"
                     "opacity-0"
                     "transition-opacity"
                     "duration-200"
                     "group-hover:opacity-100"
                     "group-focus-within:opacity-100"]
             :data-role "staking-validator-status-tooltip"}
      [:span {:class ["block"
                      "w-[270px]"
                      "max-w-[calc(100vw-2rem)]"
                      "rounded-md"
                      "bg-[#223038]"
                      "px-2.5"
                      "py-1.5"
                      "text-left"
                      "text-xs"
                      "leading-tight"
                      "text-[#f2f6f8]"
                      "shadow-[0_4px_16px_rgba(0,0,0,0.35)]"]}
       inactive-jailed-tooltip-copy]]]

    [:span {:class ["text-xs" "font-normal" "leading-6" "text-[#9aa3a4]"]}
     "Inactive"]))

(defn- summary-card
  [label value data-role]
  [:div {:class ["rounded-[10px]"
                 "border"
                 "border-[#1b2429]"
                 "bg-[#0f1a1f]"
                 "px-4"
                 "py-3"
                 "space-y-2"]
         :data-role data-role}
   [:div {:class ["text-sm" "leading-[15px]" "font-normal" "text-[#878c8f]"]}
    label]
   [:div {:class ["text-[30px]" "sm:text-[34px]" "leading-none" "font-normal" "text-[#f6fefd]" "num"]}
    value]])

(defn- key-value-row
  [label value]
  [:div {:class ["flex" "items-start" "justify-between" "gap-3" "text-xs"]}
   [:span {:class ["text-[#9aa3a4]" "leading-[15px]"]}
    label]
   [:span {:class ["num" "text-[#f6fefd]" "font-normal" "leading-[15px]"]}
    value]])

(def ^:private popover-margin-px
  12)

(def ^:private popover-gap-px
  10)

(def ^:private minimum-popover-anchor-height-px
  36)

(def ^:private neutral-input-focus-classes
  ["outline-none"
   "transition-[border-color,box-shadow]"
   "duration-150"
   "hover:border-[#6f7a88]"
   "hover:ring-1"
   "hover:ring-[#6f7a88]/30"
   "hover:ring-offset-0"
   "focus:outline-none"
   "focus:ring-1"
   "focus:ring-[#8a96a6]/40"
   "focus:ring-offset-0"
   "focus:shadow-none"
   "focus:border-[#8a96a6]"])

(def ^:private popover-fallback-viewport-width
  1280)

(def ^:private popover-fallback-viewport-height
  800)

(defn- clamp
  [value min-value max-value]
  (-> value
      (max min-value)
      (min max-value)))

(defn- anchor-number
  [anchor k default]
  (let [value (get anchor k)]
    (if (number? value)
      value
      default)))

(def ^:private action-popover-trigger-data-role-by-kind
  {:transfer "staking-action-transfer-button"
   :unstake "staking-action-unstake-button"
   :stake "staking-action-stake-button"})

(defn- query-element-anchor
  [data-role]
  (when (and (string? data-role)
             (some? js/document))
    (let [selector (str "[data-role=\"" data-role "\"]")
          element (.querySelector js/document selector)]
      (when (and element
                 (fn? (.-getBoundingClientRect element)))
        (let [rect (.getBoundingClientRect element)]
          {:left (.-left rect)
           :right (.-right rect)
           :top (.-top rect)
           :bottom (.-bottom rect)
           :width (.-width rect)
           :height (.-height rect)
           :viewport-width (some-> js/globalThis .-innerWidth)
           :viewport-height (some-> js/globalThis .-innerHeight)})))))

(defn- action-popover-anchor
  [kind stored-anchor]
  (let [data-role (get action-popover-trigger-data-role-by-kind kind)]
    (or (query-element-anchor data-role)
        stored-anchor)))

(defn- action-popover-layout-style
  [anchor kind]
  (let [anchor* (if (map? anchor) anchor {})
        estimated-height-px (if (= kind :transfer) 440 400)
        viewport-width (max 320
                            (anchor-number anchor* :viewport-width popover-fallback-viewport-width)
                            (+ (anchor-number anchor* :right 0) popover-margin-px))
        viewport-height (max 320
                             (anchor-number anchor* :viewport-height popover-fallback-viewport-height))
        available-width (max 0 (- viewport-width (* 2 popover-margin-px)))
        panel-width (min 560 available-width)
        anchor-right (anchor-number anchor*
                                    :right
                                    (- viewport-width popover-margin-px))
        anchor-top (anchor-number anchor* :top popover-margin-px)
        anchor-height (max minimum-popover-anchor-height-px
                           (anchor-number anchor* :height 0))
        anchor-bottom* (anchor-number anchor*
                                      :bottom
                                      (+ anchor-top anchor-height))
        anchor-bottom (if (>= (- anchor-bottom* anchor-top) 8)
                        anchor-bottom*
                        (+ anchor-top anchor-height))
        preferred-left (- anchor-right panel-width)
        left (clamp preferred-left
                    popover-margin-px
                    (- viewport-width panel-width popover-margin-px))
        preferred-top (+ anchor-bottom popover-gap-px)
        max-top (- viewport-height estimated-height-px popover-margin-px)
        top (if (> max-top popover-margin-px)
              (clamp preferred-top popover-margin-px max-top)
              popover-margin-px)]
    {:left (str left "px")
     :top (str top "px")
     :width (str panel-width "px")}))

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

(defn- popover-close-button []
  [:button {:type "button"
            :class ["absolute"
                    "right-5"
                    "top-4"
                    "inline-flex"
                    "h-8"
                    "w-8"
                    "items-center"
                    "justify-center"
                    "rounded-lg"
                    "text-[#f6fefd]"
                    "transition-colors"
                    "hover:bg-[#16313b]"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"]
            :aria-label "Close staking action popover"
            :on {:click [[:actions/close-staking-action-popover]]}}
   "x"])

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
                          "border-[#1b2429]"
                          "bg-[#08161f]"
                          "px-3"
                          "pr-16"
                          "text-sm"
                          "text-[#f6fefd]"]
                         neutral-input-focus-classes)
            :on {:input [on-change]}}]
   [:button {:type "button"
             :class ["absolute"
                     "right-3"
                     "top-1/2"
                     "-translate-y-1/2"
                     "text-xs"
                     "font-medium"
                     "leading-none"
                     "text-[#50d2c1]"
                     "focus:outline-none"
                     "focus:ring-0"
                     "focus:ring-offset-0"]
             :on {:click [on-max]}}
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
            :on {:click [on-submit]}}
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
                             "border-[#1b2429]"
                             "bg-[#08161f]"
                             "px-3"
                             "pr-9"
                             "text-sm"
                             "text-[#c8d5d7]"
                             "placeholder:text-[#9aa3a4]"]
                            neutral-input-focus-classes)
               :on {:focus [[:actions/set-staking-form-field :validator-dropdown-open? true]]
                    :input [[:actions/set-staking-form-field :validator-search-query [:event.target/value]]
                            [:actions/set-staking-form-field :validator-dropdown-open? true]]}}]
      [:button {:type "button"
                :class ["absolute"
                        "right-2.5"
                        "top-1/2"
                        "-translate-y-1/2"
                        "text-sm"
                        "text-[#949e9c]"
                        "focus:outline-none"
                        "focus:ring-0"
                        "focus:ring-offset-0"]
                :on {:click (if open?
                              [[:actions/set-staking-form-field :validator-dropdown-open? false]]
                              [[:actions/set-staking-form-field :validator-search-query ""]
                               [:actions/set-staking-form-field :validator-dropdown-open? true]])}}
       (if open? "⌃" "⌄")]]
     (when open?
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
                      "bg-[#0f1a1f]"
                      "p-1"
                      "shadow-[0_16px_34px_rgba(0,0,0,0.45)]"]}
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
                                 ["bg-[#122c37]" "text-[#f6fefd]"]
                                 ["text-[#c8d5d7]" "hover:bg-[#112733]"]))
                  :on {:click [[:actions/select-staking-validator ""]]}}
         (when (empty? selected-validator)
           [:span {:class ["text-[#97fce4]"]} "✓"])
         [:span "Select a Validator"]]
        (if (seq filtered-options)
          (for [{:keys [validator name stake]} filtered-options]
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
                                       ["bg-[#122c37]" "text-[#f6fefd]"]
                                       ["text-[#c8d5d7]" "hover:bg-[#112733]"]))
                        :on {:click [[:actions/select-staking-validator validator]]}}
               [:span {:class ["truncate"]}
                (str (when selected? "✓ ")
                     name)]
               [:span {:class ["num" "shrink-0" "text-xs" "text-[#9aa3a4]"]}
                (if (number? stake)
                  (str (format-table-hype stake) " HYPE")
                  "")]]))
          [:div {:class ["px-2" "py-2" "text-sm" "text-[#949e9c]"]}
           "No validators found"])])]))

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
      [:span {:class ["text-[16px]" "text-[#50d2c1]"]}
       "->"]
      [:span to-label]]]))

(defn- transfer-popover-content
  [{:keys [form submitting balances transfer-direction]}]
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
                       (format-balance-hype (:available-transfer balances))
                       (format-balance-hype (:available-stake balances)))]
    [:div {:class ["space-y-3"]}
     [:div {:class ["space-y-1" "text-center"]}
      [:p {:class ["text-sm" "text-[#9aa3a4]"]}
       "Transfer HYPE between your staking and spot balances."]
      [:p {:class ["text-sm" "text-[#9aa3a4]"]}
       "Transfers from Staking Balance to Spot Balance are locked for 7 days."]]
     (transfer-direction-toggle transfer-direction)
     (popover-amount-input {:input-id "staking-transfer-amount"
                            :amount amount
                            :on-change on-change
                            :on-max on-max})
     [:div {:class ["space-y-1.5"]}
      (key-value-row source-label source-value)
      (key-value-row "Available to Stake" (format-balance-hype (:available-stake balances)))
      (key-value-row "Pending Transfers to Spot Balance"
                     (format-balance-hype (:pending-withdrawals balances)))]
     (popover-cta-button {:label "Transfer"
                          :submitting? submitting?
                          :on-submit on-submit})]))

(defn- stake-popover-content
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
    (key-value-row "Available to Stake" (format-balance-hype (:available-stake balances)))
    (key-value-row "Total Staked" (format-balance-hype (:total-staked balances)))]
   [:p {:class ["text-sm" "text-[#9aa3a4]"]}
    "The staking lockup period is 1 day."]
   (popover-cta-button {:label "Stake"
                        :submitting? (true? (:delegate? submitting))
                        :on-submit :actions/submit-staking-delegate})])

(defn- unstake-popover-content
  [{:keys [form
           submitting
           selected-validator
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
   (popover-cta-button {:label "Unstake"
                        :submitting? (true? (:undelegate? submitting))
                        :on-submit :actions/submit-staking-undelegate})])

(defn- action-popover-layer
  [{:keys [action-popover
           form
           submitting
           balances
           selected-validator
           validator-search-query
           validator-dropdown-open?
           validators]}]
  (when (:open? action-popover)
    (let [kind (:kind action-popover)
          anchor (action-popover-anchor kind (:anchor action-popover))
          panel-style (action-popover-layout-style anchor kind)
          title (case kind
                  :transfer "Transfer HYPE"
                  :unstake "Unstake"
                  "Stake")]
      [:div {:class ["fixed" "inset-0" "z-[230]" "pointer-events-none"]
             :data-role "staking-action-popover-layer"}
       [:button {:type "button"
                 :class ["absolute" "inset-0" "pointer-events-auto" "bg-transparent"]
                 :aria-label "Close staking action popover"
                 :on {:click [[:actions/close-staking-action-popover]]}}]
       [:div {:class ["absolute"
                      "pointer-events-auto"
                      "staking-action-popover-surface"
                      "rounded-[22px]"
                      "border"
                      "border-[#1d3540]"
                      "p-4"
                      "pt-5"
                      "shadow-[0_24px_58px_rgba(0,0,0,0.55)]"
                      "space-y-3"]
              :style panel-style
              :tab-index 0
              :role "dialog"
              :aria-modal true
              :data-role "staking-action-popover"
              :on {:keydown [[:actions/handle-staking-action-popover-keydown [:event/key]]]}}
        (popover-close-button)
        [:h2 {:class ["text-[42px]" "font-normal" "leading-none" "text-[#f6fefd]" "text-center"]}
         title]
        (case kind
          :transfer
          (transfer-popover-content {:form form
                                     :submitting submitting
                                     :balances balances
                                     :transfer-direction (:transfer-direction action-popover)})
          :unstake
          (unstake-popover-content {:form form
                                    :submitting submitting
                                    :selected-validator selected-validator
                                    :validator-search-query validator-search-query
                                    :validator-dropdown-open? validator-dropdown-open?
                                    :validators validators})
          (stake-popover-content {:form form
                                  :submitting submitting
                                  :balances balances
                                  :selected-validator selected-validator
                                  :validator-search-query validator-search-query
                                  :validator-dropdown-open? validator-dropdown-open?
                                  :validators validators}))]])))

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

(defn- sortable-validator-header
  [label column validator-sort]
  (let [active? (= column (:column validator-sort))
        direction (:direction validator-sort)
        indicator (if active?
                    (if (= :asc direction) "↑" "↓")
                    "↕")]
    [:th {:class ["px-3" "py-2" "text-left" "font-normal"]}
     [:button {:type "button"
               :class (into ["inline-flex"
                             "items-center"
                             "gap-1.5"
                             "text-xs"
                             "font-normal"
                             "transition-colors"
                             "focus:outline-none"
                             "focus:ring-0"
                             "focus:ring-offset-0"]
                            (if active?
                              ["text-[#f6fefd]"]
                              ["text-[#949e9c]" "hover:text-[#c5d0ce]"]))
               :data-role (str "staking-sort-header-" (name column))
               :on {:click [[:actions/set-staking-validator-sort column]]}}
      [:span label]
      [:span {:class ["num"
                      (if active?
                        "text-[#97fce4]"
                        "text-[#5f6d70]")]}
       indicator]]]))

(defn- validator-description-cell
  [{:keys [description]}]
  (let [description-text (or description "--")]
    [:td {:class ["px-3" "py-1.5" "text-[#9aa3a4]"]}
     [:div {:class ["group" "relative" "max-w-[260px]"]}
      [:span {:class ["block" "truncate"]
              :title description-text}
       description-text]
      (when (seq description)
        [:div {:class ["pointer-events-none"
                       "absolute"
                       "left-0"
                       "bottom-full"
                       "z-[120]"
                       "mb-1"
                       "w-max"
                       "max-w-[520px]"
                       "rounded-lg"
                       "border"
                       "border-[#2b3b42]"
                       "bg-[#202b32]"
                       "px-3"
                       "py-2"
                       "text-xs"
                       "leading-tight"
                       "text-[#f6fefd]"
                       "whitespace-normal"
                       "opacity-0"
                       "transition-opacity"
                       "duration-150"
                       "group-hover:opacity-100"]
               :data-role "staking-validator-description-tooltip"}
         description])]]))

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
                        "border-[#1b2429]"
                        "text-xs"
                        "cursor-pointer"
                        "transition-colors"
                        "hover:bg-[#1d2a30]"]
                       (when selected?
                         ["bg-[#1a2c31]"]))
          :on {:click [[:actions/select-staking-validator validator]]}
        :data-role "staking-validator-row"}
     [:td {:class ["px-3" "py-1.5" "font-normal" "text-[#f6fefd]"]}
      name]
     (validator-description-cell {:description description})
     [:td {:class ["px-3" "py-1.5" "num" "text-[#f6fefd]"]} (format-table-hype stake)]
     [:td {:class ["px-3" "py-1.5" "num" "text-[#f6fefd]"]}
      (if (pos? (or your-stake 0))
        (format-table-hype your-stake)
        "-")]
     [:td {:class ["px-3" "py-1.5" "num" "text-[#f6fefd]"]} (format-percent uptime-fraction)]
     [:td {:class ["px-3" "py-1.5" "num" "text-[#f6fefd]"]} (format-percent predicted-apr)]
     [:td {:class ["px-3" "py-1.5"]} (status-pill status)]
     [:td {:class ["px-3" "py-1.5" "num" "text-[#f6fefd]"]} (format-percent commission)]]))

(defn- history-table
  [rows columns empty-text row-render]
  [:div {:class ["overflow-x-auto" "rounded-[10px]" "border" "border-[#1b2429]"]}
   [:table {:class ["min-w-full" "bg-[#0f1a1f]"]}
    [:thead
     [:tr {:class ["text-xs" "text-[#949e9c]"]}
      (for [column columns]
        ^{:key column}
        [:th {:class ["px-3" "py-2.5" "text-left" "font-normal"]}
         column])]]
    [:tbody
     (if (seq rows)
       (map row-render rows)
       [:tr
       [:td {:col-span (count columns)
              :class ["px-3" "py-6" "text-center" "text-sm" "text-[#949e9c]"]}
         empty-text]])]]])

(defn- validator-pagination
  [{:keys [validator-page
           validator-page-count
           validator-show-all?
           validators-total-count
           validator-page-range-start
           validator-page-range-end]}]
  (when (pos? validators-total-count)
    (let [can-prev? (pos? validator-page)
          can-next? (< validator-page (dec validator-page-count))
          prev-page (max 0 (dec validator-page))
          next-page (min (dec validator-page-count) (inc validator-page))
          toggle-label (if validator-show-all?
                         "Paginated View"
                         "View All")
          toggle-target (not validator-show-all?)]
      [:div {:class ["flex"
                     "items-center"
                     "justify-between"
                     "px-3"
                     "py-2"
                     "text-sm"
                     "border-t"
                     "border-[#1b2429]"
                     "bg-[#0f1a1f]"]
             :data-role "staking-validator-pagination"}
       [:button {:type "button"
                 :class ["text-[#5ecfc1]"
                         "transition-colors"
                         "hover:text-[#7ee7d8]"
                         "focus:outline-none"
                         "focus:ring-0"
                         "focus:ring-offset-0"]
                 :data-role "staking-validator-toggle-view-all"
                 :on {:click [[:actions/set-staking-validator-show-all toggle-target]]}}
        toggle-label]
       [:div {:class ["flex" "items-center" "gap-2" "text-[#f6fefd]"]}
        [:span {:class ["num" "text-sm" "text-[#f6fefd]"]}
         (str validator-page-range-start "-" validator-page-range-end " of " validators-total-count)]
        [:button {:type "button"
                  :class (into ["h-7"
                                "min-w-7"
                                "px-2"
                                "inline-flex"
                                "items-center"
                                "justify-center"
                                "rounded-md"
                                "border"
                                "transition-colors"
                                "focus:outline-none"
                                "focus:ring-0"
                                "focus:ring-offset-0"]
                               (if can-prev?
                                 ["border-[#2a3b42]" "text-[#f6fefd]" "hover:bg-[#14252e]"]
                                 ["border-[#1b2429]" "text-[#4f5a5d]" "cursor-not-allowed"]))
                  :disabled (not can-prev?)
                  :data-role "staking-validator-page-prev"
                  :on {:click [[:actions/set-staking-validator-page prev-page]]}}
         "Prev"]
        [:button {:type "button"
                  :class (into ["h-7"
                                "min-w-7"
                                "px-2"
                                "inline-flex"
                                "items-center"
                                "justify-center"
                                "rounded-md"
                                "border"
                                "transition-colors"
                                "focus:outline-none"
                                "focus:ring-0"
                                "focus:ring-offset-0"]
                               (if can-next?
                                 ["border-[#2a3b42]" "text-[#f6fefd]" "hover:bg-[#14252e]"]
                                 ["border-[#1b2429]" "text-[#4f5a5d]" "cursor-not-allowed"]))
                  :disabled (not can-next?)
                  :data-role "staking-validator-page-next"
                  :on {:click [[:actions/set-staking-validator-page next-page]]}}
         "Next"]]])))

(defn- staking-timeframe-menu
  [selected-timeframe timeframe-options open?]
  (let [selected-label (or (some (fn [{:keys [value label]}]
                                   (when (= value selected-timeframe)
                                     label))
                                 timeframe-options)
                           "7D")]
    [:div {:class ["relative"]
           :data-role "staking-timeframe-toggle"}
     (when open?
       [:button {:type "button"
                 :class ["fixed" "inset-0" "z-[9]" "cursor-default"]
                 :aria-label "Close staking timeframe menu"
                 :on {:click [[:actions/close-staking-validator-timeframe-menu]]}}])
     [:button {:type "button"
               :class (into ["relative"
                             "z-[10]"
                             "h-8"
                             "min-w-[58px]"
                             "inline-flex"
                             "items-center"
                             "justify-between"
                             "gap-1.5"
                             "rounded-lg"
                             "border"
                             "border-[#1b2429]"
                             "bg-[#0f1a1f]"
                             "px-2.5"
                             "text-xs"
                             "font-normal"
                             "text-[#f6fefd]"
                             "transition-colors"
                             "hover:border-[#6f7a88]"]
                            neutral-input-focus-classes)
               :aria-expanded (boolean open?)
               :data-role "staking-timeframe-menu-trigger"
               :on {:click [[:actions/toggle-staking-validator-timeframe-menu]]}}
      [:span selected-label]
      [:svg {:class (into ["h-3.5"
                           "w-3.5"
                           "text-[#949e9c]"
                           "transition-transform"
                           "duration-150"]
                          (when open?
                            ["rotate-180"]))
             :fill "none"
             :stroke "currentColor"
             :viewBox "0 0 24 24"}
       [:path {:stroke-linecap "round"
               :stroke-linejoin "round"
               :stroke-width 2
               :d "M6 9l6 6 6-6"}]]]
     [:div {:class (into ["absolute"
                          "right-0"
                          "top-[calc(100%+6px)]"
                          "z-[11]"
                          "min-w-[72px]"
                          "overflow-hidden"
                          "rounded-[10px]"
                          "border"
                          "border-[#1b2429]"
                          "bg-[#0f1a1f]"
                          "shadow-[0_14px_28px_rgba(0,0,0,0.45)]"
                          "transition-[opacity,transform]"
                          "duration-100"]
                         (if open?
                           ["opacity-100" "translate-y-0" "pointer-events-auto"]
                           ["opacity-0" "-translate-y-1" "pointer-events-none"]))
            :data-role "staking-timeframe-menu"}
      (for [{:keys [value label]} timeframe-options]
        ^{:key (str "staking-timeframe-option-" (name value))}
        [:button {:type "button"
                  :class (into ["block"
                                "w-full"
                                "px-3"
                                "py-2"
                                "text-left"
                                "text-xs"
                                "font-normal"
                                "transition-colors"]
                               (if (= value selected-timeframe)
                                 ["bg-[#122c37]" "text-[#f6fefd]"]
                                 ["text-[#c8d5d7]" "hover:bg-[#112733]"]))
                  :data-role (str "staking-timeframe-option-" (name value))
                  :on {:click [[:actions/set-staking-validator-timeframe value]]}}
         label])]]))

(defn staking-view
  [state]
  (let [{:keys [connected?
                effective-address
                active-tab
                tabs
                validator-timeframe
                validator-timeframe-dropdown-open?
                validator-page
                validator-show-all?
                validator-page-count
                validators-total-count
                validator-page-range-start
                validator-page-range-end
                validator-sort
                timeframe-options
                loading?
                error
                summary
                balances
                validators
                rewards
                history
                action-popover
                selected-validator
                validator-search-query
                validator-dropdown-open?
                form
                submitting]} (staking-vm/staking-vm state)]
    [:div {:class ["flex"
                   "flex-1"
                   "h-full"
                   "min-h-0"
                   "w-full"
                   "overflow-y-auto"
                   "scrollbar-hide"
                   "flex-col"
                   "gap-2"
                   "app-shell-gutter"
                   "pt-3"
                   "pb-10"]
           :data-parity-id "staking-root"}
     [:div {:class ["bg-[#04251f]"
                    "px-4"
                    "py-3"
                    "space-y-3"
                    "rounded-[10px]"]}
      [:div {:class ["flex" "flex-wrap" "items-start" "justify-between" "gap-3"]}
       [:div {:class ["space-y-2" "max-w-[980px]"]}
        [:h1 {:class ["text-[24px]" "md:text-[34px]" "font-normal" "leading-[1.08]" "text-[#ffffff]"]}
         "Staking"]
        [:p {:class ["text-sm" "leading-[15px]" "text-[#f6fefd]" "max-w-[1200px]"]}
         "The Hyperliquid L1 is a proof-of-stake blockchain where stakers delegate the native token HYPE to validators to earn staking rewards. Stakers only receive rewards when the validator successfully participates in consensus, so stakers should only delegate to reputable and trusted validators."]
        (when (seq effective-address)
          [:p {:class ["text-xs" "text-[#878c8f]" "num"]}
           (str "Account: " effective-address)])]
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
          "Connect"])]

      [:div {:class ["grid" "gap-2" "lg:grid-cols-[340px_minmax(0,1fr)]"]}
       [:div {:class ["grid" "gap-2"]}
        (summary-card "Total Staked" (format-summary-hype (:total-staked summary)) "staking-summary-total")
        (summary-card "Your Stake" (format-summary-hype (:your-stake summary)) "staking-summary-user")]
       [:div {:class ["rounded-[10px]" "border" "border-[#1b2429]" "bg-[#0f1a1f]" "p-4" "space-y-2"]
              :data-role "staking-balance-panel"}
        [:div {:class ["text-sm" "leading-[15px]" "font-normal" "text-[#878c8f]"]}
         "Staking Balance"]
        (key-value-row "Available to Transfer to Staking Balance"
                       (format-balance-hype (:available-transfer balances)))
        (key-value-row "Available to Stake" (format-balance-hype (:available-stake balances)))
        (key-value-row "Total Staked" (format-balance-hype (:total-staked balances)))
        (key-value-row "Pending Transfers to Spot Balance"
                       (format-balance-hype (:pending-withdrawals balances)))]]]

     [:div {:class ["rounded-[10px]" "border" "border-[#1b2429]" "bg-[#0f1a1f]" "overflow-hidden"]}
      [:div {:class ["flex" "flex-wrap" "items-end" "justify-between" "gap-2" "px-3" "pt-2" "pb-0"]}
       [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
        (for [{:keys [value label]} tabs]
          ^{:key value}
          (tab-button (= value active-tab)
                      label
                      [:actions/set-staking-active-tab value]))]
       (when (= :validator-performance active-tab)
         (staking-timeframe-menu validator-timeframe
                                 timeframe-options
                                 validator-timeframe-dropdown-open?))]

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
        [:div {:class ["space-y-0"]}
         [:div {:class ["overflow-x-auto" "overflow-y-auto"]
                :style {:max-height "56vh"}
                :data-role "staking-validator-table-scroll-region"}
          [:table {:class ["min-w-full" "bg-[#0f1a1f]"]
                   :data-role "staking-validator-table"}
           [:thead
            [:tr {:class ["border-b" "border-[#1b2429]" "text-xs" "text-[#949e9c]"]}
             (sortable-validator-header "Name" :name validator-sort)
             (sortable-validator-header "Description" :description validator-sort)
             (sortable-validator-header "Stake" :stake validator-sort)
             (sortable-validator-header "Your Stake" :your-stake validator-sort)
             (sortable-validator-header "Uptime" :uptime validator-sort)
             (sortable-validator-header "Est. APR" :apr validator-sort)
             (sortable-validator-header "Status" :status validator-sort)
             (sortable-validator-header "Commission" :commission validator-sort)]]
           [:tbody
            (if (seq validators)
              (for [row validators]
                ^{:key (:validator row)}
                (validator-row row selected-validator))
              [:tr
                [:td {:col-span 8
                      :class ["px-3" "py-6" "text-center" "text-sm" "text-[#949e9c]"]}
                 (if loading?
                   "Loading validators..."
                   "No validator data available.")]])]]]
         (validator-pagination {:validator-page validator-page
                                :validator-show-all? validator-show-all?
                                :validator-page-count validator-page-count
                                :validators-total-count validators-total-count
                                :validator-page-range-start validator-page-range-start
                                :validator-page-range-end validator-page-range-end})])]

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
        error])

     (when (and connected?
                (:open? action-popover))
       (action-popover-layer {:action-popover action-popover
                              :form form
                              :submitting submitting
                              :balances balances
                              :selected-validator selected-validator
                              :validator-search-query validator-search-query
                              :validator-dropdown-open? validator-dropdown-open?
                              :validators validators}))]))
