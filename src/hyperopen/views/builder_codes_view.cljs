(ns hyperopen.views.builder-codes-view
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.builder-codes.actions :as builder-codes-actions]
            [hyperopen.wallet.core :as wallet]))

(defn- remove-button
  [{:keys [address revoking]}]
  (let [removing? (= revoking address)]
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
                      "focus:ring-offset-0"
                      "disabled:cursor-not-allowed"
                      "disabled:opacity-60"]
              :disabled removing?
              :data-role "builder-codes-remove-button"
              :on {:click [[:actions/revoke-builder address]]}}
     (if removing?
       "Removing..."
       "Remove")]))

(defn- builder-row
  [{:keys [row revoking]}]
  (let [{:keys [address max-fee]} row]
    [:tr {:class ["border-b"
                  "border-base-300/50"
                  "text-sm"
                  "text-trading-text"
                  "hover:bg-base-200/40"]
          :data-role "builder-codes-table-row"}
     [:td {:class ["px-3" "py-3" "text-left"]}
      [:span {:class ["num" "break-all" "font-medium" "text-white"]}
       address]]
     [:td {:class ["px-3" "py-3" "text-left" "num"]}
      (or max-fee "—")]
     [:td {:class ["px-3" "py-3" "text-left"]}
      (remove-button {:address address
                      :revoking revoking})]]))

(defn- builder-mobile-row
  [{:keys [row revoking]}]
  (let [{:keys [address max-fee]} row]
    [:div {:class ["rounded-xl"
                   "border"
                   "border-base-300"
                   "bg-base-100"
                   "p-3"
                   "space-y-2"]
           :data-role "builder-codes-mobile-row"}
     [:div {:class ["flex" "items-start" "justify-between" "gap-2"]}
      [:div {:class ["space-y-1"]}
       [:div {:class ["num" "break-all" "text-sm" "font-medium" "text-white"]}
        address]
       [:div {:class ["text-xs" "text-trading-text-secondary"]}
        [:span "Max fee "]
        [:span {:class ["num" "text-trading-text"]}
         (or max-fee "—")]]]
      (remove-button {:address address
                      :revoking revoking})]]))

(defn- rows-section
  [{:keys [rows loading? error revoking connected?]}]
  [:section {:class ["rounded-xl"
                     "border"
                     "border-base-300"
                     "bg-base-100"
                     "overflow-hidden"]
             :data-role "builder-codes-rows-card"}
   [:div {:class ["hidden" "md:block" "overflow-x-auto"]}
    [:table {:class ["min-w-full"]
             :data-role "builder-codes-table"}
     [:thead {:class ["bg-base-100"]}
      [:tr {:class ["text-xs" "text-trading-text-secondary"]}
       [:th {:class ["px-3" "py-2" "text-left"]}
        "Builder Address"]
       [:th {:class ["px-3" "py-2" "text-left"]}
        "Max Fee"]
       [:th {:class ["px-3" "py-2" "text-left"]}
        "Action"]]]
     [:tbody
      (cond
        (and (seq rows) connected?)
        (for [row rows]
          ^{:key (:address row)}
          (builder-row {:row row
                        :revoking revoking}))

        loading?
        [:tr
         [:td {:col-span 3
               :class ["px-3" "py-8" "text-center" "text-sm" "text-trading-text-secondary"]}
          "Loading builder codes..."]]

        (seq error)
        [:tr
         [:td {:col-span 3
               :class ["px-3" "py-8" "text-center" "text-sm" "text-ho-sell-tint"]}
          error
          [:button {:type "button"
                    :class ["ml-3"
                            "rounded-lg"
                            "border"
                            "border-[#2f625a]"
                            "bg-ho-accent-soft"
                            "px-3"
                            "py-1.5"
                            "text-xs"
                            "font-medium"
                            "text-[#daf3ef]"]
                    :data-role "builder-codes-retry-button"
                    :on {:click [[:actions/load-builder-codes-route
                                  builder-codes-actions/canonical-route]]}}
           "Retry"]]]

        :else
        [:tr
         [:td {:col-span 3
               :class ["px-3" "py-8" "text-center" "text-sm" "text-trading-text-secondary"]}
          "No builder codes have been approved."]])]]]
   [:div {:class ["grid" "gap-2" "p-3" "md:hidden"]}
    (cond
      (and (seq rows) connected?)
      (for [row rows]
        ^{:key (:address row)}
        (builder-mobile-row {:row row
                             :revoking revoking}))

      loading?
      [:div {:class ["rounded-xl"
                     "border"
                     "border-base-300"
                     "bg-base-100"
                     "px-3"
                     "py-6"
                     "text-center"
                     "text-sm"
                     "text-trading-text-secondary"]}
       "Loading builder codes..."]

      (seq error)
      [:div {:class ["rounded-xl"
                     "border"
                     "border-ho-border-sell"
                     "bg-ho-sell-soft/55"
                     "px-3"
                     "py-6"
                     "text-center"
                     "text-sm"
                     "text-ho-sell-tint"]}
       error
       [:div {:class ["mt-3"]}
        [:button {:type "button"
                  :class ["rounded-lg"
                          "border"
                          "border-[#2f625a]"
                          "bg-ho-accent-soft"
                          "px-3"
                          "py-1.5"
                          "text-xs"
                          "font-medium"
                          "text-[#daf3ef]"]
                  :on {:click [[:actions/load-builder-codes-route
                                builder-codes-actions/canonical-route]]}}
         "Retry"]]]

      :else
      [:div {:class ["rounded-xl"
                     "border"
                     "border-base-300"
                     "bg-base-100"
                     "px-3"
                     "py-6"
                     "text-center"
                     "text-sm"
                     "text-trading-text-secondary"]}
       "No builder codes have been approved."])]])

