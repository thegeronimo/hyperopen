(ns hyperopen.views.api-wallets-view
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.api-wallets.vm :as api-wallets-vm]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.core :as wallet]))

(defn- input-label
  [label]
  [:label {:class ["text-xs"
                   "font-semibold"
                   "uppercase"
                   "tracking-[0.08em]"
                   "text-trading-text-secondary"]}
   label])

(defn- text-input
  [{:keys [id value placeholder on-input disabled?]}]
  [:input {:id id
           :type "text"
           :value (or value "")
           :placeholder placeholder
           :disabled disabled?
           :class ["h-11"
                   "w-full"
                   "rounded-xl"
                   "border"
                   "border-base-300"
                   "bg-base-100"
                   "px-3"
                   "text-sm"
                   "text-trading-text"
                   "focus:outline-none"
                   "focus:ring-0"
                   "focus:ring-offset-0"
                   "disabled:cursor-not-allowed"
                   "disabled:opacity-60"]
           :on {:input [on-input]}}])

(defn- inline-error
  [message]
  (when (seq message)
    [:p {:class ["text-xs" "text-[#f2b8c5]"]}
     message]))

(defn- generate-address-input
  [{:keys [value disabled?]}]
  [:div {:class ["flex"
                 "items-center"
                 "gap-2"
                 "rounded-xl"
                 "border"
                 "border-base-300"
                 "bg-base-100"
                 "px-1"
                 "focus-within:border-[#4f8f87]"]}
   [:input {:id "api-wallet-address"
            :type "text"
            :value (or value "")
            :placeholder "0x..."
            :disabled disabled?
            :class ["h-11"
                    "min-w-0"
                    "flex-1"
                    "border-0"
                    "bg-transparent"
                    "px-3"
                    "text-sm"
                    "text-trading-text"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"
                    "disabled:cursor-not-allowed"
                    "disabled:opacity-60"]
            :on {:input [[:actions/set-api-wallet-form-field
                          :address
                          [:event.target/value]]]}}]
   [:button {:type "button"
             :class ["rounded-lg"
                     "border"
                     "border-[#2f625a]"
                     "bg-[#0d3a35]"
                     "px-3"
                     "py-2"
                     "text-sm"
                     "font-medium"
                     "text-[#daf3ef]"
                     "transition-colors"
                     "hover:border-[#3f7f75]"
                     "hover:bg-[#115046]"
                     "focus:outline-none"
                     "focus:ring-0"
                     "focus:ring-offset-0"
                     "disabled:cursor-not-allowed"
                     "disabled:opacity-60"]
             :disabled disabled?
             :data-role "api-wallets-generate-button"
             :on {:click [[:actions/generate-api-wallet]]}}
    "Generate"]])

(defn- format-valid-until
  [value]
  (if (number? value)
    (or (fmt/format-local-date-time value) "Never")
    "Never"))

(defn- sort-direction-icon
  [direction]
  [:svg {:class (into ["h-3" "w-3" "shrink-0" "opacity-70" "transition-transform"]
                      (if (= :asc direction)
                        ["rotate-180"]
                        ["rotate-0"]))
         :viewBox "0 0 12 12"
         :aria-hidden true}
   [:path {:d "M3 4.5L6 7.5L9 4.5"
           :fill "none"
           :stroke "currentColor"
           :stroke-width "1.5"
           :stroke-linecap "round"
           :stroke-linejoin "round"}]])

(defn- sortable-header
  [label column sort]
  (let [active? (= column (:column sort))]
    [:button {:type "button"
              :class ["inline-flex"
                      "items-center"
                      "gap-1"
                      "font-normal"
                      "text-trading-text-secondary"
                      "hover:text-trading-text"
                      "focus:outline-none"
                      "focus:ring-0"
                      "focus:ring-offset-0"]
              :on {:click [[:actions/set-api-wallet-sort column]]}}
     [:span label]
     (when active?
       (sort-direction-icon (:direction sort)))]))

