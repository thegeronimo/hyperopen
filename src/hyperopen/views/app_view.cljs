(ns hyperopen.views.app-view
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.route-modules :as route-modules]
            [hyperopen.router :as router]
            [hyperopen.staking.actions :as staking-actions]
            [hyperopen.trade.layout-actions :as trade-layout-actions]
            [hyperopen.views.agent-trading-recovery-modal :as agent-trading-recovery-modal]
            [hyperopen.views.funding-modal :as funding-modal]
            [hyperopen.views.footer-view :as footer-view]
            [hyperopen.views.spectate-mode-modal :as spectate-mode-modal]
            [hyperopen.views.header-view :as header-view]
            [hyperopen.views.notifications-view :as notifications-view]
            [hyperopen.views.order-submit-confirmation-modal :as order-submit-confirmation-modal]
            [hyperopen.views.trade-view :as trade-view]
            [hyperopen.wallet.core :as wallet]))

(defn- spectate-mode-banner
  [state]
  (let [spectate-active? (account-context/spectate-mode-active? state)
        trader-portfolio-route? (account-context/trader-portfolio-route-active? state)
        spectate-address (account-context/spectate-address state)]
    (when (and spectate-active?
               (not trader-portfolio-route?)
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

(defn- deferred-route-loading-shell
  [state route]
  (let [error-message (route-modules/route-error state route)]
    [:div {:class ["flex-1"
                   "flex"
                   "items-center"
                   "justify-center"
                   "bg-base-100"
                   "px-6"
                   "py-10"]
           :data-parity-id "app-route-module-shell"}
     [:div {:class ["flex"
                    "max-w-md"
                    "flex-col"
                    "items-center"
                    "gap-3"
                    "text-center"]}
      [:div {:class ["text-sm"
                     "font-semibold"
                     "uppercase"
                     "tracking-[0.12em]"
                     "text-trading-text-secondary"]}
       (if error-message
         "Route Load Failed"
         "Loading Route")]
      [:p {:class ["text-sm" "text-trading-text-secondary"]}
       (or error-message
           "Loading this screen on demand to keep the trade landing route smaller.")]
      (when error-message
        [:button {:type "button"
                  :class ["rounded-lg"
                          "border"
                          "border-base-300"
                          "px-3"
                          "py-2"
                          "text-sm"
                          "font-medium"
                          "text-trading-text"
                          "transition-colors"
                          "hover:border-primary"
                          "hover:text-primary"]
                  :on {:click [[:actions/navigate route {:replace? true}]]}}
         "Retry"])]]))

(defn app-view [state]
  (let [route (get-in state [:router :path] "/trade")
        trade-route? (router/trade-route? route)
        deferred-route? (some? (route-modules/route-module-id route))
        route-error (route-modules/route-error state route)
        mobile-surface (trade-layout-actions/normalize-trade-mobile-surface
                        (get-in state [:trade-ui :mobile-surface]))
        mobile-account-surface? (and trade-route? (= mobile-surface :account))
        app-main-classes (into ["flex-1"
                                "min-h-0"
                                "lg:pb-12"
                                "flex"
                                "flex-col"]
                               (if mobile-account-surface?
                                 ["pb-[calc(3rem+env(safe-area-inset-bottom))]"]
                                 ["pb-[5rem]"]))
        root-classes (into ["h-screen" "bg-base-100" "flex" "flex-col" "overflow-y-auto" "scrollbar-hide"]
                           (when trade-route?
                             ["xl:overflow-y-hidden"]))]
    [:div {:class root-classes
           :data-parity-id "app-root"}
     (header-view/header-view state)
     (spectate-mode-banner state)
     [:div {:class app-main-classes
            :data-parity-id "app-main"}
      (cond
        trade-route? (trade-view/trade-view state)
        (and deferred-route?
             route-error)
        (deferred-route-loading-shell state route)
        (and deferred-route?
             (route-modules/route-ready? state route))
        (or (route-modules/render-route-view state route)
            (deferred-route-loading-shell state route))
        deferred-route? (deferred-route-loading-shell state route)
        :else (trade-view/trade-view state))]
     (funding-modal/funding-modal-view state)
     (spectate-mode-modal/spectate-mode-modal-view state)
     (agent-trading-recovery-modal/agent-trading-recovery-modal-view state)
     (order-submit-confirmation-modal/order-submit-confirmation-modal-view state)
     (notifications-view/notifications-view state)
     (footer-view/footer-view state)]))
