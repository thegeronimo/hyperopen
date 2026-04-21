(ns hyperopen.runtime.collaborators.order
  (:require [hyperopen.funding.actions :as funding-actions]
            [hyperopen.order.actions :as order-actions]))

(defn action-deps []
  {:select-order-entry-mode order-actions/select-order-entry-mode
   :select-pro-order-type order-actions/select-pro-order-type
   :toggle-pro-order-type-dropdown order-actions/toggle-pro-order-type-dropdown
   :close-pro-order-type-dropdown order-actions/close-pro-order-type-dropdown
   :handle-pro-order-type-dropdown-keydown order-actions/handle-pro-order-type-dropdown-keydown
   :toggle-margin-mode-dropdown order-actions/toggle-margin-mode-dropdown
   :close-margin-mode-dropdown order-actions/close-margin-mode-dropdown
   :handle-margin-mode-dropdown-keydown order-actions/handle-margin-mode-dropdown-keydown
   :toggle-leverage-popover order-actions/toggle-leverage-popover
   :close-leverage-popover order-actions/close-leverage-popover
   :handle-leverage-popover-keydown order-actions/handle-leverage-popover-keydown
   :set-order-ui-leverage-draft order-actions/set-order-ui-leverage-draft
   :confirm-order-ui-leverage order-actions/confirm-order-ui-leverage
   :toggle-size-unit-dropdown order-actions/toggle-size-unit-dropdown
   :close-size-unit-dropdown order-actions/close-size-unit-dropdown
   :handle-size-unit-dropdown-keydown order-actions/handle-size-unit-dropdown-keydown
   :toggle-tpsl-unit-dropdown order-actions/toggle-tpsl-unit-dropdown
   :close-tpsl-unit-dropdown order-actions/close-tpsl-unit-dropdown
   :handle-tpsl-unit-dropdown-keydown order-actions/handle-tpsl-unit-dropdown-keydown
   :toggle-tif-dropdown order-actions/toggle-tif-dropdown
   :close-tif-dropdown order-actions/close-tif-dropdown
   :handle-tif-dropdown-keydown order-actions/handle-tif-dropdown-keydown
   :set-order-ui-leverage order-actions/set-order-ui-leverage
   :set-order-margin-mode order-actions/set-order-margin-mode
   :set-order-size-percent order-actions/set-order-size-percent
   :set-order-size-display order-actions/set-order-size-display
   :set-order-size-input-mode order-actions/set-order-size-input-mode
   :focus-order-price-input order-actions/focus-order-price-input
   :blur-order-price-input order-actions/blur-order-price-input
   :set-order-price-to-mid order-actions/set-order-price-to-mid
   :toggle-order-tpsl-panel order-actions/toggle-order-tpsl-panel
   :update-order-form order-actions/update-order-form
   :dismiss-order-feedback-toast order-actions/dismiss-order-feedback-toast
   :expand-order-feedback-toast order-actions/expand-order-feedback-toast
   :collapse-order-feedback-toast order-actions/collapse-order-feedback-toast
   :dismiss-order-submission-confirmation order-actions/dismiss-order-submission-confirmation
   :handle-order-submission-confirmation-keydown
   order-actions/handle-order-submission-confirmation-keydown
   :confirm-order-submission order-actions/confirm-order-submission
   :submit-order order-actions/submit-order
   :submit-unlocked-order-request order-actions/submit-unlocked-order-request
   :submit-unlocked-cancel-request order-actions/submit-unlocked-cancel-request
   :confirm-cancel-visible-open-orders order-actions/confirm-cancel-visible-open-orders
   :close-cancel-visible-open-orders-confirmation
   order-actions/close-cancel-visible-open-orders-confirmation
   :handle-cancel-visible-open-orders-confirmation-keydown
   order-actions/handle-cancel-visible-open-orders-confirmation-keydown
   :submit-cancel-visible-open-orders-confirmation
   order-actions/submit-cancel-visible-open-orders-confirmation
   :cancel-visible-open-orders order-actions/cancel-visible-open-orders
   :cancel-order order-actions/cancel-order
   :cancel-twap order-actions/cancel-twap
   :open-funding-send-modal funding-actions/open-funding-send-modal
   :open-funding-transfer-modal funding-actions/open-funding-transfer-modal
   :open-funding-withdraw-modal funding-actions/open-funding-withdraw-modal
   :open-funding-deposit-modal funding-actions/open-funding-deposit-modal
   :close-funding-modal funding-actions/close-funding-modal
   :handle-funding-modal-keydown funding-actions/handle-funding-modal-keydown
   :set-funding-modal-field funding-actions/set-funding-modal-field
   :search-funding-deposit-assets funding-actions/search-funding-deposit-assets
   :search-funding-withdraw-assets funding-actions/search-funding-withdraw-assets
   :select-funding-deposit-asset funding-actions/select-funding-deposit-asset
   :return-to-funding-deposit-asset-select funding-actions/return-to-funding-deposit-asset-select
   :return-to-funding-withdraw-asset-select
   funding-actions/return-to-funding-withdraw-asset-select
   :enter-funding-deposit-amount funding-actions/enter-funding-deposit-amount
   :set-funding-deposit-amount-to-minimum funding-actions/set-funding-deposit-amount-to-minimum
   :enter-funding-transfer-amount funding-actions/enter-funding-transfer-amount
   :select-funding-withdraw-asset funding-actions/select-funding-withdraw-asset
   :enter-funding-withdraw-destination funding-actions/enter-funding-withdraw-destination
   :enter-funding-withdraw-amount funding-actions/enter-funding-withdraw-amount
   :set-hyperunit-lifecycle funding-actions/set-hyperunit-lifecycle
   :clear-hyperunit-lifecycle funding-actions/clear-hyperunit-lifecycle
   :set-hyperunit-lifecycle-error funding-actions/set-hyperunit-lifecycle-error
   :set-funding-transfer-direction funding-actions/set-funding-transfer-direction
   :set-funding-amount-to-max funding-actions/set-funding-amount-to-max
   :submit-funding-send funding-actions/submit-funding-send
   :submit-funding-transfer funding-actions/submit-funding-transfer
   :submit-funding-withdraw funding-actions/submit-funding-withdraw
   :submit-funding-deposit funding-actions/submit-funding-deposit})
