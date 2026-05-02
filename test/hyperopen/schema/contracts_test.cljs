(ns hyperopen.schema.contracts-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.application.modal-vm :as funding-modal-vm]
            [hyperopen.funding.application.modal-vm.test-support :as funding-support]
            [hyperopen.schema.funding-modal-contracts :as funding-modal-contracts]
            [hyperopen.schema.order-form-contracts :as order-form-contracts]))

(deftest order-form-vm-schema-contracts-test
  (let [valid-vm {:form {:type :limit}
                  :side :buy
                  :type :limit
                  :entry-mode :limit
                  :pro-dropdown-open? false
                  :tpsl-panel-open? false
                  :pro-dropdown-options [:scale]
                  :pro-tab-label "Pro"
                  :spot? false
                  :hip3? false
                  :outcome? false
                  :outcome-sides []
                  :outcome-side-index 0
                  :read-only? false
                  :display {:available-to-trade "0.00 USDC"
                            :current-position "0.0000 BTC"
                            :liquidation-price "N/A"
                            :order-value "N/A"
                            :margin-required "N/A"
                            :slippage "Est 0.0000% / Max 8.00%"
                            :fees {:effective "0.0450% / 0.0150%"
                                   :baseline nil}}
                  :ui-leverage 20
                  :next-leverage 25
                  :size-percent 0
                  :display-size-percent "0"
                  :notch-overlap-threshold 4
                  :size-input-mode :quote
                  :size-display ""
                  :price {:raw ""
                          :display ""
                          :focused? false
                          :fallback nil
                          :context {:label "Ref"
                                    :mid-available? false}}
                  :base-symbol "BTC"
                  :quote-symbol "USDC"
                  :scale-preview-lines {:start "N/A"
                                        :end "N/A"}
                  :error nil
                  :submitting? false
                  :controls {:limit-like? true
                             :show-limit-like-controls? true
                             :show-tpsl-toggle? true
                             :show-tpsl-panel? false
                             :show-post-only? false
                             :show-scale-preview? false
                             :show-liquidation-row? true
                             :show-slippage-row? false}
                  :submit {:form {}
                           :errors []
                           :required-fields []
                           :reason nil
                           :error-message nil
                           :tooltip nil
                           :market-price-missing? false
                           :disabled? false}}
        invalid-vm (assoc valid-vm :unknown true)]
    (is (true? (order-form-contracts/order-form-vm-valid? valid-vm)))
    (is (false? (order-form-contracts/order-form-vm-valid? invalid-vm)))
    (is (= valid-vm
           (order-form-contracts/assert-order-form-vm! valid-vm {:phase :test})))
    (is (thrown-with-msg?
         js/Error
         #"order-form VM schema validation failed"
         (order-form-contracts/assert-order-form-vm! invalid-vm {:phase :test})))))

(deftest order-form-transition-schema-contracts-test
  (let [valid-transition {:order-form {}
                          :order-form-runtime {:submitting? false
                                               :error nil}}
        invalid-transition {:order-form {}
                            :unknown true}]
    (is (true? (order-form-contracts/order-form-transition-valid? valid-transition)))
    (is (false? (order-form-contracts/order-form-transition-valid? invalid-transition)))
    (is (= valid-transition
           (order-form-contracts/assert-order-form-transition! valid-transition {:phase :test})))
    (is (thrown-with-msg?
         js/Error
         #"order-form transition schema validation failed"
         (order-form-contracts/assert-order-form-transition! invalid-transition {:phase :test})))))

(deftest funding-modal-vm-schema-contracts-test
  (let [btc-asset (funding-support/withdraw-asset :key :btc
                                                  :symbol "BTC"
                                                  :name "Bitcoin"
                                                  :network "Bitcoin"
                                                  :flow-kind :hyperunit-address
                                                  :hyperunit-source-chain "bitcoin"
                                                  :min 0.0003
                                                  :max 1.25
                                                  :available-amount 1.25
                                                  :available-display "1.25"
                                                  :available-detail-display "1.25")
        valid-vm (funding-modal-vm/funding-modal-view-model
                  (funding-support/base-deps)
                  (funding-support/base-state {:modal {:mode :withdraw
                                                      :withdraw-step :amount-entry
                                                      :withdraw-selected-asset-key :btc
                                                      :amount-input "0.25"
                                                      :destination-input "bc1qexample"
                                                      :hyperunit-lifecycle {:direction :withdraw
                                                                            :asset-key :btc
                                                                            :state :failed
                                                                            :status :terminal
                                                                            :position-in-withdraw-queue 4
                                                                            :destination-tx-hash "tx-123"
                                                                            :recovery-hint "Retry from the activity panel."}
                                                      :hyperunit-withdrawal-queue {:status :ready
                                                                                   :by-chain {"bitcoin" {:withdrawal-queue-length 9
                                                                                                         :last-withdraw-queue-operation-tx-id
                                                                                                         "queue-123"}}}}
                                               :withdraw-assets [btc-asset]
                                               :withdraw-asset btc-asset}))
        invalid-vm (assoc valid-vm :unknown true)
        invalid-asset-vm (assoc-in valid-vm [:deposit :assets 0 :unexpected] true)
        invalid-raw-lifecycle-vm (assoc-in valid-vm [:hyperunit-lifecycle :unexpected] true)
        invalid-queue-vm (assoc-in valid-vm [:hyperunit-withdrawal-queue :by-chain "bitcoin" :unexpected] true)
        invalid-panel-lifecycle-vm (assoc-in valid-vm [:withdraw :lifecycle :unexpected] true)]
    (is (true? (funding-modal-contracts/funding-modal-vm-valid? valid-vm)))
    (is (false? (funding-modal-contracts/funding-modal-vm-valid? invalid-vm)))
    (is (false? (funding-modal-contracts/funding-modal-vm-valid? invalid-asset-vm)))
    (is (false? (funding-modal-contracts/funding-modal-vm-valid? invalid-raw-lifecycle-vm)))
    (is (false? (funding-modal-contracts/funding-modal-vm-valid? invalid-queue-vm)))
    (is (false? (funding-modal-contracts/funding-modal-vm-valid? invalid-panel-lifecycle-vm)))
    (is (= valid-vm
           (funding-modal-contracts/assert-funding-modal-vm! valid-vm {:phase :test})))
    (is (thrown-with-msg?
         js/Error
         #"funding modal VM schema validation failed"
         (funding-modal-contracts/assert-funding-modal-vm! invalid-vm {:phase :test})))
    (is (thrown-with-msg?
         js/Error
         #"funding modal VM schema validation failed"
         (funding-modal-contracts/assert-funding-modal-vm! invalid-panel-lifecycle-vm {:phase :test})))))
