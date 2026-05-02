(ns hyperopen.views.api-wallets.modal
  (:require [hyperopen.views.api-wallets.common :refer [format-valid-until
                                                         input-label]]
            [hyperopen.wallet.agent-session :as agent-session]))

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

(defn- remove-modal?
  [modal]
  (= :remove (:type modal)))

(defn- modal-title
  [modal]
  (if (remove-modal? modal)
    "Remove API Wallet"
    "Authorize API Wallet"))

(defn- modal-aria-label
  [modal]
  (if (remove-modal? modal)
    "Remove API wallet"
    "Authorize API wallet"))

(defn- modal-body
  [{:keys [modal form generated-private-key valid-until-preview-ms]}]
  (if (remove-modal? modal)
    (remove-modal-body {:row (:row modal)})
    (authorize-modal-body {:form form
                           :generated-private-key generated-private-key
                           :valid-until-preview-ms valid-until-preview-ms})))

(defn- modal-error-view
  [modal]
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
     (:error modal)]))

(defn- modal-confirm-classes
  [modal-confirm-disabled?]
  (into ["rounded-lg"
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
           "hover:bg-[#115046]"])))

(defn- modal-confirm-label
  [modal]
  (cond
    (true? (:submitting? modal))
    (if (remove-modal? modal)
      "Removing..."
      "Authorizing...")

    (remove-modal? modal)
    "Remove"

    :else
    "Authorize"))

(defn modal-view
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
            :aria-label (modal-aria-label modal)
            :data-role "api-wallets-modal"}
      [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
       [:h2 {:class ["text-lg" "font-semibold" "text-white"]}
        (modal-title modal)]
       [:button {:type "button"
                 :class ["text-sm"
                         "text-trading-text-secondary"
                         "hover:text-trading-text"
                         "focus:outline-none"
                         "focus:ring-0"
                         "focus:ring-offset-0"]
                 :on {:click [[:actions/close-api-wallet-modal]]}}
        "Close"]]
      (modal-body {:modal modal
                   :form form
                   :generated-private-key generated-private-key
                   :valid-until-preview-ms valid-until-preview-ms})
      (modal-error-view modal)
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
                 :class (modal-confirm-classes modal-confirm-disabled?)
                 :data-role "api-wallets-modal-confirm"
                 :on {:click [[:actions/confirm-api-wallet-modal]]}}
        (modal-confirm-label modal)]]]]))
