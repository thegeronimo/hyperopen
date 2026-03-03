(ns hyperopen.views.vaults.detail.transfer-modal)

(defn hero-transfer-button
  [{:keys [label enabled? action]}]
  [:button
   {:type "button"
    :disabled (not enabled?)
    :class (into ["rounded-lg"
                  "border"
                  "px-4"
                  "py-2"
                  "text-sm"
                  "transition-colors"]
                 (if enabled?
                   ["border-[#2d5551]"
                    "bg-[#0a2830]/70"
                    "text-[#d7ecef]"
                    "hover:border-[#3b736e]"
                    "hover:bg-[#12323a]"]
                   ["border-[#2a4b4b]"
                    "bg-[#08202a]/55"
                    "text-[#6c8e93]"
                    "opacity-70"
                    "cursor-not-allowed"]))
    :on {:click [action]}}
   label])

(defn vault-transfer-modal-view
  [{:keys [open?
           title
           mode
           deposit-max-display
           deposit-max-input
           deposit-lockup-copy
           amount-input
           withdraw-all?
           submitting?
           error
           preview-ok?
           preview-message
           confirm-label
           submit-disabled?]}]
  (when open?
    (let [show-deposit? (= mode :deposit)
          show-withdraw-all? (= mode :withdraw)
          status-message (or error
                             (when (and (not preview-ok?)
                                        (seq preview-message))
                               preview-message))]
      [:div {:class ["fixed" "inset-0" "z-[80]" "flex" "items-center" "justify-center" "p-4"]}
       [:div {:class ["absolute" "inset-0" "bg-black/65"]
              :on {:click [[:actions/close-vault-transfer-modal]]}}]
       [:div {:class ["relative"
                      "z-[81]"
                      "w-full"
                      "max-w-md"
                      "rounded-2xl"
                      "border"
                      "border-[#1f3b3c]"
                      "bg-[#081b24]"
                      "p-4"
                      "shadow-2xl"
                      "space-y-3"]
              :role "dialog"
              :aria-modal true
              :aria-label title
              :tab-index 0
              :data-role "vault-transfer-modal"
              :on {:keydown [[:actions/handle-vault-transfer-modal-keydown [:event/key]]]}}
        [:div {:class ["flex" "items-center" "justify-between"]}
         [:h2 {:class ["text-lg" "font-semibold" "text-[#e5eef1]"]}
          title]
         [:button {:type "button"
                   :class ["text-sm" "text-[#8ea4ab]" "hover:text-[#e5eef1]"]
                   :on {:click [[:actions/close-vault-transfer-modal]]}}
          "Close"]]
        (when (and show-deposit?
                   (seq deposit-lockup-copy))
          [:p {:class ["text-sm" "text-[#b5c7cd]"]
               :data-role "vault-transfer-deposit-lockup-copy"}
           deposit-lockup-copy])
        [:div {:class ["space-y-2"]}
         [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
          [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
           "Amount (USDC)"]
          (when show-deposit?
            [:button {:type "button"
                      :disabled submitting?
                      :class (into ["text-xs"
                                    "font-medium"
                                    "tracking-[0.03em]"]
                                   (if submitting?
                                     ["text-[#6f868c]" "cursor-not-allowed"]
                                     ["text-[#5de6da]" "hover:text-[#8bf3ea]"]))
                      :data-role "vault-transfer-deposit-max"
                      :on {:click [[:actions/set-vault-transfer-amount
                                    (or deposit-max-input "0")]]}}
             (str "MAX: " (or deposit-max-display "0.00") " USDC")])]
         [:input {:type "text"
                  :input-mode "decimal"
                  :placeholder (if show-withdraw-all?
                                 "Enter amount or use Withdraw All"
                                 "Enter amount")
                  :disabled (or submitting?
                                (and show-withdraw-all?
                                     withdraw-all?))
                  :value (or amount-input "")
                  :class ["w-full"
                          "rounded-lg"
                          "border"
                          "border-[#28474b]"
                          "bg-[#0c2028]"
                          "px-3"
                          "py-2"
                          "text-sm"
                          "text-[#e6eff2]"
                          "outline-none"
                          "focus:border-[#4f8f87]"
                          "disabled:cursor-not-allowed"
                          "disabled:opacity-70"]
                  :data-role "vault-transfer-amount-input"
                  :on {:input [[:actions/set-vault-transfer-amount [:event.target/value]]]}}]
         (when show-withdraw-all?
           [:label {:class ["inline-flex" "items-center" "gap-2" "text-sm" "text-[#b9cbd0]"]}
            [:input {:type "checkbox"
                     :checked (boolean withdraw-all?)
                     :disabled submitting?
                     :on {:change [[:actions/set-vault-transfer-withdraw-all :event.target/checked]]}}]
            "Withdraw All"])]
        (when (seq status-message)
          [:div {:class ["rounded-md"
                         "border"
                         "border-[#7b3340]"
                         "bg-[#3a1b22]/55"
                         "px-3"
                         "py-2"
                         "text-sm"
                         "text-[#f2b8c5]"]
                 :data-role "vault-transfer-status"}
           status-message])
        [:div {:class ["flex" "justify-end" "gap-2" "pt-1"]}
         [:button {:type "button"
                   :class ["rounded-lg"
                           "border"
                           "border-[#2c4b50]"
                           "px-3.5"
                           "py-2"
                           "text-sm"
                           "text-[#b7c8cc]"
                           "hover:border-[#3d666b]"
                           "hover:text-[#e5eef1]"]
                   :on {:click [[:actions/close-vault-transfer-modal]]}}
          "Cancel"]
         [:button {:type "button"
                   :disabled submit-disabled?
                   :class (into ["rounded-lg"
                                 "border"
                                 "px-3.5"
                                 "py-2"
                                 "text-sm"
                                 "font-medium"]
                                (if submit-disabled?
                                  ["border-[#2a4b4b]"
                                   "bg-[#08202a]/55"
                                   "text-[#6c8e93]"
                                   "cursor-not-allowed"]
                                  ["border-[#2f625a]"
                                   "bg-[#0d3a35]"
                                   "text-[#daf3ef]"
                                   "hover:border-[#3f7f75]"
                                   "hover:bg-[#115046]"]))
                   :data-role "vault-transfer-submit"
                   :on {:click [[:actions/submit-vault-transfer]]}}
          confirm-label]]]])))
