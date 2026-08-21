(ns hyperopen.views.subaccounts-view.management
  (:require [hyperopen.views.subaccounts-view.transfer-dropdowns :as transfer-dropdowns]))

(defn- action-button
  [{:keys [data-role label on-click disabled? variant class]}]
  [:button {:type "button"
            :data-role data-role
            :disabled (boolean disabled?)
            :class (into ["inline-flex"
                          "h-8"
                          "items-center"
                          "justify-center"
                          "rounded-md"
                          "border"
                          "px-2.5"
                          "text-xs"
                          "font-medium"
                          "leading-none"
                          "transition-colors"]
                         (concat
                          (cond
                            disabled?
                            ["cursor-not-allowed" "border-base-300" "bg-base-200/30" "text-trading-text-secondary"]

                            (= :primary variant)
                            ["border-ho-accent" "bg-ho-accent" "text-[#021b18]" "hover:bg-[#69e7d6]"]

                            :else
                            ["border-base-300" "bg-[#121d20]" "text-white" "hover:border-[#2dceb3]/60" "hover:bg-[#172528]"])
                          class))
            :on {:click on-click}}
   label])

(defn- text-input
  [{:keys [data-role value on-input placeholder disabled?]}]
  [:input {:type "text"
           :data-role data-role
           :value (or value "")
           :placeholder placeholder
           :disabled (boolean disabled?)
           :class ["min-w-0"
                   "h-10"
                   "w-full"
                   "rounded-md"
                   "border"
                   "border-base-300"
                   "bg-[#0a1417]"
                   "px-2.5"
                   "text-xs"
                   "text-white"
                   "outline-none"
                   "placeholder:text-trading-text-secondary"
                   "focus:border-[#2dceb3]"]
           :on {:input on-input}}])

(defn create-panel
  [{:keys [subaccounts connected? read-only?]}]
  (let [creating? (true? (:creating? subaccounts))
        open? (and (not read-only?)
                   (true? (:create-popover-open? subaccounts)))]
    [:div {:class ["relative" "flex" "w-full" "justify-end" "sm:w-auto"]
           :data-role "subaccounts-create-panel"}
     (action-button {:data-role "subaccounts-open-create-popover"
                     :label "Create Sub-Account"
                     :variant :primary
                     :disabled? (or read-only? (not connected?))
                     :on-click [[:actions/open-subaccount-create-popover]]})
     (when open?
       [:div {:class ["absolute"
                      "right-0"
                      "top-12"
                      "z-20"
                      "w-[min(92vw,31rem)]"
                      "rounded-xl"
                      "border"
                      "border-[#294145]"
                      "bg-[#0b171b]"
                      "p-6"
                      "shadow-[0_24px_80px_rgba(0,0,0,0.45)]"]
              :data-role "subaccounts-create-popover"}
        [:div {:class ["mb-5" "flex" "items-center" "justify-between" "gap-4"]}
         [:h2 {:class ["text-xl" "font-semibold" "text-white"]} "Create Sub-Account"]
         [:button {:type "button"
                   :data-role "subaccounts-create-popover-close"
                   :class ["text-xl" "leading-none" "text-trading-text-secondary" "hover:text-white"]
                   :on {:click [[:actions/close-subaccount-create-popover]]}}
          "x"]]
        (text-input {:data-role "subaccounts-create-name"
                     :value (:create-name subaccounts)
                     :placeholder "Name"
                     :disabled? (or read-only? creating? (not connected?))
                     :on-input [[:actions/set-subaccount-form-field
                                 :create-name
                                 [:event.target/value]]]})
        [:div {:class ["mt-5" "grid" "grid-cols-2" "gap-4"]}
         (action-button {:data-role "subaccounts-create-cancel"
                         :label "Cancel"
                         :disabled? creating?
                         :on-click [[:actions/close-subaccount-create-popover]]})
         (action-button {:data-role "subaccounts-create-submit"
                         :label (if creating? "Creating..." "Confirm")
                         :variant :primary
                         :disabled? (or read-only? creating? (not connected?))
                         :on-click [[:actions/submit-create-subaccount]]})]])]))

(defn trade-button
  [{:keys [data-role label on-click active? disabled?]}]
  [:button {:type "button"
            :data-role data-role
            :disabled (boolean disabled?)
            :class (into ["text-sm" "font-medium" "text-[#48dbc8]" "transition-colors"]
                         (cond
                           disabled? ["cursor-not-allowed" "opacity-40"]
                           active? ["text-white"]
                           :else ["hover:text-white"]))
            :on {:click on-click}}
   label])

