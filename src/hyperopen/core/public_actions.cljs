(ns hyperopen.core.public-actions
  (:require [hyperopen.account.history.actions :as account-history-actions]
            [hyperopen.asset-selector.actions :as asset-actions]
            [hyperopen.chart.actions :as chart-actions]
            [hyperopen.chart.settings :as chart-settings]
            [hyperopen.order.actions :as order-actions]
            [hyperopen.orderbook.actions :as orderbook-actions]
            [hyperopen.orderbook.settings :as orderbook-settings]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.runtime.action-adapters :as action-adapters]
            [hyperopen.startup.restore :as startup-restore]
            [hyperopen.ui.preferences :as ui-preferences]))

(def toggle-asset-dropdown
  asset-actions/toggle-asset-dropdown)

(def close-asset-dropdown
  asset-actions/close-asset-dropdown)

(def select-asset
  asset-actions/select-asset)

(def update-asset-search
  asset-actions/update-asset-search)

(def update-asset-selector-sort
  asset-actions/update-asset-selector-sort)

(def toggle-asset-selector-strict
  asset-actions/toggle-asset-selector-strict)

(def toggle-asset-favorite
  asset-actions/toggle-asset-favorite)

(def set-asset-selector-favorites-only
  asset-actions/set-asset-selector-favorites-only)

(def set-asset-selector-tab
  asset-actions/set-asset-selector-tab)

(def handle-asset-selector-shortcut
  asset-actions/handle-asset-selector-shortcut)

(def set-asset-selector-scroll-top
  asset-actions/set-asset-selector-scroll-top)

(def increase-asset-selector-render-limit
  asset-actions/increase-asset-selector-render-limit)

(def show-all-asset-selector-markets
  asset-actions/show-all-asset-selector-markets)

(def maybe-increase-asset-selector-render-limit
  asset-actions/maybe-increase-asset-selector-render-limit)

(def refresh-asset-markets
  action-adapters/refresh-asset-markets)

(def apply-asset-icon-status-updates
  asset-actions/apply-asset-icon-status-updates)

(def mark-loaded-asset-icon
  asset-actions/mark-loaded-asset-icon)

(def mark-missing-asset-icon
  asset-actions/mark-missing-asset-icon)

(def set-funding-hypothetical-size
  asset-actions/set-funding-hypothetical-size)

(def set-funding-hypothetical-value
  asset-actions/set-funding-hypothetical-value)

(def restore-open-orders-sort-settings!
  account-history-actions/restore-open-orders-sort-settings!)

(def restore-order-history-pagination-settings!
  account-history-actions/restore-order-history-pagination-settings!)

(def restore-funding-history-pagination-settings!
  account-history-actions/restore-funding-history-pagination-settings!)

(def restore-trade-history-pagination-settings!
  account-history-actions/restore-trade-history-pagination-settings!)

(def restore-chart-options!
  chart-settings/restore-chart-options!)

(def restore-orderbook-ui!
  orderbook-settings/restore-orderbook-ui!)

(def restore-agent-storage-mode!
  startup-restore/restore-agent-storage-mode!)

(def restore-ui-font-preference!
  ui-preferences/restore-ui-font-preference!)

(def toggle-timeframes-dropdown
  chart-actions/toggle-timeframes-dropdown)

(def select-chart-timeframe
  chart-actions/select-chart-timeframe)

(def toggle-chart-type-dropdown
  chart-actions/toggle-chart-type-dropdown)

(def select-chart-type
  chart-actions/select-chart-type)

(def toggle-indicators-dropdown
  chart-actions/toggle-indicators-dropdown)

(def update-indicators-search
  chart-actions/update-indicators-search)

(def toggle-portfolio-summary-scope-dropdown
  portfolio-actions/toggle-portfolio-summary-scope-dropdown)

(def select-portfolio-summary-scope
  portfolio-actions/select-portfolio-summary-scope)

