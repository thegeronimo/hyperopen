(ns hyperopen.views.funding-modal
  (:require [hyperopen.funding.actions :as funding-actions]
            [hyperopen.views.funding-modal.deposit :as deposit]
            [hyperopen.views.funding-modal.send :as send]
            [hyperopen.views.funding-modal.transfer :as transfer]
            [hyperopen.views.funding-modal.withdraw :as withdraw]
            [hyperopen.views.ui.funding-modal-positioning
             :as funding-modal-positioning]))

(defn- legacy-content
  [{:keys [message]}]
  [:div {:class ["space-y-3"]}
   [:p {:class ["text-sm" "text-[#b9cbd0]"]}
    message]
   [:div {:class ["flex" "justify-end"]}
    [:button {:type "button"
              :class ["rounded-lg"
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
              :on {:click [[:actions/close-funding-modal]]}}
     "Close"]]])

(defn- unknown-content
  [{:keys [kind]}]
  [:div {:class ["space-y-3"] :data-role "funding-unknown-content"}
   [:div {:class ["rounded-lg"
                  "border"
                  "border-[#7b3340]"
                  "bg-[#3a1b22]/55"
                  "px-3"
                  "py-3"
                  "space-y-1.5"]}
    [:p {:class ["text-sm" "text-[#f2b8c5]"]}
     "This funding modal state is not supported yet."]
    [:p {:class ["text-xs" "text-[#d7b8c0]"]}
     (str "Unhandled content kind: " (pr-str kind))]]
   [:div {:class ["flex" "justify-end"]}
    [:button {:type "button"
              :class ["rounded-lg"
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
              :on {:click [[:actions/close-funding-modal]]}}
     "Close"]]])

(defn- render-content
  [{:keys [content deposit send transfer withdraw legacy]}]
  (case (:kind content)
    :deposit/select (deposit/deposit-select-content deposit)
    :deposit/address (deposit/deposit-address-content deposit)
    :deposit/amount (deposit/deposit-amount-content deposit)
    :deposit/unavailable (deposit/deposit-unavailable-content deposit)
    :deposit/missing-asset (deposit/deposit-missing-asset-content deposit)
    :send/form (send/render-content send)
    :transfer/form (transfer/render-content transfer)
    :withdraw/select (withdraw/withdraw-select-content withdraw)
    :withdraw/detail (withdraw/withdraw-detail-content withdraw)
    :unsupported/workflow (legacy-content legacy)
    (unknown-content content)))

(defn render-funding-modal
  [{:keys [modal feedback] :as view-model}]
  (let [open? (:open? modal)]
    (when open?
      (let [{:keys [mobile-sheet?
                    anchored-popover?
                    popover-style
                    sheet-style]}
            (funding-modal-positioning/resolve-modal-layout modal)
            panel-children
            [[:div {:class ["flex" "items-center" "justify-between"]}
              [:h2 {:class ["text-lg" "font-semibold" "text-[#e5eef1]"]}
               (:title modal)]
              [:button {:type "button"
                        :aria-label "Close funding dialog"
                        :class (into ["h-8"
                                      "w-8"
                                      "leading-none"
                                      "text-xl"
                                      "transition-colors"
                                      "focus:outline-none"
                                      "focus:ring-1"
                                      "focus:ring-[#66e3c5]/40"
                                      "focus:ring-offset-0"
                                      "focus:shadow-none"]
                                     (if mobile-sheet?
                                       ["inline-flex"
                                        "items-center"
                                        "justify-center"
                                        "rounded-lg"
                                        "border"
                                        "border-[#17313d]"
                                        "bg-[#0b181d]"
                                        "text-gray-300"
                                        "hover:bg-[#102229]"
                                        "hover:text-gray-100"]
                                       ["rounded-md"
                                        "text-[#7f98a0]"
                                        "hover:bg-[#0f2834]"
                                        "hover:text-[#dce9ee]"]))
                        :on {:click [[:actions/close-funding-modal]]}}
               "×"]]
             (render-content view-model)
             (when (:visible? feedback)
               [:div {:class ["rounded-md"
                              "border"
                              "border-[#7b3340]"
                              "bg-[#3a1b22]/55"
                              "px-3"
                              "py-2"
                              "text-sm"
                              "text-[#f2b8c5]"]
                      :data-role "funding-status"}
                (:message feedback)])]]
        (if mobile-sheet?
          [:div {:class ["fixed" "inset-0" "z-[80]"]
                 :data-role "funding-mobile-sheet-layer"}
           [:button {:type "button"
                     :class ["absolute" "inset-0" "bg-black/55" "backdrop-blur-[1px]"]
                     :style {:transition "opacity 0.14s ease-out"
                             :opacity 1}
                     :replicant/mounting {:style {:opacity 0}}
                     :replicant/unmounting {:style {:opacity 0}}
                     :aria-label "Close funding dialog"
                     :data-role "funding-mobile-sheet-backdrop"
                     :on {:click [[:actions/close-funding-modal]]}}]
           (into [:div {:class ["absolute"
                                "inset-x-0"
                                "bottom-0"
                                "w-full"
                                "overflow-y-auto"
                                "rounded-t-[22px]"
                                "border"
                                "border-[#17313d]"
                                "bg-[#06131a]"
                                "px-4"
                                "pt-4"
                                "text-sm"
                                "shadow-[0_-24px_60px_rgba(0,0,0,0.45)]"
                                "space-y-3"]
                        :style sheet-style
                        :replicant/mounting {:style {:transform "translateY(18px)"
                                                     :opacity 0}}
                        :replicant/unmounting {:style {:transform "translateY(18px)"
                                                       :opacity 0}}
                        :role "dialog"
                        :aria-modal true
                        :aria-label (:title modal)
                        :tab-index 0
                        :data-role "funding-modal"
                        :data-parity-id "funding-modal-mobile"
                        :data-funding-mobile-sheet-surface "true"
                        :on {:keydown [[:actions/handle-funding-modal-keydown
                                        [:event/key]]]}}]
                 (keep identity panel-children))]
          [:div {:class (into ["fixed" "inset-0" "z-[80]"]
                              (if anchored-popover?
                                ["pointer-events-none"]
                                ["flex" "items-center" "justify-center" "p-4"]))}
           [:button {:type "button"
                     :class (into ["absolute" "inset-0"]
                                  (if anchored-popover?
                                    ["pointer-events-auto" "bg-transparent"]
                                    ["bg-black/65"]))
                     :aria-label "Close funding dialog"
                     :on {:click [[:actions/close-funding-modal]]}}]
           (into [:div {:class (into ["relative"
                                      "z-[81]"
                                      "space-y-3"
                                      "border"
                                      "border-[#1f3b3c]"
                                      "bg-[#081b24]"
                                      "shadow-2xl"
                                      "pointer-events-auto"]
                                     (cond-> ["rounded-2xl" "p-4"]
                                       (not anchored-popover?)
                                       (conj "w-full" "max-w-md")))
                          :style (or popover-style)
                          :role "dialog"
                          :aria-modal true
                          :aria-label (:title modal)
                          :tab-index 0
                          :data-role "funding-modal"
                          :data-parity-id "funding-modal-desktop"
                          :on {:keydown [[:actions/handle-funding-modal-keydown
                                          [:event/key]]]}}]
                 (keep identity panel-children))])))))

(defn funding-modal-view
  [state]
  (render-funding-modal (funding-actions/funding-modal-view-model state)))
