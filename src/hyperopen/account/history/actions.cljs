(ns hyperopen.account.history.actions
  (:require [hyperopen.account.history.funding-actions :as funding-actions]
            [hyperopen.account.history.order-actions :as order-actions]
            [hyperopen.account.history.position-overlay-actions :as position-overlay-actions]
            [hyperopen.account.history.shared :as history-shared]
            [hyperopen.account.history.surface-actions :as surface-actions]))

(def default-funding-history-state
  funding-actions/default-funding-history-state)

(def default-order-history-state
  order-actions/default-order-history-state)

(def default-trade-history-state
  order-actions/default-trade-history-state)

(def normalize-order-history-page-size
  history-shared/normalize-order-history-page-size)

(def normalize-order-history-page
  history-shared/normalize-order-history-page)

(def restore-open-orders-sort-settings!
  surface-actions/restore-open-orders-sort-settings!)

(def restore-order-history-pagination-settings!
  order-actions/restore-order-history-pagination-settings!)

(def restore-funding-history-pagination-settings!
  funding-actions/restore-funding-history-pagination-settings!)

(def restore-trade-history-pagination-settings!
  order-actions/restore-trade-history-pagination-settings!)

(def funding-history-filters
  funding-actions/funding-history-filters)

(def funding-history-request-id
  funding-actions/funding-history-request-id)

(def order-history-request-id
  order-actions/order-history-request-id)

(defn select-account-info-tab [state tab]
  (cond
    (= tab :funding-history)
    (funding-actions/select-funding-history-tab state)

    (= tab :order-history)
    (order-actions/select-order-history-tab state)

    :else
    [[:effects/save [:account-info :selected-tab] tab]]))

(def set-funding-history-filters
  funding-actions/set-funding-history-filters)

(def toggle-funding-history-filter-open
  funding-actions/toggle-funding-history-filter-open)

(def toggle-funding-history-filter-coin
  funding-actions/toggle-funding-history-filter-coin)

(def add-funding-history-filter-coin
  funding-actions/add-funding-history-filter-coin)

(def handle-funding-history-coin-search-keydown
  funding-actions/handle-funding-history-coin-search-keydown)

(def reset-funding-history-filter-draft
  funding-actions/reset-funding-history-filter-draft)

(def apply-funding-history-filters
  funding-actions/apply-funding-history-filters)

(def view-all-funding-history
  funding-actions/view-all-funding-history)

(def export-funding-history-csv
  funding-actions/export-funding-history-csv)

(def sort-positions
  surface-actions/sort-positions)

(def toggle-positions-direction-filter-open
  surface-actions/toggle-positions-direction-filter-open)

(def set-positions-direction-filter
  surface-actions/set-positions-direction-filter)

(def sort-balances
  surface-actions/sort-balances)

(def sort-open-orders
  surface-actions/sort-open-orders)

(def toggle-open-orders-direction-filter-open
  surface-actions/toggle-open-orders-direction-filter-open)

(def set-open-orders-direction-filter
  surface-actions/set-open-orders-direction-filter)

(def sort-funding-history
  funding-actions/sort-funding-history)

(def set-funding-history-page-size
  funding-actions/set-funding-history-page-size)

(def set-funding-history-page
  funding-actions/set-funding-history-page)

(def next-funding-history-page
  funding-actions/next-funding-history-page)

(def prev-funding-history-page
  funding-actions/prev-funding-history-page)

(def set-funding-history-page-input
  funding-actions/set-funding-history-page-input)

(def apply-funding-history-page-input
  funding-actions/apply-funding-history-page-input)

(def handle-funding-history-page-input-keydown
  funding-actions/handle-funding-history-page-input-keydown)

(def set-trade-history-page-size
  order-actions/set-trade-history-page-size)

(def set-trade-history-page
  order-actions/set-trade-history-page)

(def next-trade-history-page
  order-actions/next-trade-history-page)

(def prev-trade-history-page
  order-actions/prev-trade-history-page)

(def set-trade-history-page-input
  order-actions/set-trade-history-page-input)

(def apply-trade-history-page-input
  order-actions/apply-trade-history-page-input)

(def handle-trade-history-page-input-keydown
  order-actions/handle-trade-history-page-input-keydown)

(def sort-trade-history
  order-actions/sort-trade-history)

(def toggle-trade-history-direction-filter-open
  order-actions/toggle-trade-history-direction-filter-open)

(def set-trade-history-direction-filter
  order-actions/set-trade-history-direction-filter)

(def sort-order-history
  order-actions/sort-order-history)

(def toggle-order-history-filter-open
  order-actions/toggle-order-history-filter-open)

(def set-order-history-status-filter
  order-actions/set-order-history-status-filter)

(def set-account-info-coin-search
  surface-actions/set-account-info-coin-search)

(def toggle-account-info-mobile-card
  surface-actions/toggle-account-info-mobile-card)

(def set-order-history-page-size
  order-actions/set-order-history-page-size)

(def set-order-history-page
  order-actions/set-order-history-page)

(def next-order-history-page
  order-actions/next-order-history-page)

(def prev-order-history-page
  order-actions/prev-order-history-page)

(def set-order-history-page-input
  order-actions/set-order-history-page-input)

(def apply-order-history-page-input
  order-actions/apply-order-history-page-input)

(def handle-order-history-page-input-keydown
  order-actions/handle-order-history-page-input-keydown)

(def refresh-order-history
  order-actions/refresh-order-history)

(def set-hide-small-balances
  surface-actions/set-hide-small-balances)

(def open-position-tpsl-modal
  position-overlay-actions/open-position-tpsl-modal)

(def close-position-tpsl-modal
  position-overlay-actions/close-position-tpsl-modal)

(def handle-position-tpsl-modal-keydown
  position-overlay-actions/handle-position-tpsl-modal-keydown)

(def set-position-tpsl-modal-field
  position-overlay-actions/set-position-tpsl-modal-field)

(def set-position-tpsl-configure-amount
  position-overlay-actions/set-position-tpsl-configure-amount)

(def set-position-tpsl-limit-price
  position-overlay-actions/set-position-tpsl-limit-price)

(def submit-position-tpsl
  position-overlay-actions/submit-position-tpsl)

(def trigger-close-all-positions
  position-overlay-actions/trigger-close-all-positions)

(def open-position-reduce-popover
  position-overlay-actions/open-position-reduce-popover)

(def close-position-reduce-popover
  position-overlay-actions/close-position-reduce-popover)

(def handle-position-reduce-popover-keydown
  position-overlay-actions/handle-position-reduce-popover-keydown)

(def set-position-reduce-popover-field
  position-overlay-actions/set-position-reduce-popover-field)

(def set-position-reduce-size-percent
  position-overlay-actions/set-position-reduce-size-percent)

(def set-position-reduce-limit-price-to-mid
  position-overlay-actions/set-position-reduce-limit-price-to-mid)

(def submit-position-reduce-close
  position-overlay-actions/submit-position-reduce-close)

(def open-position-margin-modal
  position-overlay-actions/open-position-margin-modal)

(def close-position-margin-modal
  position-overlay-actions/close-position-margin-modal)

(def handle-position-margin-modal-keydown
  position-overlay-actions/handle-position-margin-modal-keydown)

(def set-position-margin-modal-field
  position-overlay-actions/set-position-margin-modal-field)

(def set-position-margin-amount-percent
  position-overlay-actions/set-position-margin-amount-percent)

(def set-position-margin-amount-to-max
  position-overlay-actions/set-position-margin-amount-to-max)

(def submit-position-margin-update
  position-overlay-actions/submit-position-margin-update)
