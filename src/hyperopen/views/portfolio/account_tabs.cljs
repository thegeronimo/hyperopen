(ns hyperopen.views.portfolio.account-tabs
  (:require [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.portfolio.header :as portfolio-header]
            [hyperopen.views.portfolio.performance-metrics-view :as performance-metrics-view]
            [hyperopen.views.portfolio.summary-cards :as summary-cards]))

(def ^:private performance-metrics-panel-height
  "min(44rem, calc(100dvh - 24rem))")

(def ^:private portfolio-account-panel-style
  {:height performance-metrics-panel-height
   :max-height performance-metrics-panel-height})

(def ^:private portfolio-account-tab-click-actions-by-tab
  (into
   {:deposits-withdrawals [[:actions/set-portfolio-account-info-tab :deposits-withdrawals]]
    :performance-metrics [[:actions/set-portfolio-account-info-tab :performance-metrics]]}
   (map (fn [tab]
          [tab
           [[:actions/set-portfolio-account-info-tab tab]
            [:actions/select-account-info-tab tab]]])
        account-info-view/available-tabs)))

(def ^:private portfolio-account-tab-order
  [:performance-metrics
   :balances
   :positions
   :open-orders
   :funding-history
   :deposits-withdrawals
   :trade-history
   :order-history
   :twap])

(def ^:private portfolio-account-tab-label-overrides
  {:funding-history "Interest"})

(def ^:private portfolio-card-deposit-action-data-role
  "portfolio-funding-action-deposit")

(def ^:private portfolio-card-withdraw-action-data-role
  "portfolio-funding-action-withdraw")

(def ^:private portfolio-card-transfer-action-data-role
  "portfolio-funding-action-transfer")

(defn- deposits-withdrawals-card [state]
  (let [focus-request {:data-role (get-in state [:funding-ui :modal :focus-return-data-role])
                       :token (get-in state [:funding-ui :modal :focus-return-token] 0)}]
  (summary-cards/section-card
   "portfolio-deposits-withdrawals-card"
   [:div {:class ["space-y-4" "px-4" "py-4"]}
    [:div {:class ["space-y-1"]}
     [:div {:class ["text-sm" "font-medium" "text-trading-text"]}
      "Deposits & Withdrawals"]
     [:div {:class ["text-sm" "text-trading-text-secondary"]}
      "Move funds between wallet, spot, and trading balances without leaving the portfolio route."]]
    [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
     (portfolio-header/action-button {:label "Deposit"
                                      :focus-request focus-request
                                      :data-role portfolio-card-deposit-action-data-role
                                      :action [:actions/open-funding-deposit-modal
                                               :event.currentTarget/bounds
                                               :event.currentTarget/data-role]
                                      :primary? true})
     (portfolio-header/action-button {:label "Withdraw"
                                      :focus-request focus-request
                                      :data-role portfolio-card-withdraw-action-data-role
                                      :action [:actions/open-funding-withdraw-modal
                                               :event.currentTarget/bounds
                                               :event.currentTarget/data-role]})
     (portfolio-header/action-button {:label "Transfer"
                                      :mobile-label "Transfer"
                                      :focus-request focus-request
                                      :data-role portfolio-card-transfer-action-data-role
                                      :action [:actions/open-funding-transfer-modal
                                               :event.currentTarget/bounds
                                               :event.currentTarget/data-role]})]
    [:div {:class ["grid" "gap-3" "text-sm" "text-trading-text-secondary" "sm:grid-cols-2"]}
     [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-100/90" "px-3" "py-3"]}
      "Deposit and withdraw flows stay anchored to the same funding modals used from trade."]
     [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-100/90" "px-3" "py-3"]}
      "Portfolio keeps balances and account tables in context while cash movement actions stay one tap away."]]])))

(defn account-info-options [state view-model trader-portfolio-route?]
  (let [extra-tabs (cond-> [{:id :performance-metrics
                             :label "Performance Metrics"
                             :panel-classes ["min-h-0"]
                             :panel-style portfolio-account-panel-style
                             :render (fn [_]
                                       (performance-metrics-view/performance-metrics-card
                                        (assoc (:performance-metrics view-model)
                                               :time-range-selector (get-in view-model [:selectors :performance-metrics-time-range]))))}]
                     (not trader-portfolio-route?)
                     (into [{:id :deposits-withdrawals
                             :label "Deposits & Withdrawals"
                             :render (fn [_]
                                       (deposits-withdrawals-card state))}]))]
    {:extra-tabs extra-tabs
     :default-panel-classes ["min-h-0"]
     :default-panel-style portfolio-account-panel-style
     :selected-tab-override (get-in state [:portfolio-ui :account-info-tab] portfolio-actions/default-account-info-tab)
     :default-selected-tab portfolio-actions/default-account-info-tab
     :tab-click-actions-by-tab portfolio-account-tab-click-actions-by-tab
     :tab-label-overrides portfolio-account-tab-label-overrides
     :tab-order portfolio-account-tab-order}))
