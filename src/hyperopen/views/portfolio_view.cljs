(ns hyperopen.views.portfolio-view
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.chart.d3.hover-state :as chart-hover-state]
            [hyperopen.views.portfolio.account-tabs :as account-tabs]
            [hyperopen.views.portfolio.chart-view :as chart-view]
            [hyperopen.views.portfolio.header :as portfolio-header]
            [hyperopen.views.portfolio.summary-cards :as summary-cards]
            [hyperopen.views.portfolio.vm :as portfolio-vm]))

(defonce ^:private portfolio-view-cache
  (atom nil))

(defn- build-portfolio-view-sections
  [state]
  (let [view-model (portfolio-vm/portfolio-vm state)
        trader-portfolio-route? (account-context/trader-portfolio-route-active? state)]
    {:header (if trader-portfolio-route?
               (portfolio-header/portfolio-inspection-header state)
               (portfolio-header/header-actions state))
     :background-status (portfolio-header/background-status-banner (:background-status view-model))
     :summary-grid
     [:div {:class ["grid"
                    "grid-cols-1"
                    "gap-3"
                    "lg:grid-cols-[240px_minmax(260px,0.85fr)_minmax(0,1.55fr)]"
                    "xl:grid-cols-[320px_minmax(280px,0.8fr)_minmax(520px,1.8fr)]"]}
      (summary-cards/metric-cards view-model)
      (summary-cards/summary-card view-model)
      (chart-view/chart-card view-model)]
     :account-table
     [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "overflow-hidden"]
            :data-role "portfolio-account-table"}
      (account-info-view/account-info-view
       state
       (account-tabs/account-info-options state view-model trader-portfolio-route?))]}))

(defn portfolio-view [state]
  (let [route (get-in state [:router :path])
        hover-active? (chart-hover-state/surface-hover-active? :portfolio)
        cached-entry @portfolio-view-cache
        sections (if (and hover-active?
                          (= route (:route cached-entry))
                          (map? (:sections cached-entry)))
                   (:sections cached-entry)
                   (let [next-sections (build-portfolio-view-sections state)]
                     (reset! portfolio-view-cache {:route route
                                                  :sections next-sections})
                     next-sections))]
    [:div {:class ["w-full"
                   "app-shell-gutter"
                   "py-4"
                   "space-y-4"
                   "md:py-5"]
           :style {:background-image "radial-gradient(circle at 15% 0%, rgba(0, 212, 170, 0.10), transparent 35%), radial-gradient(circle at 85% 100%, rgba(0, 212, 170, 0.08), transparent 40%)"
                   :padding-bottom "3.5rem"}
           :data-parity-id "portfolio-root"}
     (:header sections)
     (:background-status sections)
     (:summary-grid sections)
     (:account-table sections)]))

(defn ^:export route-view
  [state]
  (portfolio-view state))

(goog/exportSymbol "hyperopen.views.portfolio_view.route_view" route-view)
