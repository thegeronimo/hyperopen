(ns hyperopen.views.order-submit-confirmation-modal
  (:require [hyperopen.order.submit-confirmation :as submit-confirmation]))

(defn- close-icon []
  [:svg {:viewBox "0 0 20 20"
         :class ["h-4" "w-4"]
         :fill "none"
         :stroke "currentColor"
         :stroke-width "1.8"
         :stroke-linecap "round"
         :aria-hidden "true"}
   [:path {:d "M5 5 15 15"}]
   [:path {:d "M15 5 5 15"}]])

(defn- confirmation-copy
  [variant]
  (case variant
    :close-position
    {:eyebrow "Close Position"
     :title "Submit Close Order?"
     :body "This close order will be sent immediately to the exchange."
     :hint "Turn off close-position confirmation in Trading settings if you prefer one-click closes."
     :cancel-label "Keep Position"
     :confirm-label "Submit Close Order"}

    {:eyebrow "Open Order"
     :title "Submit Order?"
     :body "This order will be sent immediately to the exchange."
     :hint "Turn off open-order confirmation in Trading settings if you prefer one-click submits."
     :cancel-label "Keep Editing"
     :confirm-label "Submit Order"}))

(defn order-submit-confirmation-modal-view
  [state]
  (let [confirmation (or (:order-submit-confirmation state)
                         (submit-confirmation/default-state))]
    (when (submit-confirmation/open? confirmation)
      (let [{:keys [eyebrow
                    title
                    body
                    hint
                    cancel-label
                    confirm-label]}
            (confirmation-copy (:variant confirmation))]
        [:div {:class ["fixed"
                       "inset-0"
                       "z-[305]"
                       "flex"
                       "items-center"
                       "justify-center"
                       "p-4"
                       "md:p-6"]
               :data-role "order-submit-confirmation-overlay"}
         [:button {:type "button"
                   :class ["ui-confirmation-backdrop"
                           "absolute"
                           "inset-0"
                           "bg-[#02070b]/80"
                           "backdrop-blur-[2px]"]
                   :aria-label "Dismiss order confirmation"
                   :data-role "order-submit-confirmation-backdrop"
                   :on {:click [[:actions/dismiss-order-submission-confirmation]]}}]
         [:div {:class ["ui-confirmation-panel"
                        "relative"
                        "z-[306]"
                        "w-full"
                        "max-w-[26rem]"
                        "overflow-hidden"
                        "rounded-[1.35rem]"
                        "border"
                        "border-[#1a3b40]"
                        "bg-[#07161d]"
                        "shadow-[0_32px_80px_rgba(0,0,0,0.55)]"]
                :role "dialog"
                :aria-modal true
                :aria-label title
                :tab-index 0
                :data-role "order-submit-confirmation-dialog"
                :on {:keydown [[:actions/handle-order-submission-confirmation-keydown
                                [:event/key]]]}}
          [:div {:class ["absolute"
                         "inset-x-0"
                         "top-0"
                         "h-px"
                         "bg-[linear-gradient(90deg,rgba(80,210,193,0.08),rgba(80,210,193,0.95),rgba(80,210,193,0.08))]"]}]
          [:div {:class ["absolute"
                         "-left-16"
                         "-top-20"
                         "h-40"
                         "w-40"
                         "rounded-full"
                         "bg-[radial-gradient(circle,rgba(80,210,193,0.14),rgba(80,210,193,0)_70%)]"]}]
          [:div {:class ["relative" "space-y-5" "px-5" "py-5" "sm:px-6"]}
           [:div {:class ["flex" "items-start" "justify-between" "gap-4"]}
            [:div {:class ["space-y-3"]}
             [:div {:class ["inline-flex"
                            "items-center"
                            "rounded-full"
                            "border"
                            "border-[#20484c]"
                            "bg-[#0a222a]"
                            "px-2.5"
                            "py-1"
                            "text-xs"
                            "font-semibold"
                            "uppercase"
                            "tracking-[0.16em]"
                            "text-[#8edbcf]"]
                    :data-role "order-submit-confirmation-eyebrow"}
              eyebrow]
             [:div {:class ["space-y-2"]}
              [:h2 {:class ["text-[1.4rem]"
                            "font-semibold"
                            "leading-tight"
                            "text-[#f3fbfa]"]
                    :data-role "order-submit-confirmation-title"}
               title]
              [:p {:class ["max-w-[30ch]"
                           "text-sm"
                           "leading-6"
                           "text-[#d6e5e4]"]
                    :data-role "order-submit-confirmation-description"}
               body]]]
            [:button {:type "button"
                      :class ["inline-flex"
                              "h-9"
                              "w-9"
                              "shrink-0"
                              "items-center"
                              "justify-center"
                              "rounded-xl"
                              "border"
                              "border-[#17343b]"
                              "bg-[#091c23]"
                              "text-[#8ea3a8]"
                              "transition-colors"
                              "hover:border-[#25525d]"
                              "hover:text-[#edf7f6]"
                              "focus:outline-none"
                              "focus-visible:ring-2"
                              "focus-visible:ring-[#6be2d2]/60"
                              "focus-visible:ring-offset-2"
                              "focus-visible:ring-offset-[#07161d]"]
                      :aria-label "Close order confirmation"
                      :data-role "order-submit-confirmation-close"
                      :on {:click [[:actions/dismiss-order-submission-confirmation]]}}
             (close-icon)]]
           [:div {:class ["rounded-[1.1rem]"
                          "border"
                          "border-[#163138]"
                          "bg-[#081e25]/88"
                          "px-4"
                          "py-3.5"]}
            [:p {:class ["text-sm"
                         "leading-6"
                         "text-[#96abae]"]
                 :data-role "order-submit-confirmation-hint"}
             hint]]
           [:div {:class ["flex" "justify-end" "gap-2.5" "pt-1"]}
            [:button {:type "button"
                      :class ["rounded-xl"
                              "border"
                              "border-[#29474c]"
                              "bg-transparent"
                              "px-3.5"
                              "py-2.5"
                              "text-sm"
                              "font-medium"
                              "text-[#c5d5d6]"
                              "transition-colors"
                              "hover:border-[#3c676d]"
                              "hover:text-[#eef8f7]"
                              "focus:outline-none"
                              "focus-visible:ring-2"
                              "focus-visible:ring-[#6be2d2]/50"
                              "focus-visible:ring-offset-2"
                              "focus-visible:ring-offset-[#07161d]"]
                      :data-role "order-submit-confirmation-cancel"
                      :on {:click [[:actions/dismiss-order-submission-confirmation]]}}
             cancel-label]
            [:button {:type "button"
                      :autofocus true
                      :class ["rounded-xl"
                              "border"
                              "border-[#2f625a]"
                              "bg-[#0d3a35]"
                              "px-4"
                              "py-2.5"
                              "text-sm"
                              "font-semibold"
                              "text-[#daf3ef]"
                              "transition-colors"
                              "hover:border-[#3f7f75]"
                              "hover:bg-[#115046]"
                              "focus:outline-none"
                              "focus-visible:ring-2"
                              "focus-visible:ring-[#6be2d2]/60"
                              "focus-visible:ring-offset-2"
                              "focus-visible:ring-offset-[#07161d]"]
                      :data-role "order-submit-confirmation-submit"
                      :on {:click [[:actions/confirm-order-submission]]}}
             confirm-label]]]]]))))
