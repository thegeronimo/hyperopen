(ns hyperopen.views.app-view
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api-wallets.actions :as api-wallets-actions]
            [hyperopen.funding-comparison.actions :as funding-actions]
            [hyperopen.views.funding-modal :as funding-modal]
            [hyperopen.views.footer-view :as footer-view]
            [hyperopen.views.funding-comparison-view :as funding-comparison-view]
            [hyperopen.views.api-wallets-view :as api-wallets-view]
            [hyperopen.views.spectate-mode-modal :as spectate-mode-modal]
            [hyperopen.views.header-view :as header-view]
            [hyperopen.views.notifications-view :as notifications-view]
            [hyperopen.views.vaults.detail-view :as vault-detail-view]
            [hyperopen.views.vaults.list-view :as vaults-view]
            [hyperopen.views.vaults.vm :as vault-vm]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.trade-view :as trade-view]
            [hyperopen.wallet.core :as wallet]))

(defn- spectate-mode-banner
  [state]
  (let [spectate-active? (account-context/spectate-mode-active? state)
        spectate-address (account-context/spectate-address state)]
    (when (and spectate-active?
               (seq spectate-address))
      [:div {:class ["border-b"
                     "border-[#1f4746]"
                     "bg-[#072426]"
                     "text-[#d3f5ef]"]
             :data-role "spectate-mode-active-banner"}
       [:div {:class ["app-shell-gutter"
                      "flex"
                      "flex-wrap"
                      "items-center"
                      "justify-between"
                      "gap-2"
                      "py-2.5"]}
        [:div {:class ["min-w-0" "flex" "items-center" "gap-2"]}
         [:span {:class ["rounded-md"
                         "border"
                         "border-[#2a6863]"
                         "bg-[#0c3a35]"
                         "px-2"
                         "py-0.5"
                         "text-xs"
                         "font-semibold"
                         "uppercase"
                         "tracking-[0.08em]"]}
          "Spectate Mode"]
         [:span {:class ["text-sm" "text-[#b4d9d4]"]}
          "Currently spectating"]
         [:span {:class ["text-sm" "font-semibold" "num"]
                 :data-role "spectate-mode-active-banner-address"}
          (or (wallet/short-addr spectate-address) spectate-address)]]
        [:div {:class ["flex" "items-center" "gap-2"]}
         [:button {:type "button"
                   :class ["rounded-lg"
                           "border"
                           "border-[#2d585a]"
                           "bg-transparent"
                           "px-3"
                           "py-1.5"
                           "text-xs"
                           "font-medium"
                           "text-[#b7d3d0]"
                           "transition-colors"
                           "hover:border-[#3e7478]"
                           "hover:text-[#e2f4f2]"]
                   :on {:click [[:actions/open-spectate-mode-modal :event.currentTarget/bounds]]}
                   :data-spectate-mode-trigger "true"
                   :data-role "spectate-mode-banner-manage"}
          "Manage"]
         [:button {:type "button"
                   :class ["rounded-lg"
                           "border"
                           "border-[#2f7067]"
                           "bg-[#0f433d]"
                           "px-3"
                           "py-1.5"
                           "text-xs"
                           "font-semibold"
                           "text-[#dbf7f2]"
                           "transition-colors"
                           "hover:bg-[#14544c]"]
                   :on {:click [[:actions/stop-spectate-mode]]}
                   :data-role "spectate-mode-banner-stop"}
          "Stop Spectate Mode"]]]])))

(defn app-view [state]
  (let [route (get-in state [:router :path] "/trade")
        trade-route? (str/starts-with? route "/trade")
        portfolio-route? (str/starts-with? route "/portfolio")
        funding-route? (funding-actions/funding-comparison-route? route)
        api-wallet-route? (api-wallets-actions/api-wallet-route? route)
        vault-route? (vault-vm/vault-route? route)
        vault-detail-route? (vault-vm/vault-detail-route? route)
        root-classes (into ["h-screen" "bg-base-100" "flex" "flex-col" "overflow-y-auto" "scrollbar-hide"]
                           (when trade-route?
                             ["xl:overflow-y-hidden"]))]
    [:div {:class root-classes
           :data-parity-id "app-root"}
     (header-view/header-view state)
     (spectate-mode-banner state)
     [:div {:class ["flex-1" "min-h-0" "pb-12" "flex" "flex-col"]
            :data-parity-id "app-main"}
      (cond
        trade-route? (trade-view/trade-view state)
        portfolio-route? (portfolio-view/portfolio-view state)
        funding-route? (funding-comparison-view/funding-comparison-view state)
        api-wallet-route? (api-wallets-view/api-wallets-view state)
        vault-detail-route? (vault-detail-view/vault-detail-view state)
        vault-route? (vaults-view/vaults-view state)
        :else (trade-view/trade-view state))]
     (funding-modal/funding-modal-view state)
     (spectate-mode-modal/spectate-mode-modal-view state)
     (notifications-view/notifications-view state)
     (footer-view/footer-view state)]))