(defn- rename-controls
  [{:keys [address subaccounts]}]
  (let [active? (= address (:renaming-address subaccounts))]
    [:div {:class ["flex" "min-w-0" "flex-col" "gap-2"]}
     (action-button {:data-role (str "subaccounts-rename-" address)
                     :label "Rename"
                     :disabled? active?
                     :on-click [[:actions/start-rename-subaccount address]]})
     (when active?
       [:div {:class ["flex" "min-w-[18rem]" "flex-col" "gap-2" "sm:flex-row"]}
        (text-input {:data-role (str "subaccounts-rename-name-" address)
                     :value (:rename-name subaccounts)
                     :placeholder "New name"
                     :on-input [[:actions/set-subaccount-form-field
                                 :rename-name
                                 [:event.target/value]]]})
        (action-button {:data-role (str "subaccounts-rename-submit-" address)
                        :label "Save"
                        :variant :primary
                        :on-click [[:actions/submit-rename-subaccount address]]})
        (action-button {:data-role (str "subaccounts-rename-cancel-" address)
                        :label "Cancel"
                        :on-click [[:actions/cancel-rename-subaccount]]})])]))

(defn- transfer-field-shell
  [{:keys [children class]}]
  (into [:div {:class (into ["flex"
                             "h-10"
                             "items-center"
                             "rounded-md"
                             "border"
                             "border-[#263b3f]"
                             "bg-[#0b1518]"
                             "px-3"
                             "text-sm"
                             "text-white"
                             "transition-colors"
                             "focus-within:border-ho-accent"]
                            class)}]
        children))


(defn- transfer-direction-summary
  [{:keys [address direction subaccount-name]}]
  (let [withdraw? (= :withdraw direction)
        from-label (if withdraw? subaccount-name "Master Account")
        to-label (if withdraw? "Master Account" subaccount-name)]
    [:div {:class ["grid"
                   "grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)]"
                   "items-end"
                   "gap-3"]}
     [:div {:data-role (str "subaccounts-transfer-source-" address)
            :class ["min-w-0"]}
      [:div {:class ["mb-2" "text-xs" "font-medium" "text-[#9aa8ab]"]} "From"]
      [:div {:class ["truncate" "text-sm" "font-semibold" "text-white"]
             :title from-label}
       from-label]]
     [:div {:class ["flex" "items-end" "justify-start" "pb-0.5" "sm:justify-center"]}
      [:button {:type "button"
                :data-role (str "subaccounts-transfer-toggle-direction-" address)
                :aria-label "Reverse transfer direction"
                :title "Reverse transfer direction"
                :class ["inline-flex"
                        "h-8"
                        "min-w-8"
                        "items-center"
                        "justify-center"
                        "rounded-md"
                        "border-0"
                        "bg-transparent"
                        "px-2"
                        "text-base"
                        "font-semibold"
                        "leading-none"
                        "text-ho-accent"
                        "transition-colors"
                        "whitespace-nowrap"
                        "hover:bg-[#12312e]"
                        "hover:text-white"
                        "focus:outline-none"
                        "focus:ring-0"
                        "focus:ring-offset-0"]
                :on {:click [[:actions/toggle-transfer-direction]]}}
       [:span {:data-role (str "subaccounts-transfer-flow-arrow-" address)
               :aria-hidden true}
        "->"]]]
     [:div {:data-role (str "subaccounts-transfer-destination-" address)
            :class ["min-w-0"]}
      [:div {:class ["mb-2" "text-xs" "font-medium" "text-[#9aa8ab]"]} "To"]
      [:div {:class ["truncate" "text-sm" "font-semibold" "text-white"]
             :title to-label}
       to-label]]]))

