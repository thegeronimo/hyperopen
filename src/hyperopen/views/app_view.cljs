(ns hyperopen.views.app-view
  (:require [clojure.string :as str]
            [hyperopen.funding-comparison.actions :as funding-actions]
            [hyperopen.views.funding-modal :as funding-modal]
            [hyperopen.views.footer-view :as footer-view]
            [hyperopen.views.funding-comparison-view :as funding-comparison-view]
            [hyperopen.views.header-view :as header-view]
            [hyperopen.views.notifications-view :as notifications-view]
            [hyperopen.views.vaults.detail-view :as vault-detail-view]
            [hyperopen.views.vaults.list-view :as vaults-view]
            [hyperopen.views.vaults.vm :as vault-vm]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.trade-view :as trade-view]))

(defn app-view [state]
  (let [route (get-in state [:router :path] "/trade")
        trade-route? (str/starts-with? route "/trade")
        portfolio-route? (str/starts-with? route "/portfolio")
        funding-route? (funding-actions/funding-comparison-route? route)
        vault-route? (vault-vm/vault-route? route)
        vault-detail-route? (vault-vm/vault-detail-route? route)
        root-classes (into ["h-screen" "bg-base-100" "flex" "flex-col" "overflow-y-auto" "scrollbar-hide"]
                           (when trade-route?
                             ["xl:overflow-y-hidden"]))]
    [:div {:class root-classes
           :data-parity-id "app-root"}
     (header-view/header-view state)
     [:div {:class ["flex-1" "min-h-0" "pb-12" "flex" "flex-col"]
            :data-parity-id "app-main"}
      (cond
        trade-route? (trade-view/trade-view state)
        portfolio-route? (portfolio-view/portfolio-view state)
        funding-route? (funding-comparison-view/funding-comparison-view state)
        vault-detail-route? (vault-detail-view/vault-detail-view state)
        vault-route? (vaults-view/vaults-view state)
        :else (trade-view/trade-view state))]
     (funding-modal/funding-modal-view state)
     (notifications-view/notifications-view state)
     (footer-view/footer-view state)]))
