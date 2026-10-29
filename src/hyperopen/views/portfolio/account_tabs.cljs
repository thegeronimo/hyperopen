(ns hyperopen.views.portfolio.account-tabs
  (:require [hyperopen.platform :as platform]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.vol-range.application.report-model :as vol-range-report-model]
            [hyperopen.vol-range.contracts :as vol-range-contracts]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.portfolio.account-activity :as account-activity-view]
            [hyperopen.views.portfolio.montecarlo.panel :as montecarlo-panel]
            [hyperopen.views.portfolio.performance-metrics-view :as performance-metrics-view]
            [hyperopen.views.portfolio.vm.montecarlo :as montecarlo-vm]
            [hyperopen.views.portfolio.vol-range-report :as vol-range-report]))

(def ^:private performance-metrics-panel-height
  "min(44rem, calc(100dvh - 24rem))")

(def ^:private portfolio-account-panel-style
  {:height performance-metrics-panel-height
   :max-height performance-metrics-panel-height})

(def ^:private portfolio-account-tab-click-actions-by-tab
  (into
   {:deposits-withdrawals [[:actions/set-portfolio-account-info-tab :deposits-withdrawals]]
    :performance-metrics [[:actions/set-portfolio-account-info-tab :performance-metrics]]
    :monte-carlo [[:actions/set-portfolio-account-info-tab :monte-carlo]]
    :vol-range [[:actions/set-portfolio-account-info-tab :vol-range]]}
   (map (fn [tab]
          [tab
           [[:actions/set-portfolio-account-info-tab tab]
            [:actions/select-account-info-tab tab]]])
        account-info-view/available-tabs)))

(def ^:private portfolio-account-tab-order
  [:performance-metrics
   :monte-carlo
   :vol-range
   :balances
   :positions
   :open-orders
   :funding-history
   :deposits-withdrawals
   :trade-history
   :order-history
   :twap
   :outcomes])

(def ^:private portfolio-account-tab-label-overrides
  {:funding-history "Interest"})

(defn account-info-options [state view-model trader-portfolio-route?]
  (let [extra-tabs (-> (cond-> [{:id :performance-metrics
                                 :label "Performance Metrics"
                                 :panel-classes ["min-h-0"]
                                 :panel-style portfolio-account-panel-style
                                 :render (fn [_]
                                           (performance-metrics-view/performance-metrics-card
                                            (assoc (:performance-metrics view-model)
                                                   :time-range-selector (get-in view-model [:selectors :performance-metrics-time-range])
                                                   :range-strip (:metrics-range-strip view-model))))}]
                         (not trader-portfolio-route?)
                         (into [{:id :deposits-withdrawals
                                 :label "Account Activity"
                                 :panel-classes ["min-h-0"]
                                 :panel-style portfolio-account-panel-style
                                 :render (fn [_]
                                           (account-activity-view/account-activity-table state))}]))
                       (conj {:id :monte-carlo
                              :label "Monte Carlo"
                              :panel-classes ["min-h-0"]
                              :panel-style {}
                              :render (fn [_]
                                        (montecarlo-panel/monte-carlo-card
                                         (montecarlo-vm/montecarlo-model
                                          state
                                          (:monte-carlo view-model))))})
                       ;; Vol Range reporting (spec 11.3): never for another
                       ;; trader's portfolio, and only in builds with reporting.
                       (cond-> (and (vol-range-contracts/reporting-enabled?) (not trader-portfolio-route?))
                         (conj {:id :vol-range
                                :label "Vol Range"
                                :panel-classes ["min-h-0"]
                                :panel-style portfolio-account-panel-style
                                :render (fn [_]
                                          (vol-range-report/vol-range-report
                                           (vol-range-report-model/report-view-model state (platform/now-ms))))})))]
    {:extra-tabs extra-tabs
     :default-panel-classes ["min-h-0"]
     :default-panel-style portfolio-account-panel-style
     :selected-tab-override (get-in state [:portfolio-ui :account-info-tab] portfolio-actions/default-account-info-tab)
     :default-selected-tab portfolio-actions/default-account-info-tab
     :tab-click-actions-by-tab portfolio-account-tab-click-actions-by-tab
     :tab-label-overrides portfolio-account-tab-label-overrides
     :tab-order portfolio-account-tab-order}))
