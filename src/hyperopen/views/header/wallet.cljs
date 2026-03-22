(ns hyperopen.views.header.wallet
  (:require [hyperopen.views.header.icons :as icons]))

(defn- wallet-copy-feedback-row
  [{:keys [kind message]}]
  (let [text-classes (if (= :success kind)
                       ["text-success"]
                       ["text-error"])]
    [:div {:class (into ["flex"
                         "items-center"
                         "gap-1.5"
                         "px-3"
                         "pb-2"
                         "text-xs"
                         "font-medium"]
                        text-classes)
           :data-role "wallet-copy-feedback"}
     (icons/feedback-icon
      kind
      {:class ["h-3.5" "w-3.5"]
       :data-role (if (= :success kind)
                    "wallet-copy-feedback-success-icon"
                    "wallet-copy-feedback-error-icon")})
     [:span message]]))

(defn- enable-trading-button
  [{:keys [action disabled? label]}]
  (when label
    [:button {:type "button"
              :class ["mx-3"
                      "mb-2"
                      "mt-1"
                      "block"
                      "w-[calc(100%-1.5rem)]"
                      "rounded-lg"
                      "bg-teal-600"
                      "px-3"
                      "py-2"
                      "text-sm"
                      "font-medium"
                      "text-teal-100"
                      "transition-colors"
                      "hover:bg-teal-700"
                      "disabled:cursor-not-allowed"
                      "disabled:opacity-60"]
              :disabled disabled?
              :on {:click action}
              :data-role "wallet-enable-trading"}
     label]))

(defn- wallet-menu
  [{:keys [copy-action copy-feedback disconnect-action enable-trading menu-address-label]}]
  (let [enable-trading-cta (enable-trading-button enable-trading)]
    [:div {:class ["ui-dropdown-panel"
                   "absolute"
                   "right-0"
                   "top-full"
                   "mt-2"
                   "w-48"
                   "overflow-hidden"
                   "rounded-xl"
                   "border"
                   "border-base-300"
                   "bg-trading-bg"
                   "isolate"
                   "shadow-2xl"
                   "z-[260]"]
           :data-ui-native-details-panel "true"
           :data-role "wallet-menu-panel"}
     [:button {:type "button"
               :class ["flex"
                       "w-full"
                       "items-center"
                       "justify-between"
                       "gap-2"
                       "px-3"
                       "py-3"
                       "text-left"
                       "text-sm"
                       "text-white"
                       "transition-colors"
                       "hover:bg-base-200"
                       "focus:outline-none"]
               :on {:click copy-action}
               :title "Copy address"
               :aria-label "Copy address"
               :data-role "wallet-menu-copy"}
      [:span {:class ["truncate" "num"]} menu-address-label]
      (icons/wallet-copy-icon)]
     (when (and (map? copy-feedback)
                (seq (:message copy-feedback)))
       (wallet-copy-feedback-row copy-feedback))
     (when enable-trading-cta
       enable-trading-cta)
     (when enable-trading-cta
       [:div {:class ["h-px" "w-full" "bg-base-300"]}])
     [:button {:type "button"
               :class ["block"
                       "w-full"
                       "px-3"
                       "py-3"
                       "text-left"
                       "text-sm"
                       "font-medium"
                       "text-[#50f6d2]"
                       "transition-colors"
                       "hover:bg-base-200"
                       "focus:outline-none"]
               :on {:click disconnect-action}
               :data-role "wallet-menu-disconnect"}
      "Disconnect"]]))

(defn- wallet-trigger
  [{:keys [trigger-label]}]
  [:summary {:class ["relative"
                     "z-[170]"
                     "inline-flex"
                     "h-9"
                     "sm:h-10"
                     "items-center"
                     "gap-2"
                     "rounded-xl"
                     "border"
                     "border-base-300"
                     "bg-base-100"
                     "px-2.5"
                     "sm:px-3"
                     "text-xs"
                     "sm:text-sm"
                     "text-white"
                     "transition-colors"
                     "hover:bg-base-200"
                     "list-none"
                     "cursor-pointer"]
             :data-role "wallet-menu-trigger"
             :aria-haspopup "menu"}
   [:span {:class ["num"]} trigger-label]
   (icons/chevron-down-icon
    {:class ["h-4" "w-4" "text-gray-300" "transition-transform" "group-open:rotate-180"]
     :data-role "wallet-menu-chevron"})])

(defn- connect-wallet-button
  [{:keys [connect-action connecting?]}]
  (into
   [:button {:class ["bg-teal-600"
                     "hover:bg-teal-700"
                     "text-teal-100"
                     "inline-flex"
                     "h-9"
                     "items-center"
                     "justify-center"
                     "px-3"
                     "rounded-lg"
                     "text-xs"
                     "sm:text-sm"
                     "font-medium"
                     "transition-colors"]
             :disabled connecting?
             :on {:click connect-action}
             :data-role "wallet-connect-button"}]
   (if connecting?
     [[:span {:class ["sm:hidden"]} "Connecting…"]
      [:span {:class ["hidden" "sm:inline"]} "Connecting…"]]
     [[:span {:class ["sm:hidden"]} "Connect"]
      [:span {:class ["hidden" "sm:inline"]} "Connect Wallet"]])))

(defn render
  [{:keys [connected?] :as wallet}]
  (if connected?
    [:details {:class ["relative" "group"] :data-role "wallet-menu-details"}
     (wallet-trigger wallet)
     (wallet-menu wallet)]
    (connect-wallet-button wallet)))
