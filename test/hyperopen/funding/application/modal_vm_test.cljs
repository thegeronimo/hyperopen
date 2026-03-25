(ns hyperopen.funding.application.modal-vm-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.application.modal-vm :as modal-vm]
            [hyperopen.funding.application.modal-vm.test-support :as support]))

(deftest funding-modal-view-model-directly-exposes-generated-address-state-test
  (let [state (support/base-state {:modal {:deposit-generated-asset-key :btc
                                           :deposit-generated-address "bc1generated"
                                           :deposit-generated-signatures ["sig-a" "sig-b"]}})
        view-model (modal-vm/funding-modal-view-model (support/base-deps) state)]
    (is (= :deposit/address (get-in view-model [:content :kind])))
    (is (= "bc1generated" (:deposit-generated-address view-model)))
    (is (= "Regenerate address" (:deposit-submit-label view-model)))
    (is (= 2 (get-in view-model [:deposit :flow :generated-signature-count])))
    (is (= "bc1generated"
           (get-in view-model [:deposit :flow :generated-address])))))

(deftest funding-modal-view-model-hides-preview-feedback-before-deposit-amount-entry-test
  (let [state (support/base-state {:modal {:deposit-step :asset-select}
                                   :deposit-asset nil
                                   :preview-result {:ok? false
                                                    :display-message "Enter a valid amount."}})
        view-model (modal-vm/funding-modal-view-model (support/base-deps) state)]
    (is (= :deposit/select (get-in view-model [:content :kind])))
    (is (nil? (:status-message view-model)))
    (is (not (get-in view-model [:feedback :visible?])))
    (is (= true (:submit-disabled? view-model)))))

(deftest funding-modal-view-model-builds-withdraw-lifecycle-and-queue-models-test
  (let [btc-asset (support/withdraw-asset :key :btc
                                          :symbol "BTC"
                                          :flow-kind :hyperunit-address
                                          :source-chain "bitcoin"
                                          :min 0.0003
                                          :max 1.25)
        state (support/base-state {:modal {:mode :withdraw
                                           :withdraw-step :amount-entry
                                           :amount-input "0.25"
                                           :destination-input "bc1qexample"
                                           :hyperunit-lifecycle {:direction :withdraw
                                                                 :asset-key :btc
                                                                 :state :failed
                                                                 :status :terminal
                                                                 :position-in-withdraw-queue 4
                                                                 :destination-tx-hash "tx-123"
                                                                 :recovery-hint "Retry from the activity panel."}
                                           :hyperunit-fee-estimate {:status :ready
                                                                    :by-chain {"bitcoin" {:withdrawal-eta "~20 mins"
                                                                                          :withdrawal-fee "0.00001"}}}
                                           :hyperunit-withdrawal-queue {:status :ready
                                                                        :by-chain {"bitcoin" {:withdrawal-queue-length 9
                                                                                              :last-withdraw-queue-operation-tx-id
                                                                                              "queue-123"}}}}
                                   :withdraw-assets [btc-asset]
                                   :withdraw-asset btc-asset})
        view-model (modal-vm/funding-modal-view-model (support/base-deps) state)]
    (is (= :withdraw/detail (get-in view-model [:content :kind])))
    (is (= :failure (:hyperunit-lifecycle-outcome view-model)))
    (is (= "Needs Attention" (:hyperunit-lifecycle-outcome-label view-model)))
    (is (= "Retry from the activity panel."
           (:hyperunit-lifecycle-recovery-hint view-model)))
    (is (= "https://explorer/withdraw/bitcoin/tx-123"
           (:hyperunit-lifecycle-destination-explorer-url view-model)))
    (is (= :ready
           (get-in view-model [:withdraw :flow :withdrawal-queue :state])))
    (is (= 9 (:withdraw-queue-length view-model)))
    (is (= "queue-123" (:withdraw-queue-last-operation-tx-id view-model)))
    (is (= "https://explorer/withdraw/bitcoin/queue-123"
           (:withdraw-queue-last-operation-explorer-url view-model)))
    (is (= "~20 mins" (:withdraw-estimated-time view-model)))
    (is (= "0.00001@bitcoin" (:withdraw-network-fee view-model)))
    (is (= "1.25 BTC available"
           (get-in view-model [:withdraw :amount :available-label])))))

(deftest funding-modal-view-model-builds-withdraw-select-model-with-asset-amounts-test
  (let [btc-asset (support/withdraw-asset :key :btc
                                          :symbol "BTC"
                                          :flow-kind :hyperunit-address
                                          :source-chain "bitcoin"
                                          :min 0.0003
                                          :max 1.25)
        state (support/base-state {:modal {:mode :withdraw
                                           :withdraw-step :asset-select
                                           :withdraw-search-input "bt"}
                                   :withdraw-assets [(assoc (support/withdraw-asset :key :usdc
                                                                                    :symbol "USDC"
                                                                                    :flow-kind :evm-address
                                                                                    :max 360.793551)
                                                           :available-display "360.793551"
                                                           :available-detail-display "360.793551")
                                            (assoc btc-asset
                                                   :available-display "1.25"
                                                   :available-detail-display "1.25")]
                                   :withdraw-asset btc-asset
                                   :preview-result {:ok? false
                                                    :display-message "Enter a valid destination address."}})
        view-model (modal-vm/funding-modal-view-model (support/base-deps) state)]
    (is (= :withdraw/select (get-in view-model [:content :kind])))
    (is (= "bt" (get-in view-model [:withdraw :search :value])))
    (is (= "1.25" (get-in view-model [:withdraw :assets 1 :available-display])))
    (is (false? (get-in view-model [:feedback :visible?])))))

(deftest funding-modal-view-model-direct-feedback-uses-preview-errors-for-withdrawals-test
  (let [state (support/base-state {:modal {:mode :withdraw
                                           :withdraw-step :amount-entry
                                           :amount-input ""
                                           :destination-input ""}
                                   :preview-result {:ok? false
                                                    :display-message "Enter a valid amount."}})
        view-model (modal-vm/funding-modal-view-model (support/base-deps) state)]
    (is (= "Enter a valid amount." (:status-message view-model)))
    (is (= true (get-in view-model [:feedback :visible?])))
    (is (= true (:submit-disabled? view-model)))
    (is (= "Withdraw" (:submit-label view-model)))))

(deftest funding-modal-view-model-marks-unsupported-deposit-flows-test
  (let [unsupported-asset (support/deposit-asset :flow-kind :route
                                                 :implemented? false)
        state (support/base-state {:deposit-assets [unsupported-asset]
                                   :deposit-asset unsupported-asset
                                   :preview-result {:ok? false
                                                    :display-message "Deposit routing unavailable."}})
        view-model (modal-vm/funding-modal-view-model (support/base-deps) state)]
    (is (= :deposit/unavailable (get-in view-model [:content :kind])))
    (is (= false (:deposit-flow-supported? view-model)))
    (is (= "Route-based bridge/swap flow will be implemented in the next milestone."
           (get-in view-model [:deposit :flow :unsupported-detail])))
    (is (= "Deposit unavailable"
           (get-in view-model [:deposit :actions :submit-label])))))
