(ns hyperopen.runtime.collaborators.chart
  (:require [hyperopen.chart.actions :as chart-actions]
            [hyperopen.chart.settings :as chart-settings]
            [hyperopen.orderbook.actions :as orderbook-actions]
            [hyperopen.portfolio.account-activity-actions :as account-activity-actions]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.portfolio.montecarlo.actions :as montecarlo-actions]
            [hyperopen.trade.layout-actions :as trade-layout-actions]))

(defn action-deps []
  {:toggle-timeframes-dropdown chart-actions/toggle-timeframes-dropdown
   :select-chart-timeframe chart-actions/select-chart-timeframe
   :request-chart-candle-backfill chart-actions/request-chart-candle-backfill
   :toggle-chart-type-dropdown chart-actions/toggle-chart-type-dropdown
   :select-chart-type chart-actions/select-chart-type
   :toggle-indicators-dropdown chart-actions/toggle-indicators-dropdown
   :update-indicators-search chart-actions/update-indicators-search
   :toggle-portfolio-summary-scope-dropdown portfolio-actions/toggle-portfolio-summary-scope-dropdown
   :select-portfolio-summary-scope portfolio-actions/select-portfolio-summary-scope
   :toggle-portfolio-summary-time-range-dropdown portfolio-actions/toggle-portfolio-summary-time-range-dropdown
   :toggle-portfolio-performance-metrics-time-range-dropdown
   portfolio-actions/toggle-portfolio-performance-metrics-time-range-dropdown
   :open-portfolio-fee-schedule portfolio-actions/open-portfolio-fee-schedule
   :close-portfolio-fee-schedule portfolio-actions/close-portfolio-fee-schedule
   :toggle-portfolio-fee-schedule-referral-dropdown
   portfolio-actions/toggle-portfolio-fee-schedule-referral-dropdown
   :toggle-portfolio-fee-schedule-staking-dropdown
   portfolio-actions/toggle-portfolio-fee-schedule-staking-dropdown
   :toggle-portfolio-fee-schedule-maker-rebate-dropdown
   portfolio-actions/toggle-portfolio-fee-schedule-maker-rebate-dropdown
   :toggle-portfolio-fee-schedule-market-dropdown
   portfolio-actions/toggle-portfolio-fee-schedule-market-dropdown
   :select-portfolio-fee-schedule-referral-discount
   portfolio-actions/select-portfolio-fee-schedule-referral-discount
   :select-portfolio-fee-schedule-staking-tier
   portfolio-actions/select-portfolio-fee-schedule-staking-tier
   :select-portfolio-fee-schedule-maker-rebate-tier
   portfolio-actions/select-portfolio-fee-schedule-maker-rebate-tier
   :select-portfolio-fee-schedule-market-type
   portfolio-actions/select-portfolio-fee-schedule-market-type
   :handle-portfolio-fee-schedule-keydown
   portfolio-actions/handle-portfolio-fee-schedule-keydown
   :select-portfolio-summary-time-range portfolio-actions/select-portfolio-summary-time-range
   :select-portfolio-chart-tab portfolio-actions/select-portfolio-chart-tab
   :set-portfolio-account-info-tab portfolio-actions/set-portfolio-account-info-tab
   :set-portfolio-account-activity-sub-tab
   account-activity-actions/set-portfolio-account-activity-sub-tab
   :sort-portfolio-account-activity
   account-activity-actions/sort-portfolio-account-activity
   :set-portfolio-account-activity-page-size
   account-activity-actions/set-portfolio-account-activity-page-size
   :next-portfolio-account-activity-page
   account-activity-actions/next-portfolio-account-activity-page
   :prev-portfolio-account-activity-page
   account-activity-actions/prev-portfolio-account-activity-page
   :set-portfolio-account-activity-page-input
   account-activity-actions/set-portfolio-account-activity-page-input
   :apply-portfolio-account-activity-page-input
   account-activity-actions/apply-portfolio-account-activity-page-input
   :handle-portfolio-account-activity-page-input-keydown
   account-activity-actions/handle-portfolio-account-activity-page-input-keydown
   :set-portfolio-monte-carlo-control montecarlo-actions/set-portfolio-monte-carlo-control
   :rerun-portfolio-monte-carlo montecarlo-actions/rerun-portfolio-monte-carlo
   :set-portfolio-returns-benchmark-search portfolio-actions/set-portfolio-returns-benchmark-search
   :set-portfolio-returns-benchmark-suggestions-open
   portfolio-actions/set-portfolio-returns-benchmark-suggestions-open
   :select-portfolio-returns-benchmark portfolio-actions/select-portfolio-returns-benchmark
   :remove-portfolio-returns-benchmark portfolio-actions/remove-portfolio-returns-benchmark
   :handle-portfolio-returns-benchmark-search-keydown
   portfolio-actions/handle-portfolio-returns-benchmark-search-keydown
   :clear-portfolio-returns-benchmark portfolio-actions/clear-portfolio-returns-benchmark
   :open-portfolio-volume-history portfolio-actions/open-portfolio-volume-history
   :close-portfolio-volume-history portfolio-actions/close-portfolio-volume-history
   :handle-portfolio-volume-history-keydown portfolio-actions/handle-portfolio-volume-history-keydown
   :toggle-orderbook-size-unit-dropdown orderbook-actions/toggle-orderbook-size-unit-dropdown
   :select-orderbook-size-unit orderbook-actions/select-orderbook-size-unit
   :toggle-orderbook-price-aggregation-dropdown
   orderbook-actions/toggle-orderbook-price-aggregation-dropdown
   :select-orderbook-price-aggregation orderbook-actions/select-orderbook-price-aggregation
   :select-orderbook-tab orderbook-actions/select-orderbook-tab
   :select-trade-mobile-surface trade-layout-actions/select-trade-mobile-surface
   :toggle-trade-mobile-asset-details trade-layout-actions/toggle-trade-mobile-asset-details
   :add-indicator chart-settings/add-indicator
   :remove-indicator chart-settings/remove-indicator
   :update-indicator-period chart-settings/update-indicator-period
   :show-volume-indicator chart-settings/show-volume-indicator
   :hide-volume-indicator chart-settings/hide-volume-indicator})
