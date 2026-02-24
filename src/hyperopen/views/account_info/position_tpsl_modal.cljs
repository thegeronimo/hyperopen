(ns hyperopen.views.account-info.position-tpsl-modal
  (:require [hyperopen.account.history.position-tpsl :as position-tpsl]
            [hyperopen.domain.trading :as trading-domain]
            [hyperopen.views.account-info.shared :as shared]))

(defn- amount-text [value]
  (if (and (number? value) (not (js/isNaN value)))
    (trading-domain/number->clean-string value 8)
    "0"))

(defn- coin-label [coin]
  (let [parsed (shared/parse-coin-namespace coin)]
    (or (:base parsed)
        (shared/non-blank-text coin)
        "-")))

(defn- metric-row [label value]
  [:div {:class ["flex" "items-center" "justify-between"]}
   [:span {:class ["text-gray-400"]} label]
   [:span {:class ["font-semibold" "text-gray-100" "num"]} value]])

(defn- checkbox-row [id label checked? on-change]
  [:div {:class ["inline-flex" "items-center" "gap-2"]}
   [:input {:id id
            :type "checkbox"
            :class ["h-4"
                    "w-4"
                    "rounded-[3px]"
                    "border"
                    "border-base-300"
                    "bg-transparent"
                    "trade-toggle-checkbox"
                    "transition-colors"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"
                    "focus:shadow-none"]
            :checked (boolean checked?)
            :on {:change on-change}}]
   [:label {:for id
            :class ["cursor-pointer" "select-none" "text-sm" "text-gray-100"]}
    label]])

(defn- input-row [label value action]
  [:div {:class ["relative" "w-full"]}
   [:span {:class ["pointer-events-none"
                   "absolute"
                   "left-3"
                   "top-1/2"
                   "-translate-y-1/2"
                   "text-sm"
                   "text-gray-500"]}
    label]
   [:input (cond-> {:class ["h-10"
                            "w-full"
                            "rounded-lg"
                            "border"
                            "border-base-300"
                            "bg-base-200"
                            "pl-24"
                            "pr-3"
                            "text-right"
                            "text-sm"
                            "font-semibold"
                            "text-gray-100"
                            "num"
                            "focus:outline-none"
                            "focus:ring-1"
                            "focus:ring-[#8a96a6]/40"]
                    :type "text"
                    :value (or value "")}
             (some? action) (assoc :on {:input action})
             (nil? action) (assoc :readonly true))]])

(defn position-tpsl-modal-view [state]
  (let [modal (or (get-in state [:positions-ui :tpsl-modal])
                  (position-tpsl/default-modal-state))]
    (when (position-tpsl/open? modal)
      (let [preview (position-tpsl/validate-modal modal)
            submitting? (boolean (:submitting? modal))
            submit-label (if submitting?
                           "Submitting..."
                           (:display-message preview))
            submit-disabled? (or submitting?
                                (not (:is-ok preview)))
            coin (coin-label (:coin modal))
            active-size (position-tpsl/active-size modal)
            gain (position-tpsl/estimated-gain-usd modal)
            loss (position-tpsl/estimated-loss-usd modal)]
        [:div {:class ["fixed" "inset-0" "z-[260]" "flex" "items-center" "justify-center" "px-3"]
               :role "dialog"
               :aria-modal "true"
               :on {:keydown [[:actions/handle-position-tpsl-modal-keydown :event/key]]}}
         [:div {:class ["absolute" "inset-0" "bg-black/60"]
                :on {:click [[:actions/close-position-tpsl-modal]]}}]
         [:div {:class ["relative"
                        "w-full"
                        "max-w-[500px]"
                        "rounded-[10px]"
                        "border"
                        "border-[#273449]"
                        "bg-[#0A1422]"
                        "p-4"
                        "text-sm"
                        "shadow-[0_24px_60px_rgba(0,0,0,0.45)]"
                        "space-y-3"]}
          [:div {:class ["flex" "items-center" "justify-between"]}
           [:h2 {:class ["text-2xl" "font-semibold" "text-gray-100"]} "Position TP/SL"]
           [:button {:type "button"
                     :class ["h-7" "w-7" "rounded-md" "text-gray-400" "hover:bg-base-300" "hover:text-gray-100"]
                     :on {:click [[:actions/close-position-tpsl-modal]]}}
            "x"]]

          [:div {:class ["space-y-1.5"]}
           (metric-row "Asset" coin)
           (metric-row "Size" (str (amount-text active-size) " " coin))
           (metric-row "Value" (str (shared/format-currency (:position-value modal)) " USDC"))
           (metric-row "Entry Price" (shared/format-trade-price (:entry-price modal)))
           (metric-row "Mark Price" (shared/format-trade-price (:mark-price modal)))]

          [:div {:class ["grid" "grid-cols-2" "gap-2"]}
           (input-row "TP Price"
                      (:tp-price modal)
                      [[:actions/set-position-tpsl-modal-field [:tp-price] :event.target/value]])
           (input-row "Gain"
                      (str (shared/format-currency gain) " $")
                      nil)]

          (when (boolean (:limit-price? modal))
            [:div {:class ["grid" "grid-cols-2" "gap-2"]}
             (input-row "TP Limit"
                        (:tp-limit modal)
                        [[:actions/set-position-tpsl-modal-field [:tp-limit] :event.target/value]])
             [:div]])

          [:div {:class ["grid" "grid-cols-2" "gap-2"]}
           (input-row "SL Price"
                      (:sl-price modal)
                      [[:actions/set-position-tpsl-modal-field [:sl-price] :event.target/value]])
           (input-row "Loss"
                      (str (shared/format-currency loss) " $")
                      nil)]

          (when (boolean (:limit-price? modal))
            [:div {:class ["grid" "grid-cols-2" "gap-2"]}
             (input-row "SL Limit"
                        (:sl-limit modal)
                        [[:actions/set-position-tpsl-modal-field [:sl-limit] :event.target/value]])
             [:div]])

          (when (boolean (:configure-amount? modal))
            (input-row "Amount"
                       (:size-input modal)
                       [[:actions/set-position-tpsl-modal-field [:size-input] :event.target/value]]))

          [:div {:class ["space-y-1"]}
           (checkbox-row "position-tpsl-configure-amount"
                         "Configure Amount"
                         (:configure-amount? modal)
                         [[:actions/set-position-tpsl-configure-amount :event.target/checked]])
           (checkbox-row "position-tpsl-limit-price"
                         "Limit Price"
                         (:limit-price? modal)
                         [[:actions/set-position-tpsl-limit-price :event.target/checked]])]

          (when (seq (:error modal))
            [:div {:class ["text-xs" "text-[#ED7088]"]} (:error modal)])

          [:div {:class ["grid" "grid-cols-2" "gap-3" "pt-1"]}
           [:button {:type "button"
                     :class ["h-11"
                             "rounded-lg"
                             "bg-[#74808F]"
                             "text-sm"
                             "font-semibold"
                             "text-[#1A212B]"
                             "hover:bg-[#8893a0]"
                             "disabled:cursor-not-allowed"
                             "disabled:opacity-50"]
                     :disabled submit-disabled?
                     :on {:click [[:actions/submit-position-tpsl]]}}
            submit-label]
           [:button {:type "button"
                     :class ["h-11"
                             "rounded-lg"
                             "border"
                             "border-base-300"
                             "bg-base-200"
                             "text-sm"
                             "font-semibold"
                             "text-gray-100"
                             "hover:bg-base-300"]
                     :on {:click [[:actions/close-position-tpsl-modal]]}}
            "Close"]]]]))))
