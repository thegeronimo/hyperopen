(ns hyperopen.views.api-wallets-view
  (:require [hyperopen.views.api-wallets.form :as form]
            [hyperopen.views.api-wallets.modal :as modal]
            [hyperopen.views.api-wallets.rows :as rows]
            [hyperopen.views.api-wallets.vm :as api-wallets-vm]
            [hyperopen.wallet.core :as wallet]))

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
     (form/form-card {:form form
                      :form-errors form-errors
                      :form-error form-error
                      :authorize-disabled? authorize-disabled?})
     (rows/rows-section {:rows rows
                         :sort sort
                         :loading? loading?
                         :error error})
     (modal/modal-view {:modal modal
                        :form form
                        :generated-private-key generated-private-key
                        :valid-until-preview-ms valid-until-preview-ms
                        :modal-confirm-disabled? modal-confirm-disabled?})]))

(defn ^:export route-view
  [state]
  (api-wallets-view state))

(goog/exportSymbol "hyperopen.views.api_wallets_view.route_view" route-view)