(defn- api-wallet-row
  [row]
  [:tr {:class ["border-b"
                "border-base-300/50"
                "text-sm"
                "text-trading-text"
                "hover:bg-base-200/40"]
        :data-role "api-wallets-table-row"}
   [:td {:class ["px-3" "py-3" "text-left"]}
    [:div {:class ["flex" "items-center" "gap-2"]}
     [:span {:class ["font-medium" "text-white"]}
      (:name row)]
     (when (= :default (:row-kind row))
       [:span {:class ["rounded-full"
                       "border"
                       "border-[#2b5d5b]"
                       "bg-[#103c39]"
                       "px-2"
                       "py-0.5"
                       "text-xs"
                       "font-semibold"
                       "uppercase"
                       "tracking-[0.08em]"
                       "text-[#9cf9e2]"]}
        "Default"])]] 
   [:td {:class ["px-3" "py-3" "text-left" "num"]}
    (:address row)]
   [:td {:class ["px-3" "py-3" "text-left" "num"]}
    (format-valid-until (:valid-until-ms row))]
   [:td {:class ["px-3" "py-3" "text-left"]}
    [:button {:type "button"
              :class ["rounded-lg"
                      "border"
                      "border-[#6a3941]"
                      "bg-[#2a151c]"
                      "px-3"
                      "py-1.5"
                      "text-xs"
                      "font-medium"
                      "text-[#f3c0cb]"
                      "transition-colors"
                      "hover:border-[#8a4b56]"
                      "hover:bg-[#341b24]"
                      "focus:outline-none"
                      "focus:ring-0"
                      "focus:ring-offset-0"]
              :on {:click [[:actions/open-api-wallet-remove-modal row]]}}
     "Remove"]]])

(defn- api-wallet-mobile-row
  [row]
  [:div {:class ["rounded-xl"
                 "border"
                 "border-base-300"
                 "bg-base-100"
                 "p-3"
                 "space-y-2"]
         :data-role "api-wallets-mobile-row"}
   [:div {:class ["flex" "items-start" "justify-between" "gap-2"]}
    [:div {:class ["space-y-1"]}
     [:div {:class ["flex" "items-center" "gap-2"]}
      [:span {:class ["text-sm" "font-medium" "text-white"]}
       (:name row)]
      (when (= :default (:row-kind row))
        [:span {:class ["rounded-full"
                        "border"
                        "border-[#2b5d5b]"
                        "bg-[#103c39]"
                        "px-2"
                        "py-0.5"
                        "text-xs"
                        "font-semibold"
                        "uppercase"
                        "tracking-[0.08em]"
                        "text-[#9cf9e2]"]}
         "Default"])]
     [:div {:class ["num" "break-all" "text-xs" "text-trading-text-secondary"]}
      (:address row)]]
    [:button {:type "button"
              :class ["rounded-lg"
                      "border"
                      "border-[#6a3941]"
                      "bg-[#2a151c]"
                      "px-3"
                      "py-1.5"
                      "text-xs"
                      "font-medium"
                      "text-[#f3c0cb]"]
              :on {:click [[:actions/open-api-wallet-remove-modal row]]}}
     "Remove"]]
   [:div {:class ["text-xs" "text-trading-text-secondary"]}
    [:span "Valid Until "]
    [:span {:class ["num" "text-trading-text"]}
     (format-valid-until (:valid-until-ms row))]]])

(defn- form-card
  [{:keys [form form-errors form-error authorize-disabled?]}]
  [:section {:class ["rounded-xl"
                     "border"
                     "border-base-300"
                     "bg-base-100"
                     "p-4"
                     "space-y-4"]}
   [:div {:class ["grid" "gap-4" "lg:grid-cols-[minmax(0,1fr)_minmax(0,1.15fr)_auto]"]}
    [:div {:class ["space-y-1.5"]}
     (input-label "API Wallet Name")
     (text-input {:id "api-wallet-name"
                  :value (:name form)
                  :placeholder "Desk wallet"
                  :on-input [:actions/set-api-wallet-form-field
                             :name
                             [:event.target/value]]})
     (inline-error (:name form-errors))]
    [:div {:class ["space-y-1.5"]}
     (input-label "API Wallet Address")
     (generate-address-input {:value (:address form)
                              :disabled? false})
     (inline-error (:address form-errors))]
    [:div {:class ["flex" "items-end"]}
     [:button {:type "button"
               :class ["h-11"
                       "w-full"
                       "rounded-xl"
                       "border"
                       "border-[#2f625a]"
                       "bg-[#0d3a35]"
                       "px-4"
                       "text-sm"
                       "font-medium"
                       "text-[#daf3ef]"
                       "transition-colors"
                       "hover:border-[#3f7f75]"
                       "hover:bg-[#115046]"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:ring-offset-0"
                       "disabled:cursor-not-allowed"
                       "disabled:opacity-60"]
               :disabled authorize-disabled?
               :data-role "api-wallets-authorize-button"
               :on {:click [[:actions/open-api-wallet-authorize-modal]]}}
      "Authorize API Wallet"]]]
   (when (seq form-error)
     [:div {:class ["rounded-lg"
                    "border"
                    "border-[#7b3340]"
                    "bg-[#3a1b22]/55"
                    "px-3"
                    "py-2"
                    "text-sm"
                    "text-[#f2b8c5]"]
            :data-role "api-wallets-form-error"}
      form-error])])

