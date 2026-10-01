(ns hyperopen.funding.actions
  (:require [hyperopen.funding.application.modal-actions :as modal-actions]
            [hyperopen.funding.domain.lifecycle :as lifecycle]))

(def hyperunit-lifecycle-terminal? lifecycle/hyperunit-lifecycle-terminal?)
(def default-hyperunit-lifecycle-state lifecycle/default-hyperunit-lifecycle-state)
(def normalize-hyperunit-lifecycle lifecycle/normalize-hyperunit-lifecycle)
(def default-hyperunit-fee-estimate-state lifecycle/default-hyperunit-fee-estimate-state)
(def normalize-hyperunit-fee-estimate lifecycle/normalize-hyperunit-fee-estimate)
(def default-hyperunit-withdrawal-queue-state lifecycle/default-hyperunit-withdrawal-queue-state)
(def normalize-hyperunit-withdrawal-queue lifecycle/normalize-hyperunit-withdrawal-queue)

(def default-funding-modal-state modal-actions/default-funding-modal-state)
(def modal-open? modal-actions/modal-open?)
(def funding-modal-view-model modal-actions/funding-modal-view-model)
(def open-funding-send-modal modal-actions/open-funding-send-modal)
(def open-funding-deposit-modal modal-actions/open-funding-deposit-modal)
(def open-funding-transfer-modal modal-actions/open-funding-transfer-modal)
(def open-funding-withdraw-modal modal-actions/open-funding-withdraw-modal)
(def close-funding-modal modal-actions/close-funding-modal)
(def handle-funding-modal-keydown modal-actions/handle-funding-modal-keydown)
(def set-funding-modal-field modal-actions/set-funding-modal-field)
(def search-funding-deposit-assets modal-actions/search-funding-deposit-assets)
(def search-funding-withdraw-assets modal-actions/search-funding-withdraw-assets)
(def select-funding-deposit-asset modal-actions/select-funding-deposit-asset)
(def return-to-funding-deposit-asset-select modal-actions/return-to-funding-deposit-asset-select)
(def return-to-funding-withdraw-asset-select modal-actions/return-to-funding-withdraw-asset-select)
(def enter-funding-deposit-amount modal-actions/enter-funding-deposit-amount)
(def set-funding-deposit-amount-to-minimum modal-actions/set-funding-deposit-amount-to-minimum)
(def enter-funding-transfer-amount modal-actions/enter-funding-transfer-amount)
(def select-funding-withdraw-asset modal-actions/select-funding-withdraw-asset)
(def enter-funding-withdraw-destination modal-actions/enter-funding-withdraw-destination)
(def enter-funding-withdraw-amount modal-actions/enter-funding-withdraw-amount)
(def set-hyperunit-lifecycle modal-actions/set-hyperunit-lifecycle)
(def clear-hyperunit-lifecycle modal-actions/clear-hyperunit-lifecycle)
(def set-hyperunit-lifecycle-error modal-actions/set-hyperunit-lifecycle-error)
(def set-funding-transfer-direction modal-actions/set-funding-transfer-direction)
(def set-funding-amount-to-max modal-actions/set-funding-amount-to-max)
(def submit-funding-send modal-actions/submit-funding-send)
(def submit-funding-transfer modal-actions/submit-funding-transfer)
(def submit-funding-withdraw modal-actions/submit-funding-withdraw)
(def submit-funding-deposit modal-actions/submit-funding-deposit)
(def set-funding-modal-compat modal-actions/set-funding-modal-compat)
(def set-funding-transfer-location modal-actions/set-funding-transfer-location)
(def swap-funding-transfer-locations modal-actions/swap-funding-transfer-locations)
(def select-funding-transfer-asset modal-actions/select-funding-transfer-asset)
(def set-funding-transfer-amount-percent modal-actions/set-funding-transfer-amount-percent)
(def submit-funding-transfer-gas-topup modal-actions/submit-funding-transfer-gas-topup)
(def reset-funding-transfer-evm modal-actions/reset-funding-transfer-evm)
(def retry-funding-transfer-capability modal-actions/retry-funding-transfer-capability)
(def add-funding-transfer-token-to-wallet modal-actions/add-funding-transfer-token-to-wallet)
(def transfer-capacity-refresh-index modal-actions/transfer-capacity-refresh-index)

(defn submit-funding-repay
  "Repays a Hyperliquid spot/portfolio-margin borrow for `token` (the spot token
   index of the borrowed asset, e.g. USDH). Emits the heavy submit effect with a
   fully-built `borrowLend` repay action; `amount` is nil to repay the maximum
   (the lesser of the outstanding borrow and available balance). The effect
   resolves the owner/subaccount routing from state."
  [_state token]
  [[:effects/api-submit-funding-repay
    {:action {:type "borrowLend"
              :operation "repay"
              :token token
              :amount nil}}]])
