(ns hyperopen.views.funding-modal.transfer
  (:require [hyperopen.views.funding-modal.shared :as shared]))

(defn render-content
  [{:keys [to-perp? amount actions]}]
  [:div {:class ["space-y-3"]}
   [:div {:class ["grid" "grid-cols-2" "gap-2"]}
    [:button {:type "button"
              :class (if to-perp?
                       (shared/base-button-classes true)
                       (shared/base-button-classes false))
              :disabled (get-in actions [:submitting?])
              :on {:click [[:actions/set-funding-transfer-direction true]]}}
     "Spot -> Perps"]
    [:button {:type "button"
              :class (if to-perp?
                       (shared/base-button-classes false)
                       (shared/base-button-classes true))
              :disabled (get-in actions [:submitting?])
              :on {:click [[:actions/set-funding-transfer-direction false]]}}
     "Perps -> Spot"]]
   (shared/amount-input-field {:label "Amount (USDC)"
                               :value (:value amount)
                               :placeholder "Enter amount"
                               :disabled? (get-in actions [:submitting?])
                               :input-action :actions/enter-funding-transfer-amount
                               :max-action :actions/set-funding-amount-to-max
                               :max-label (str "MAX: " (:max-display amount) " USDC")
                               :data-role "funding-transfer-amount-input"})
   (shared/action-row {:cancel-action :actions/close-funding-modal
                       :cancel-label "Cancel"
                       :submit-action :actions/submit-funding-transfer
                       :submit-label (get-in actions [:submit-label])
                       :submit-disabled? (get-in actions [:submit-disabled?])})])