(defn- rows-section
  [{:keys [rows sort loading? error]}]
  [:section {:class ["rounded-xl"
                     "border"
                     "border-base-300"
                     "bg-base-100"
                     "overflow-hidden"]}
   [:div {:class ["hidden" "md:block" "overflow-x-auto"]}
    [:table {:class ["min-w-full"]
             :data-role "api-wallets-table"}
     [:thead {:class ["bg-base-100"]}
      [:tr {:class ["text-xs" "text-trading-text-secondary"]}
       [:th {:class ["px-3" "py-2" "text-left"]}
        (sortable-header "API Wallet Name" :name sort)]
       [:th {:class ["px-3" "py-2" "text-left"]}
        (sortable-header "API Wallet Address" :address sort)]
       [:th {:class ["px-3" "py-2" "text-left"]}
        (sortable-header "Valid Until" :valid-until sort)]
       [:th {:class ["px-3" "py-2" "text-left"]}
        "Action"]]]
     [:tbody
      (cond
        (seq rows)
        (for [row rows]
          ^{:key (str (:row-kind row) ":" (:address row) ":" (:approval-name row))}
          (api-wallet-row row))

        loading?
        [:tr
         [:td {:col-span 4
               :class ["px-3" "py-8" "text-center" "text-sm" "text-trading-text-secondary"]}
          "Loading API wallets..."]]

        (seq error)
        [:tr
         [:td {:col-span 4
               :class ["px-3" "py-8" "text-center" "text-sm" "text-[#f2b8c5]"]}
          error]]

        :else
        [:tr
         [:td {:col-span 4
               :class ["px-3" "py-8" "text-center" "text-sm" "text-trading-text-secondary"]}
          "No API wallets authorized yet."]])]]]
   [:div {:class ["grid" "gap-2" "p-3" "md:hidden"]}
    (cond
      (seq rows)
      (for [row rows]
        ^{:key (str (:row-kind row) ":" (:address row) ":" (:approval-name row))}
        (api-wallet-mobile-row row))

      loading?
      [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100" "px-3" "py-6" "text-center" "text-sm" "text-trading-text-secondary"]}
       "Loading API wallets..."]

      (seq error)
      [:div {:class ["rounded-xl" "border" "border-[#7b3340]" "bg-[#3a1b22]/55" "px-3" "py-6" "text-center" "text-sm" "text-[#f2b8c5]"]}
       error]

      :else
      [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100" "px-3" "py-6" "text-center" "text-sm" "text-trading-text-secondary"]}
       "No API wallets authorized yet."])]])

