(ns hyperopen.views.account-type-modal
  (:require [hyperopen.account-type.actions :as account-type-actions]
            [hyperopen.account-type.options :as options]))

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

(defn- check-icon []
  [:svg {:viewBox "0 0 20 20"
         :class ["h-3" "w-3"]
         :fill "none"
         :stroke "currentColor"
         :stroke-width "3"
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :aria-hidden "true"}
   [:path {:d "M4 10.5 8 14.5 16 5.5"}]])

(defn- option-checkbox
  [selected?]
  [:span {:class (into ["flex"
                        "h-4"
                        "w-4"
                        "shrink-0"
                        "items-center"
                        "justify-center"
                        "rounded-[4px]"
                        "border"
                        "transition-colors"]
                       (if selected?
                         ["border-ho-accent" "bg-ho-accent" "text-ho-bg"]
                         ["border-base-300" "bg-transparent" "text-transparent"]))
          :aria-hidden "true"}
   (check-icon)])

(defn- option-row
  [{:keys [option selected? submitting?]}]
  (let [recommended? (options/recommended? option)]
    [:button {:type "button"
              :class (into ["w-full"
                            "rounded-xl"
                            "border"
                            "p-3"
                            "text-left"
                            "transition-colors"
                            "focus:outline-none"
                            "focus:ring-0"
                            "focus:ring-offset-0"]
                           (if selected?
                             ["border-[#2f7067]" "bg-[#0f2e2b]"]
                             ["border-base-300" "bg-base-100" "hover:border-[#3e5c58]"]))
              :role "menuitemradio"
              :aria-checked (boolean selected?)
              :disabled (boolean submitting?)
              :data-role (str "account-type-option-" (name option))
              :on {:click [[:actions/select-account-type option]]}}
     [:span {:class ["flex" "items-center" "gap-2.5"]}
      (option-checkbox selected?)
      [:span {:class ["text-sm"
                      "font-semibold"
                      (if selected? "text-white" "text-gray-200")]
              :data-role (str "account-type-option-title-" (name option))}
       (options/title option)
       (when recommended?
         [:span {:class ["ml-1.5" "text-xs" "font-medium" "text-trading-text-secondary"]}
          "(Recommended)"])]]
     [:span {:class ["mt-1.5" "block" "pl-[26px]" "text-xs" "leading-5" "text-trading-text-secondary"]
             :data-role (str "account-type-option-description-" (name option))}
      (options/description option)]]))

(defn account-type-modal-view
  [state]
  (let [open? (true? (get-in state [:account-type-ui :modal-open?]))
        selected (get-in state [:account-type-ui :selected])
        submitting? (true? (get-in state [:account-type-ui :submitting?]))
        error-message (get-in state [:account-type-ui :error-message])
        current-option (account-type-actions/current-option state)
        selected* (if (options/valid-option? selected) selected current-option)]
    (when open?
      [:div {:class ["fixed"
                     "inset-0"
                     "z-[295]"
                     "flex"
                     "items-center"
                     "justify-center"
                     "bg-[#041016]/80"
                     "p-4"]
             :data-role "account-type-modal-overlay"}
       [:div {:class ["w-full"
                      "max-w-md"
                      "rounded-2xl"
                      "border"
                      "border-base-300"
                      "bg-ho-bg-deep"
                      "p-4"
                      "shadow-2xl"
                      "space-y-4"]
              :role "dialog"
              :aria-modal true
              :aria-label "Account Type"
              :data-role "account-type-modal"}
        [:div {:class ["flex" "items-center" "justify-between" "gap-4"]}
         [:h2 {:class ["text-lg" "font-semibold" "text-white"]}
          "Account Type"]
         [:button {:type "button"
                   :class ["inline-flex"
                           "h-8"
                           "w-8"
                           "shrink-0"
                           "items-center"
                           "justify-center"
                           "rounded-md"
                           "text-gray-400"
                           "hover:bg-base-200"
                           "hover:text-gray-100"
                           "focus:outline-none"
                           "focus:ring-0"
                           "focus:ring-offset-0"]
                   :on {:click [[:actions/close-account-type-modal]]}
                   :aria-label "Close account type dialog"
                   :data-role "account-type-modal-close"}
          (close-icon)]]
        [:div {:class ["space-y-2" "max-h-[55vh]" "overflow-y-auto"]}
         (for [option (options/options)]
           ^{:key (name option)}
           (option-row {:option option
                        :selected? (= option selected*)
                        :submitting? submitting?}))]
        (when (seq error-message)
          [:div {:class ["rounded-lg"
                         "border"
                         "border-ho-border-sell"
                         "bg-ho-sell-soft/55"
                         "px-3"
                         "py-2"
                         "text-sm"
                         "text-ho-sell-tint"]
                 :data-role "account-type-error-message"}
           error-message])
        [:button {:type "button"
                  :disabled (or submitting? (= selected* current-option))
                  :class (into ["w-full"
                                "rounded-lg"
                                "border"
                                "px-3.5"
                                "py-2.5"
                                "text-sm"
                                "font-semibold"
                                "transition-colors"
                                "focus:outline-none"
                                "focus:ring-0"
                                "focus:ring-offset-0"]
                               (if (or submitting? (= selected* current-option))
                                 ["border-[#2a4b4b]"
                                  "bg-[#08202a]/55"
                                  "text-[#6c8e93]"
                                  "cursor-not-allowed"]
                                 ["border-ho-accent"
                                  "bg-ho-accent"
                                  "text-ho-bg"
                                  "hover:border-ho-accent-hi"
                                  "hover:bg-ho-accent-hi"]))
                  :on {:click [[:actions/submit-account-type selected*]]}
                  :data-role "account-type-modal-confirm"}
         (if submitting?
           "Awaiting signature..."
           "Establish Connection")]
        [:div {:class ["rounded-lg"
                       "border"
                       "border-ho-border-sell"
                       "bg-ho-sell-soft/55"
                       "px-3"
                       "py-2"
                       "text-xs"
                       "leading-5"
                       "text-ho-sell-tint"]
               :data-role "account-type-modal-warning"}
         "Account value must be < $25M to use portfolio margin in beta mode."]]])))