(def toggle-portfolio-summary-time-range-dropdown
  portfolio-actions/toggle-portfolio-summary-time-range-dropdown)

(def toggle-portfolio-performance-metrics-time-range-dropdown
  portfolio-actions/toggle-portfolio-performance-metrics-time-range-dropdown)

(def select-portfolio-summary-time-range
  portfolio-actions/select-portfolio-summary-time-range)

(def select-portfolio-chart-tab
  portfolio-actions/select-portfolio-chart-tab)

(def set-portfolio-account-info-tab
  portfolio-actions/set-portfolio-account-info-tab)

(def set-portfolio-chart-hover
  portfolio-actions/set-portfolio-chart-hover)

(def clear-portfolio-chart-hover
  portfolio-actions/clear-portfolio-chart-hover)

(def set-portfolio-returns-benchmark-search
  portfolio-actions/set-portfolio-returns-benchmark-search)

(def set-portfolio-returns-benchmark-suggestions-open
  portfolio-actions/set-portfolio-returns-benchmark-suggestions-open)

(def select-portfolio-returns-benchmark
  portfolio-actions/select-portfolio-returns-benchmark)

(def remove-portfolio-returns-benchmark
  portfolio-actions/remove-portfolio-returns-benchmark)

(def handle-portfolio-returns-benchmark-search-keydown
  portfolio-actions/handle-portfolio-returns-benchmark-search-keydown)

(def clear-portfolio-returns-benchmark
  portfolio-actions/clear-portfolio-returns-benchmark)

(def toggle-orderbook-size-unit-dropdown
  orderbook-actions/toggle-orderbook-size-unit-dropdown)

(def select-orderbook-size-unit
  orderbook-actions/select-orderbook-size-unit)

(def toggle-orderbook-price-aggregation-dropdown
  orderbook-actions/toggle-orderbook-price-aggregation-dropdown)

(def select-orderbook-price-aggregation
  orderbook-actions/select-orderbook-price-aggregation)

(def select-orderbook-tab
  orderbook-actions/select-orderbook-tab)

(def add-indicator
  chart-settings/add-indicator)

(def remove-indicator
  chart-settings/remove-indicator)

(def update-indicator-period
  chart-settings/update-indicator-period)

(def show-volume-indicator
  chart-settings/show-volume-indicator)

(def hide-volume-indicator
  chart-settings/hide-volume-indicator)

(def select-account-info-tab
  account-history-actions/select-account-info-tab)

(def set-funding-history-filters
  account-history-actions/set-funding-history-filters)

(def toggle-funding-history-filter-open
  account-history-actions/toggle-funding-history-filter-open)

(def toggle-funding-history-filter-coin
  account-history-actions/toggle-funding-history-filter-coin)

(def add-funding-history-filter-coin
  account-history-actions/add-funding-history-filter-coin)

(def handle-funding-history-coin-search-keydown
  account-history-actions/handle-funding-history-coin-search-keydown)

(def reset-funding-history-filter-draft
  account-history-actions/reset-funding-history-filter-draft)

(def apply-funding-history-filters
  account-history-actions/apply-funding-history-filters)

(def view-all-funding-history
  account-history-actions/view-all-funding-history)

(def export-funding-history-csv
  account-history-actions/export-funding-history-csv)

(def sort-positions
  account-history-actions/sort-positions)

(def toggle-positions-direction-filter-open
  account-history-actions/toggle-positions-direction-filter-open)

(def set-positions-direction-filter
  account-history-actions/set-positions-direction-filter)

(def sort-balances
  account-history-actions/sort-balances)

(def sort-open-orders
  account-history-actions/sort-open-orders)

(def toggle-open-orders-direction-filter-open
  account-history-actions/toggle-open-orders-direction-filter-open)

(def set-open-orders-direction-filter
  account-history-actions/set-open-orders-direction-filter)

(def sort-funding-history
  account-history-actions/sort-funding-history)

(def set-funding-history-page-size
  account-history-actions/set-funding-history-page-size)