(defn- authorize-modal-body
  [{:keys [form generated-private-key valid-until-preview-ms]}]
  [:div {:class ["space-y-3"]}
   [:div {:class ["space-y-1"]}
    [:p {:class ["text-sm" "text-trading-text-secondary"]}
     "Authorize this wallet to trade on behalf of your connected address."]
    [:div {:class ["grid" "gap-2" "rounded-lg" "border" "border-base-300" "bg-base-200/30" "p-3" "text-sm"]}
     [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
      [:span {:class ["text-trading-text-secondary"]} "Name"]
      [:span {:class ["font-medium" "text-white"]} (:name form)]]
     [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
      [:span {:class ["text-trading-text-secondary"]} "Address"]
      [:span {:class ["num" "break-all" "text-right" "text-white"]} (:address form)]]]]
   [:div {:class ["space-y-1.5"]}
    (input-label "Days Valid (Optional)")
    [:input {:id "api-wallet-days-valid"
             :type "text"
             :input-mode "numeric"
             :value (:days-valid form)
             :placeholder (str "1-" agent-session/max-agent-valid-days)
             :class ["h-11"
                     "w-full"
                     "rounded-xl"
                     "border"
                     "border-base-300"
                     "bg-base-100"
                     "px-3"
                     "text-sm"
                     "text-trading-text"
                     "focus:outline-none"
                     "focus:ring-0"
                     "focus:ring-offset-0"]
             :on {:input [[:actions/set-api-wallet-form-field
                           :days-valid
                           [:event.target/value]]]}}]
    [:p {:class ["text-xs" "text-trading-text-secondary"]}
     (if (number? valid-until-preview-ms)
       (str "This API wallet will expire on "
            (format-valid-until valid-until-preview-ms)
            ".")
       "Leave blank for no expiry, or set up to 180 days.")]]
   (when (seq generated-private-key)
     [:div {:class ["rounded-lg"
                    "border"
                    "border-[#24485b]"
                    "bg-[#0c1f2c]"
                    "p-3"
                    "space-y-2"]}
      [:p {:class ["text-xs"
                   "font-semibold"
                   "uppercase"
                   "tracking-[0.08em]"
                   "text-[#8ea4ab]"]}
       "Generated Private Key"]
      [:p {:class ["text-xs" "text-[#b9cbd0]"]}
       "This private key is shown once. Store it before confirming."]
      [:pre {:class ["overflow-x-auto"
                     "rounded-md"
                     "bg-[#08141d]"
                     "p-2"
                     "text-xs"
                     "text-[#dce9ee]"]}
       generated-private-key]])])

(defn- remove-modal-body
  [{:keys [row]}]
  [:div {:class ["space-y-3"]}
   [:p {:class ["text-sm" "text-trading-text-secondary"]}
    "Removing this API wallet revokes its access immediately."]
   [:div {:class ["grid"
                  "gap-2"
                  "rounded-lg"
                  "border"
                  "border-base-300"
                  "bg-base-200/30"
                  "p-3"
                  "text-sm"]}
    [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
     [:span {:class ["text-trading-text-secondary"]} "Name"]
     [:span {:class ["font-medium" "text-white"]} (:name row)]]
    [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
     [:span {:class ["text-trading-text-secondary"]} "Address"]
     [:span {:class ["num" "break-all" "text-right" "text-white"]} (:address row)]]
    [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
     [:span {:class ["text-trading-text-secondary"]} "Valid Until"]
     [:span {:class ["num" "text-white"]} (format-valid-until (:valid-until-ms row))]]]])

