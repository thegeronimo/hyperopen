(ns hyperopen.views.funding-modal.send
  (:require [hyperopen.views.funding-modal.shared :as shared]))

(defn- send-asset-field
  [{:keys [symbol prefix-label]}]
  [:div {:class ["flex"
                 "items-center"
                 "rounded-lg"
                 "border"
                 "border-[#28474b]"
                 "bg-[#0c2028]"
                 "px-3"
                 "py-3"
                 "gap-2"
                 "min-w-0"]}
   [:span {:class ["truncate" "text-sm" "font-semibold" "text-[#e6eff2]"]}
    (or symbol "Asset")]
   [:div {:class ["flex" "items-center" "gap-2" "min-w-0"]}
    (when (seq prefix-label)
      [:span {:class ["inline-flex"
                      "items-center"
                      "rounded-lg"
                      "bg-[#242924]"
                      "px-3"
                      "py-[1px]"
                      "text-xs"
                      "font-medium"
                      "leading-none"
                      "text-emerald-300"]}
       prefix-label])]])

(defn render-content
  [{:keys [asset destination amount actions]}]
  [:div {:class ["space-y-4"] :data-role "funding-send-step"}
   [:p {:class ["text-sm" "leading-6" "text-[#8fa7ae]"]}
    "Send tokens to another account on the Hyperliquid L1."]
   [:div {:class ["space-y-2"]}
    [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
     "Destination"]
    [:input {:type "text"
             :placeholder "0x..."
             :disabled (get-in actions [:submitting?])
             :value (:value destination)
             :class ["w-full"
                     "rounded-lg"
                     "border"
                     "border-[#28474b]"
                     "bg-[#0c2028]"
                     "px-3"
                     "py-2.5"
                     "text-sm"
                     "text-[#e6eff2]"
                     "outline-none"
                     "focus:border-[#4f8f87]"
                     "disabled:cursor-not-allowed"
                     "disabled:opacity-70"]
             :data-role "funding-send-destination-input"
             :on {:input [[:actions/set-funding-modal-field
                           [:destination-input]
                           [:event.target/value]]]}}]]
   [:div {:class ["grid" "grid-cols-2" "gap-3"]}
    [:div {:class ["space-y-2"]}
     [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
      "Account"]
     [:div {:class ["flex"
                    "items-center"
                    "justify-between"
                    "rounded-lg"
                    "border"
                    "border-[#28474b]"
                    "bg-[#0c2028]"
                    "px-3"
                    "py-3"]}
      [:span {:class ["text-sm" "font-semibold" "text-[#e6eff2]"]} "Trading Account"]]]
    [:div {:class ["space-y-2"]}
     [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
      "Asset"]
     (send-asset-field asset)]]
   (shared/amount-input-field {:label "Amount"
                               :value (:value amount)
                               :placeholder "Enter amount"
                               :disabled? (get-in actions [:submitting?])
                               :input-action :actions/set-funding-modal-field
                               :input-args [[:amount-input]]
                               :max-action :actions/set-funding-amount-to-max
                               :max-label (when (seq (:max-display amount))
                                            (str "MAX: " (:max-display amount)
                                                 (when (seq (:symbol amount))
                                                   (str " " (:symbol amount)))))
                               :suffix (:symbol amount)
                               :data-role "funding-send-amount-input"})
   [:button {:type "button"
             :disabled (get-in actions [:submit-disabled?])
             :class (into ["w-full"] (shared/submit-button-classes (get-in actions [:submit-disabled?])))
             :on {:click [[:actions/submit-funding-send]]}}
    (get-in actions [:submit-label])]])