(defn builder-codes-view
  [state]
  (let [owner-address (account-context/owner-address state)
        spectating? (account-context/spectate-mode-active? state)
        error-message (get-in state [:builder-codes-ui :error-message])
        success-message (get-in state [:builder-codes-ui :success-message])
        revoking (get-in state [:builder-codes :revoking])
        loading? (true? (get-in state [:builder-codes :loading?]))
        error (get-in state [:builder-codes :error])
        rows (vec (get-in state [:builder-codes :rows]))
        connected? (boolean (seq owner-address))]
    [:div {:class ["app-shell-gutter"
                   "flex"
                   "w-full"
                   "flex-col"
                   "gap-4"
                   "pt-4"
                   "pb-16"]
           :data-parity-id "builder-codes-root"}
     [:section {:class ["rounded-xl"
                        "border"
                        "border-base-300"
                        "bg-base-100"
                        "p-4"
                        "space-y-2"]}
      [:h1 {:class ["text-2xl" "font-semibold" "text-white"]}
       "Builder Codes"]
      [:p {:class ["max-w-3xl" "text-sm" "text-trading-text-secondary"]}
       "Builder codes allow applications to charge a small fee on the orders that they send on your behalf. Each builder must be approved by the user. Approved builder codes can be removed here."]
      (if connected?
        [:div {:class ["flex" "flex-wrap" "items-center" "gap-2" "text-xs" "text-trading-text-secondary"]}
         [:span "Managing builder codes for"]
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
         "Connect a wallet to manage builder codes."])]
     (rows-section {:rows rows
                    :loading? loading?
                    :error error
                    :revoking revoking
                    :connected? connected?})
     (when (seq error-message)
       [:div {:class ["rounded-lg"
                      "border"
                      "border-ho-border-sell"
                      "bg-ho-sell-soft/55"
                      "px-3"
                      "py-2"
                      "text-sm"
                      "text-ho-sell-tint"]
              :data-role "builder-codes-error-message"}
        error-message])
     (when (seq success-message)
       [:div {:class ["rounded-lg"
                      "border"
                      "border-[#2f625a]"
                      "bg-ho-accent-soft"
                      "px-3"
                      "py-2"
                      "text-sm"
                      "text-[#daf3ef]"]
              :data-role "builder-codes-success-message"}
        success-message])]))

(defn ^:export route-view
  [state]
  (builder-codes-view state))

(goog/exportSymbol "hyperopen.views.builder_codes_view.route_view" route-view)