(defn- modal-view
  [{:keys [modal
           form
           generated-private-key
           valid-until-preview-ms
           modal-confirm-disabled?]}]
  (when (:open? modal)
    [:div {:class ["fixed"
                   "inset-0"
                   "z-[280]"
                   "flex"
                   "items-center"
                   "justify-center"
                   "bg-[#041016]/75"
                   "p-4"]
           :data-role "api-wallets-modal-overlay"}
     [:div {:class ["w-full"
                    "max-w-lg"
                    "rounded-2xl"
                    "border"
                    "border-base-300"
                    "bg-[#081b24]"
                    "p-4"
                    "shadow-2xl"
                    "space-y-4"]
            :role "dialog"
            :aria-modal true
            :aria-label (if (= :remove (:type modal))
                          "Remove API wallet"
                          "Authorize API wallet")
            :data-role "api-wallets-modal"}
      [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
       [:h2 {:class ["text-lg" "font-semibold" "text-white"]}
        (if (= :remove (:type modal))
          "Remove API Wallet"
          "Authorize API Wallet")]
       [:button {:type "button"
                 :class ["text-sm"
                         "text-trading-text-secondary"
                         "hover:text-trading-text"
                         "focus:outline-none"
                         "focus:ring-0"
                         "focus:ring-offset-0"]
                 :on {:click [[:actions/close-api-wallet-modal]]}}
        "Close"]]
      (case (:type modal)
        :remove (remove-modal-body {:row (:row modal)})
        (authorize-modal-body {:form form
                               :generated-private-key generated-private-key
                               :valid-until-preview-ms valid-until-preview-ms}))
      (when (seq (:error modal))
        [:div {:class ["rounded-lg"
                       "border"
                       "border-[#7b3340]"
                       "bg-[#3a1b22]/55"
                       "px-3"
                       "py-2"
                       "text-sm"
                       "text-[#f2b8c5]"]
                :data-role "api-wallets-modal-error"}
         (:error modal)])
      [:div {:class ["flex" "justify-end" "gap-2"]}
       [:button {:type "button"
                 :class ["rounded-lg"
                         "border"
                         "border-[#2c4b50]"
                         "px-3.5"
                         "py-2"
                         "text-sm"
                         "text-[#b7c8cc]"
                         "hover:border-[#3d666b]"
                         "hover:text-[#e5eef1]"
                         "focus:outline-none"
                         "focus:ring-0"
                         "focus:ring-offset-0"]
                 :disabled (true? (:submitting? modal))
                 :on {:click [[:actions/close-api-wallet-modal]]}}
        "Cancel"]
       [:button {:type "button"
                 :disabled modal-confirm-disabled?
                 :class (into ["rounded-lg"
                               "border"
                               "px-3.5"
                               "py-2"
                               "text-sm"
                               "font-medium"]
                              (if modal-confirm-disabled?
                                ["border-[#2a4b4b]"
                                 "bg-[#08202a]/55"
                                 "text-[#6c8e93]"
                                 "cursor-not-allowed"]
                                ["border-[#2f625a]"
                                 "bg-[#0d3a35]"
                                 "text-[#daf3ef]"
                                 "hover:border-[#3f7f75]"
                                 "hover:bg-[#115046]"]))
                 :data-role "api-wallets-modal-confirm"
                 :on {:click [[:actions/confirm-api-wallet-modal]]}}
        (cond
          (true? (:submitting? modal))
          (if (= :remove (:type modal))
            "Removing..."
            "Authorizing...")

          (= :remove (:type modal))
          "Remove"

          :else
          "Authorize")]]]]))

(defn api-wallets-view
  [state]
  (let [{:keys [connected?
                owner-address
                spectating?
                rows
                sort
                loading?
                error
                form
                form-errors
                form-error
                authorize-disabled?
                modal
                generated-private-key
                valid-until-preview-ms
                modal-confirm-disabled?]} (api-wallets-vm/api-wallets-vm state)]
    [:div {:class ["app-shell-gutter"
                   "flex"
                   "w-full"
                   "flex-col"
                   "gap-4"
                   "pt-4"
                   "pb-16"]
           :data-parity-id "api-wallets-root"}
     [:section {:class ["rounded-xl"
                        "border"
                        "border-base-300"
                        "bg-base-100"
                        "p-4"
                        "space-y-2"]}
      [:h1 {:class ["text-2xl" "font-semibold" "text-white"]}
       "API"]
      [:p {:class ["max-w-3xl" "text-sm" "text-trading-text-secondary"]}
       "Authorize and remove API wallets for your connected Hyperopen account."]
      (if connected?
        [:div {:class ["flex" "flex-wrap" "items-center" "gap-2" "text-xs" "text-trading-text-secondary"]}
         [:span "Managing API wallets for"]
         [:span {:class ["rounded-full"
                         "border"
                         "border-base-300"
                         "bg-base-200/40"
                         "px-2.5"
                         "py-1"
                         "font-medium"
                         "num"
                         "text-trading-text"]}
          (or (wallet/short-addr owner-address) owner-address)]
         (when spectating?
           [:span {:class ["text-[#9fdad0]"]}
            "while spectating another wallet elsewhere in the app."])]
        [:p {:class ["text-sm" "text-trading-text-secondary"]}
         "Connect a wallet to manage API access."])]
     (form-card {:form form
                 :form-errors form-errors
                 :form-error form-error
                 :authorize-disabled? authorize-disabled?})
     (rows-section {:rows rows
                    :sort sort
                    :loading? loading?
                    :error error})
     (modal-view {:modal modal
                  :form form
                  :generated-private-key generated-private-key
                  :valid-until-preview-ms valid-until-preview-ms
                  :modal-confirm-disabled? modal-confirm-disabled?})]))
