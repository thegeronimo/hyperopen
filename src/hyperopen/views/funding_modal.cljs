(ns hyperopen.views.funding-modal
  (:require [hyperopen.funding.actions :as funding-actions]))

(defn- base-button-classes
  [primary?]
  (if primary?
    ["rounded-lg"
     "border"
     "border-[#2f625a]"
     "bg-[#0d3a35]"
     "px-3.5"
     "py-2"
     "text-sm"
     "font-medium"
     "text-[#daf3ef]"
     "hover:border-[#3f7f75]"
     "hover:bg-[#115046]"]
    ["rounded-lg"
     "border"
     "border-[#2c4b50]"
     "bg-transparent"
     "px-3.5"
     "py-2"
     "text-sm"
     "text-[#b7c8cc]"
     "hover:border-[#3d666b]"
     "hover:text-[#e5eef1]"]))

(defn- submit-button-classes
  [disabled?]
  (if disabled?
    ["rounded-lg"
     "border"
     "border-[#2a4b4b]"
     "bg-[#08202a]/55"
     "px-3.5"
     "py-2"
     "text-sm"
     "font-medium"
     "text-[#6c8e93]"
     "cursor-not-allowed"]
    (base-button-classes true)))

(defn funding-modal-view
  [state]
  (let [{:keys [open?
                mode
                legacy-kind
                title
                amount-input
                to-perp?
                destination-input
                max-display
                max-input
                submitting?
                submit-disabled?
                status-message
                submit-label
                min-withdraw-usdc]} (funding-actions/funding-modal-view-model state)
        deposit? (= mode :deposit)
        transfer? (= mode :transfer)
        withdraw? (= mode :withdraw)
        legacy? (= mode :legacy)]
    (when open?
      [:div {:class ["fixed" "inset-0" "z-[80]" "flex" "items-center" "justify-center" "p-4"]}
       [:div {:class ["absolute" "inset-0" "bg-black/65"]
              :on {:click [[:actions/close-funding-modal]]}}]
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
              :data-role "funding-modal"
              :on {:keydown [[:actions/handle-funding-modal-keydown [:event/key]]]}}
        [:div {:class ["flex" "items-center" "justify-between"]}
         [:h2 {:class ["text-lg" "font-semibold" "text-[#e5eef1]"]}
          title]
         [:button {:type "button"
                   :class ["text-sm" "text-[#8ea4ab]" "hover:text-[#e5eef1]"]
                   :on {:click [[:actions/close-funding-modal]]}}
          "Close"]]

        (when deposit?
          [:div {:class ["space-y-3"]}
           [:p {:class ["text-sm" "text-[#b9cbd0]"]}
            "Deposits are credited after one Arbitrum confirmation."]
           [:p {:class ["text-sm" "text-[#8ea4ab]"]}
            "Open the deposit bridge page to fund your account."]
           [:div {:class ["flex" "justify-end" "gap-2"]}
            [:button {:type "button"
                      :class (base-button-classes false)
                      :on {:click [[:actions/close-funding-modal]]}}
             "Cancel"]
            [:button {:type "button"
                      :class (base-button-classes true)
                      :on {:click [[:actions/close-funding-modal]
                                   [:actions/navigate "/portfolio/deposit"]]}}
             "Open Deposit Page"]]])

        (when transfer?
          [:div {:class ["space-y-3"]}
           [:div {:class ["grid" "grid-cols-2" "gap-2"]}
            [:button {:type "button"
                      :class (if to-perp?
                               (base-button-classes true)
                               (base-button-classes false))
                      :disabled submitting?
                      :on {:click [[:actions/set-funding-transfer-direction true]]}}
             "Spot -> Perps"]
            [:button {:type "button"
                      :class (if to-perp?
                               (base-button-classes false)
                               (base-button-classes true))
                      :disabled submitting?
                      :on {:click [[:actions/set-funding-transfer-direction false]]}}
             "Perps -> Spot"]]
           [:div {:class ["space-y-2"]}
            [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
             [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
              "Amount (USDC)"]
             [:button {:type "button"
                       :disabled submitting?
                       :class (if submitting?
                                ["text-xs" "font-medium" "tracking-[0.03em]" "text-[#6f868c]" "cursor-not-allowed"]
                                ["text-xs" "font-medium" "tracking-[0.03em]" "text-[#5de6da]" "hover:text-[#8bf3ea]"])
                       :on {:click [[:actions/set-funding-modal-field [:amount-input] max-input]]}}
              (str "MAX: " max-display " USDC")]]
            [:input {:type "text"
                     :input-mode "decimal"
                     :placeholder "Enter amount"
                     :disabled submitting?
                     :value amount-input
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
                     :on {:input [[:actions/set-funding-modal-field [:amount-input] [:event.target/value]]]}}]]
           [:div {:class ["flex" "justify-end" "gap-2"]}
            [:button {:type "button"
                      :class (base-button-classes false)
                      :on {:click [[:actions/close-funding-modal]]}}
             "Cancel"]
            [:button {:type "button"
                      :disabled submit-disabled?
                      :class (submit-button-classes submit-disabled?)
                      :on {:click [[:actions/submit-funding-transfer]]}}
             submit-label]]])

        (when withdraw?
          [:div {:class ["space-y-3"]}
           [:div {:class ["space-y-2"]}
            [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
             "Destination Address"]
            [:input {:type "text"
                     :placeholder "0x..."
                     :disabled submitting?
                     :value destination-input
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
                     :on {:input [[:actions/set-funding-modal-field
                                   [:destination-input]
                                   [:event.target/value]]]}}]]
           [:div {:class ["space-y-2"]}
            [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
             [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
              "Amount (USDC)"]
             [:button {:type "button"
                       :disabled submitting?
                       :class (if submitting?
                                ["text-xs" "font-medium" "tracking-[0.03em]" "text-[#6f868c]" "cursor-not-allowed"]
                                ["text-xs" "font-medium" "tracking-[0.03em]" "text-[#5de6da]" "hover:text-[#8bf3ea]"])
                       :on {:click [[:actions/set-funding-modal-field [:amount-input] max-input]]}}
              (str "MAX: " max-display " USDC")]]
            [:input {:type "text"
                     :input-mode "decimal"
                     :placeholder "Enter amount"
                     :disabled submitting?
                     :value amount-input
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
                     :on {:input [[:actions/set-funding-modal-field [:amount-input] [:event.target/value]]]}}]]
           [:p {:class ["text-xs" "text-[#8ea4ab]"]}
            (str "Minimum withdrawal: " min-withdraw-usdc " USDC.")]
           [:div {:class ["flex" "justify-end" "gap-2"]}
            [:button {:type "button"
                      :class (base-button-classes false)
                      :on {:click [[:actions/close-funding-modal]]}}
             "Cancel"]
            [:button {:type "button"
                      :disabled submit-disabled?
                      :class (submit-button-classes submit-disabled?)
                      :on {:click [[:actions/submit-funding-withdraw]]}}
             submit-label]]])

        (when legacy?
          [:div {:class ["space-y-3"]}
           [:p {:class ["text-sm" "text-[#b9cbd0]"]}
            (str "The " (name legacy-kind) " funding workflow is not available yet.")]
           [:div {:class ["flex" "justify-end"]}
            [:button {:type "button"
                      :class (base-button-classes true)
                      :on {:click [[:actions/close-funding-modal]]}}
             "Close"]]])

        (when (and (seq status-message)
                   (not legacy?)
                   (not deposit?))
          [:div {:class ["rounded-md"
                         "border"
                         "border-[#7b3340]"
                         "bg-[#3a1b22]/55"
                         "px-3"
                         "py-2"
                         "text-sm"
                         "text-[#f2b8c5]"]
                 :data-role "funding-status"}
           status-message])]])))