(def set-funding-history-page
  account-history-actions/set-funding-history-page)

(def next-funding-history-page
  account-history-actions/next-funding-history-page)

(def prev-funding-history-page
  account-history-actions/prev-funding-history-page)

(def set-funding-history-page-input
  account-history-actions/set-funding-history-page-input)

(def apply-funding-history-page-input
  account-history-actions/apply-funding-history-page-input)

(def handle-funding-history-page-input-keydown
  account-history-actions/handle-funding-history-page-input-keydown)

(def set-trade-history-page-size
  account-history-actions/set-trade-history-page-size)

(def set-trade-history-page
  account-history-actions/set-trade-history-page)

(def next-trade-history-page
  account-history-actions/next-trade-history-page)

(def prev-trade-history-page
  account-history-actions/prev-trade-history-page)

(def set-trade-history-page-input
  account-history-actions/set-trade-history-page-input)

(def apply-trade-history-page-input
  account-history-actions/apply-trade-history-page-input)

(def handle-trade-history-page-input-keydown
  account-history-actions/handle-trade-history-page-input-keydown)

(def sort-trade-history
  account-history-actions/sort-trade-history)

(def toggle-trade-history-direction-filter-open
  account-history-actions/toggle-trade-history-direction-filter-open)

(def set-trade-history-direction-filter
  account-history-actions/set-trade-history-direction-filter)

(def sort-order-history
  account-history-actions/sort-order-history)

(def toggle-order-history-filter-open
  account-history-actions/toggle-order-history-filter-open)

(def set-order-history-status-filter
  account-history-actions/set-order-history-status-filter)

(def set-account-info-coin-search
  account-history-actions/set-account-info-coin-search)

(def set-order-history-page-size
  account-history-actions/set-order-history-page-size)

(def set-order-history-page
  account-history-actions/set-order-history-page)

(def next-order-history-page
  account-history-actions/next-order-history-page)

(def prev-order-history-page
  account-history-actions/prev-order-history-page)

(def set-order-history-page-input
  account-history-actions/set-order-history-page-input)

(def apply-order-history-page-input
  account-history-actions/apply-order-history-page-input)

(def handle-order-history-page-input-keydown
  account-history-actions/handle-order-history-page-input-keydown)

(def refresh-order-history
  account-history-actions/refresh-order-history)

(def set-hide-small-balances
  account-history-actions/set-hide-small-balances)

(def select-order-entry-mode
  order-actions/select-order-entry-mode)

(def select-pro-order-type
  order-actions/select-pro-order-type)

(def toggle-pro-order-type-dropdown
  order-actions/toggle-pro-order-type-dropdown)

(def close-pro-order-type-dropdown
  order-actions/close-pro-order-type-dropdown)

(def handle-pro-order-type-dropdown-keydown
  order-actions/handle-pro-order-type-dropdown-keydown)

(def set-order-ui-leverage
  order-actions/set-order-ui-leverage)

(def set-order-size-percent
  order-actions/set-order-size-percent)

(def set-order-size-display
  order-actions/set-order-size-display)

(def set-order-size-input-mode
  order-actions/set-order-size-input-mode)

(def focus-order-price-input
  order-actions/focus-order-price-input)

(def blur-order-price-input
  order-actions/blur-order-price-input)

(def set-order-price-to-mid
  order-actions/set-order-price-to-mid)

(def toggle-order-tpsl-panel
  order-actions/toggle-order-tpsl-panel)

(def update-order-form
  order-actions/update-order-form)

(def dismiss-order-feedback-toast
  order-actions/dismiss-order-feedback-toast)

(def submit-order
  order-actions/submit-order)

(def prune-canceled-open-orders
  order-actions/prune-canceled-open-orders)

(def cancel-order
  order-actions/cancel-order)

(def load-user-data
  action-adapters/load-user-data)

(def set-funding-modal
  action-adapters/set-funding-modal)
