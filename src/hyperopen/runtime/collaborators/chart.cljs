(ns hyperopen.runtime.collaborators.chart
  (:require [hyperopen.chart.actions :as chart-actions]
            [hyperopen.chart.settings :as chart-settings]
            [hyperopen.orderbook.actions :as orderbook-actions]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.trade.layout-actions :as trade-layout-actions]))

(defn action-deps []
  {:toggle-timeframes-dropdown chart-actions/toggle-timeframes-dropdown
   :select-chart-timeframe chart-actions/select-chart-timeframe
   :toggle-chart-type-dropdown chart-actions/toggle-chart-type-dropdown
   :select-chart-type chart-actions/select-chart-type
   :toggle-indicators-dropdown chart-actions/toggle-indicators-dropdown
   :update-indicators-search chart-actions/update-indicators-search
   :toggle-portfolio-summary-scope-dropdown portfolio-actions/toggle-portfolio-summary-scope-dropdown
   :select-portfolio-summary-scope portfolio-actions/select-portfolio-summary-scope
   :toggle-portfolio-summary-time-range-dropdown portfolio-actions/toggle-portfolio-summary-time-range-dropdown
   :toggle-portfolio-performance-metrics-time-range-dropdown
   portfolio-actions/toggle-portfolio-performance-metrics-time-range-dropdown
   :select-portfolio-summary-time-range portfolio-actions/select-portfolio-summary-time-range
   :select-portfolio-chart-tab portfolio-actions/select-portfolio-chart-tab
   :set-portfolio-account-info-tab portfolio-actions/set-portfolio-account-info-tab
   :set-portfolio-returns-benchmark-search portfolio-actions/set-portfolio-returns-benchmark-search
   :set-portfolio-returns-benchmark-suggestions-open
   portfolio-actions/set-portfolio-returns-benchmark-suggestions-open
   :select-portfolio-returns-benchmark portfolio-actions/select-portfolio-returns-benchmark
   :remove-portfolio-returns-benchmark portfolio-actions/remove-portfolio-returns-benchmark
   :handle-portfolio-returns-benchmark-search-keydown
   portfolio-actions/handle-portfolio-returns-benchmark-search-keydown
   :clear-portfolio-returns-benchmark portfolio-actions/clear-portfolio-returns-benchmark
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
