(ns hyperopen.views.api-wallets.form
  (:require [hyperopen.views.api-wallets.common :refer [inline-error
                                                         input-label
                                                         text-input]]))

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
(defn form-card
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
    [:div {:class ["flex" "flex-col" "justify-start" "lg:gap-1.5" "lg:pt-2"]}
     [:span {:class ["hidden"
                     "lg:block"
                     "invisible"
                     "text-xs"
                     "font-semibold"
                     "uppercase"
                     "tracking-[0.08em]"]}
      "Authorize API Wallet"]
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
