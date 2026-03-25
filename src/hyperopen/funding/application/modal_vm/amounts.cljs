(ns hyperopen.funding.application.modal-vm.amounts
  (:require [hyperopen.funding.application.modal-vm.context :as context]))

(defn- mode-max-amount
  [mode send-max transfer-max withdraw-max]
  (case mode
    :send send-max
    :transfer transfer-max
    :withdraw withdraw-max
    0))

(defn- max-symbol
  [mode send-symbol selected-withdraw-symbol]
  (case mode
    :send send-symbol
    :withdraw selected-withdraw-symbol
    "USDC"))

(defn- deposit-estimated-time
  [non-blank-text
   {:keys [selected-deposit-flow-kind
           hyperunit-fee-estimate-loading?
           deposit-chain-fee]}]
  (if (= selected-deposit-flow-kind :hyperunit-address)
    (or (when hyperunit-fee-estimate-loading? "Loading...")
        (non-blank-text (:deposit-eta deposit-chain-fee))
        "Depends on source confirmations")
    "~10 seconds"))

(defn- deposit-network-fee
  [estimate-fee-display
   {:keys [selected-deposit-flow-kind
           hyperunit-fee-estimate-loading?
           deposit-chain-fee
           deposit-chain]}]
  (if (= selected-deposit-flow-kind :hyperunit-address)
    (or (when hyperunit-fee-estimate-loading? "Loading...")
        (estimate-fee-display (:deposit-fee deposit-chain-fee)
                              deposit-chain)
        "Paid on source chain")
    "None"))

(defn- withdraw-estimated-time
  [non-blank-text
   {:keys [selected-withdraw-flow-kind
           hyperunit-fee-estimate-loading?
           withdraw-chain-fee]}]
  (if (= selected-withdraw-flow-kind :hyperunit-address)
    (or (when hyperunit-fee-estimate-loading? "Loading...")
        (non-blank-text (:withdrawal-eta withdraw-chain-fee))
        "Depends on destination chain")
    "~10 seconds"))

(defn- withdraw-network-fee
  [estimate-fee-display
   {:keys [selected-withdraw-flow-kind
           hyperunit-fee-estimate-loading?
           withdraw-chain-fee
           withdraw-chain]}]
  (if (= selected-withdraw-flow-kind :hyperunit-address)
    (or (when hyperunit-fee-estimate-loading? "Loading...")
        (estimate-fee-display (:withdrawal-fee withdraw-chain-fee)
                              withdraw-chain)
        "Paid on destination chain")
    "None"))

(defn with-amount-context
  [{:keys [state
           modal
           mode
           send-max-amount
           send-symbol
           selected-deposit-asset
           selected-withdraw-asset
           selected-withdraw-symbol
           deposit-min-usdc] :as ctx}
   {:keys [transfer-max-amount
           withdraw-max-amount
           withdraw-minimum-amount
           format-usdc-display
           format-usdc-input
           non-blank-text
           estimate-fee-display]}]
  (let [transfer-max (transfer-max-amount state modal)
        withdraw-max (withdraw-max-amount state selected-withdraw-asset)
        withdraw-min-amount (withdraw-minimum-amount selected-withdraw-asset)
        deposit-min-amount (context/asset-minimum selected-deposit-asset deposit-min-usdc)
        max-amount (mode-max-amount mode send-max-amount transfer-max withdraw-max)
        deposit-estimated-time (deposit-estimated-time non-blank-text ctx)
        deposit-network-fee (deposit-network-fee estimate-fee-display ctx)
        withdraw-estimated-time (withdraw-estimated-time non-blank-text ctx)
        withdraw-network-fee (withdraw-network-fee estimate-fee-display ctx)]
    (assoc ctx
           :transfer-max transfer-max
           :withdraw-max withdraw-max
           :withdraw-min-amount withdraw-min-amount
           :deposit-min-amount deposit-min-amount
           :deposit-min-input (format-usdc-input deposit-min-amount)
           :max-amount max-amount
           :max-display (if (= mode :send)
                          (:send-max-display ctx)
                          (format-usdc-display max-amount))
           :max-input (if (= mode :send)
                        (:send-max-input ctx)
                        (format-usdc-input max-amount))
           :max-symbol (max-symbol mode send-symbol selected-withdraw-symbol)
           :transfer-max-display (format-usdc-display transfer-max)
           :transfer-max-input (format-usdc-input transfer-max)
           :withdraw-max-display (format-usdc-display withdraw-max)
           :withdraw-max-input (format-usdc-input withdraw-max)
           :deposit-estimated-time deposit-estimated-time
           :deposit-network-fee deposit-network-fee
           :withdraw-estimated-time withdraw-estimated-time
           :withdraw-network-fee withdraw-network-fee)))