(defn- transfer-popover
  [{:keys [address subaccount-name subaccounts deposit-max withdraw-max transfer-assets unified-account? class]}]
  (let [direction (or (:transfer-direction subaccounts) :deposit)
        withdrawing? (= :withdraw direction)
        ;; Resolve the selection against the rows the dropdown actually offers,
        ;; so the MAX label and the Send button can never describe a token the
        ;; user cannot see. `token-dropdown` still receives the unfiltered list,
        ;; because it needs the full set to count what it is hiding.
        show-zero? (true? (:show-zero-balances? subaccounts))
        visible-assets (transfer-dropdowns/visible-transfer-assets
                        transfer-assets
                        show-zero?)
        hidden-zero-count (transfer-dropdowns/hidden-zero-balance-count
                           transfer-assets
                           show-zero?)
        selected-asset (transfer-dropdowns/selected-transfer-token subaccounts visible-assets)
        max-label (or (:available-display selected-asset)
                      (if withdrawing? withdraw-max deposit-max))
        selected-available (:available selected-asset)
        unresolved? (true? (:unresolved? selected-asset))
        submit-disabled? (or unresolved?
                             (not (pos? (or selected-available 0))))
        symbol (or (:symbol selected-asset) "USDC")]
    [:div {:data-role (str "subaccounts-transfer-popover-" address)
           :class (into ["relative"
                         "w-[min(92vw,32.5rem)]"
                         "max-h-[calc(100vh-7rem)]"
                         "overflow-y-auto"
                         "rounded-xl"
                         "border"
                         "border-[#294145]"
                         "bg-[#0b171b]"
                         "p-6"
                         "text-left"
                         "shadow-[0_24px_80px_rgba(0,0,0,0.48)]"]
                        class)}
     [:button {:type "button"
               :data-role (str "subaccounts-transfer-close-" address)
               :aria-label "Close transfer"
               :class ["absolute"
                       "right-5"
                       "top-5"
                       "text-xl"
                       "leading-none"
                       "text-[#9aa8ab]"
                       "transition-colors"
                       "hover:text-white"]
               :on {:click [[:actions/cancel-transfer-subaccount]]}}
      "x"]
     [:div {:class ["mb-6" "pr-8" "text-center"]}
      [:h3 {:class ["text-2xl" "font-semibold" "leading-tight" "text-white"]}
       "Send Tokens"]
      [:p {:class ["mt-2" "text-sm" "text-[#d6dddf]"]}
       "Transfer tokens between sub-account and master account."]]
     (transfer-direction-summary {:address address
                                  :direction direction
                                  :subaccount-name subaccount-name})
     [:div {:class ["mt-5" "grid" "gap-3" "sm:grid-cols-2"]}
      (transfer-field-shell
       {:children [(transfer-dropdowns/transfer-account-dropdown
                    {:address address
                     :subaccounts subaccounts
                     :unified-account? unified-account?})]})
      (transfer-field-shell
       {:class ["relative"]
        :children [(transfer-dropdowns/token-dropdown {:address address
                                                       :subaccounts subaccounts
                                                       :transfer-assets transfer-assets})]})]
     ;; Offered only when it would change something: rows are hidden right now,
     ;; or they are shown and the user needs a way back.
     (when (or (pos? hidden-zero-count) show-zero?)
       (transfer-dropdowns/zero-balance-toggle {:address address
                                                :show-zero? show-zero?
                                                :hidden-count hidden-zero-count}))
     [:div {:class ["mt-3"]}
      (transfer-field-shell
       {:class ["justify-between" "gap-3"]
        :children [[:input {:type "text"
                            :data-role (str "subaccounts-transfer-amount-" address)
                            :value (:transfer-amount subaccounts)
                            :placeholder "Amount"
                            :aria-label "Transfer amount"
                            :class ["min-w-0"
                                    "flex-1"
                                    "border-0"
                                    "bg-transparent"
                                    "text-sm"
                                    "text-white"
                                    "outline-none"
                                    "placeholder:text-[#9aa8ab]"
                                    "focus:border-0"
                                    "focus:outline-none"
                                    "focus:ring-0"]
                            :style {:border "0"
                                    :box-shadow "none"}
                            :on {:input [[:actions/set-subaccount-form-field
                                          :transfer-amount
                                          [:event.target/value]]]}}]
                   [:span {:data-role (str "subaccounts-transfer-max-" address)
                           :class ["shrink-0" "text-xs" "font-medium" "text-ho-accent"]}
                    (str "MAX: " max-label " " symbol)]]})
      ;; An asset spot metadata cannot name has no wire token id, and signing a
      ;; bare symbol is rejected by the exchange, so say so instead of failing
      ;; at submit time.
      (when unresolved?
        [:p {:data-role (str "subaccounts-transfer-unresolved-" address)
             :class ["mt-2" "text-xs" "text-ho-warn"]}
         (str symbol " cannot be sent right now. "
              transfer-dropdowns/unresolved-token-message ".")])]
     [:div {:class ["mt-6" "grid" "grid-cols-2" "gap-3"]}
      (action-button {:data-role (str "subaccounts-transfer-cancel-" address)
                      :label "Cancel"
                      :class ["w-full"]
                      :on-click [[:actions/cancel-transfer-subaccount]]})
      (action-button {:data-role (str "subaccounts-transfer-submit-" address)
                      :label "Send"
                      :variant :primary
                      :disabled? submit-disabled?
                      :class ["w-full"]
                      :on-click [[:actions/submit-transfer-subaccount address]]})]]))

(defn- transfer-controls
  [{:keys [address subaccounts]}]
  (let [active? (= address (:transferring-address subaccounts))]
    [:div {:class ["relative" "flex" "min-w-0" "justify-end"]}
     (action-button {:data-role (str "subaccounts-transfer-" address)
                     :label "Transfer"
                     :disabled? active?
                     :on-click [[:actions/start-transfer-subaccount address]]})]))

(defn transfer-popover-layer
  [{:keys [address subaccount-name subaccounts deposit-max withdraw-max transfer-assets unified-account?]}]
  (when (seq address)
    [:div {:class ["relative"
                   "z-[40]"
                   "flex"
                   "w-full"
                   "justify-center"
                   "lg:justify-end"]
           :data-role "subaccounts-transfer-popover-layer"}
     (transfer-popover {:address address
                        :subaccount-name subaccount-name
                        :subaccounts subaccounts
                        :deposit-max deposit-max
                        :withdraw-max withdraw-max
                        :transfer-assets transfer-assets
                        :unified-account? unified-account?
                        :class []})]))

(defn row-controls
  [{:keys [address subaccounts read-only?]}]
  (when-not read-only?
    [:div {:class ["flex" "min-w-[10rem]" "flex-wrap" "items-start" "justify-end" "gap-2"]}
     (rename-controls {:address address
                       :subaccounts subaccounts})
     (transfer-controls {:address address
                         :subaccounts subaccounts})]))
