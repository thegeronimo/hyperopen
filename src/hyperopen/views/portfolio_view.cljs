(ns hyperopen.views.portfolio-view
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.portfolio.fee-schedule :as fee-schedule]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.chart.d3.hover-state :as chart-hover-state]
            [hyperopen.views.portfolio.account-tabs :as account-tabs]
            [hyperopen.views.portfolio.chart-view :as chart-view]
            [hyperopen.views.portfolio.fee-schedule :as fee-schedule-view]
            [hyperopen.views.portfolio.header :as portfolio-header]
            [hyperopen.views.portfolio.optimize.view :as optimize-view]
            [hyperopen.views.portfolio.summary-cards :as summary-cards]
            [hyperopen.views.portfolio.volume-history-popover :as volume-history-popover]
            [hyperopen.views.portfolio.vm :as portfolio-vm]))

(defonce ^:private portfolio-view-cache
  (atom nil))

(defn- build-portfolio-view-sections
  [state fee-schedule-model]
  (let [view-model (portfolio-vm/portfolio-vm state)
        view-model* (assoc view-model :fee-schedule {:open? (:open? fee-schedule-model)})
        trader-portfolio-route? (account-context/trader-portfolio-route-active? state)]
    {:header (if trader-portfolio-route?
               (portfolio-header/portfolio-inspection-header state)
               (portfolio-header/header-actions state))
     :background-status (portfolio-header/background-status-banner (:background-status view-model*))
     :summary-grid
     [:div {:class ["grid"
                    "grid-cols-1"
                    "gap-3"
                    "lg:grid-cols-[240px_minmax(260px,0.85fr)_minmax(0,1.55fr)]"
                    "xl:grid-cols-[320px_minmax(280px,0.8fr)_minmax(520px,1.8fr)]"]}
      (summary-cards/metric-cards view-model*)
      (summary-cards/summary-card view-model*)
      (chart-view/chart-card view-model*)]
     :account-table
     [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "overflow-hidden"]
            :data-role "portfolio-account-table"}
      (account-info-view/account-info-view
       state
       (account-tabs/account-info-options state view-model trader-portfolio-route?))]
     :volume-history-popover
     (volume-history-popover/volume-history-popover (:volume-history view-model))}))

(defn portfolio-view [state]
  (let [route (get-in state [:router :path])
        optimizer-route? (portfolio-routes/portfolio-optimize-route? route)
        fee-schedule-model (fee-schedule/fee-schedule-model state)
        fee-schedule-cache-key (:open? fee-schedule-model)
        hover-active? (chart-hover-state/surface-hover-active? :portfolio)
        volume-history-open? (true? (get-in state [:portfolio-ui :volume-history-open?]))
        cached-entry @portfolio-view-cache
        sections (when-not optimizer-route?
                   (if (and hover-active?
                            (not volume-history-open?)
                            (false? (:volume-history-open? cached-entry))
                            (= route (:route cached-entry))
                            (= fee-schedule-cache-key (:fee-schedule-cache-key cached-entry))
                            (map? (:sections cached-entry)))
                     (:sections cached-entry)
                     (let [next-sections (build-portfolio-view-sections state fee-schedule-model)]
                       (reset! portfolio-view-cache {:route route
                                                    :volume-history-open? volume-history-open?
                                                    :fee-schedule-cache-key fee-schedule-cache-key
                                                    :sections next-sections})
                       next-sections)))]
    (if optimizer-route?
      [:div {:class ["portfolio-optimizer-v4" "w-full"]
             :style {:background-color "var(--optimizer-bg)"
                     :min-height "calc(100vh - 3.5rem)"
                     :padding-bottom "3.5rem"}
             :data-role "portfolio-optimizer-route-frame"
             :data-parity-id "portfolio-root"}
       (optimize-view/optimizer-view state)]
      (into
       [:div {:class ["w-full"
                      "app-shell-gutter"
                      "py-4"
                      "space-y-4"
                      "md:py-5"]
              :style {:background-image "radial-gradient(circle at 15% 0%, rgba(0, 212, 170, 0.10), transparent 35%), radial-gradient(circle at 85% 100%, rgba(0, 212, 170, 0.08), transparent 40%)"
                      :padding-bottom "3.5rem"}
              :data-parity-id "portfolio-root"}]
       [(:header sections)
        (:background-status sections)
        (:summary-grid sections)
        (:account-table sections)
        (:volume-history-popover sections)
        (fee-schedule-view/fee-schedule-popover fee-schedule-model)]))))

(defn ^:export route-view
  [state]
  (portfolio-view state))

(goog/exportSymbol "hyperopen.views.portfolio_view.route_view" route-view)
