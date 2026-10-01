(ns hyperopen.funding.application.modal-state-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.funding.application.modal-state :as modal-state]
            [hyperopen.funding.domain.assets :as assets-domain]))

(deftest default-funding-modal-state-owns-the-modal-ui-shape-test
  (let [state (modal-state/default-funding-modal-state)]
    (is (= false (:open? state)))
    (is (= :asset-select (:deposit-step state)))
    (is (= :asset-select (:withdraw-step state)))
    (is (= assets-domain/withdraw-default-asset-key
           (:withdraw-selected-asset-key state)))
    (is (= "" (:deposit-search-input state)))
    (is (= "" (:withdraw-search-input state)))
    (is (= false (:submitting? state)))
    (is (nil? (:error state)))))

;; Other tests merge over these defaults, so this literal is the one place every
;; default value is pinned; a new modal key must be added here on purpose.
(deftest default-funding-modal-state-pins-every-default-value-test
  (is (= {:open? false
          :mode nil
          :legacy-kind nil
          :anchor nil
          :opener-data-role nil
          :focus-return-data-role nil
          :focus-return-token 0
          :send-token nil
          :send-symbol nil
          :send-prefix-label nil
          :send-max-amount nil
          :send-max-display nil
          :send-max-input ""
          :deposit-step :asset-select
          :deposit-search-input ""
          :withdraw-step :asset-select
          :withdraw-search-input ""
          :deposit-selected-asset-key nil
          :deposit-generated-address nil
          :deposit-generated-signatures nil
          :deposit-generated-asset-key nil
          :amount-input ""
          :to-perp? true
          :transfer-dex ""
          :transfer-destination-address ""
          :transfer-from-subaccount ""
          :transfer-from nil
          :transfer-to nil
          :transfer-asset nil
          :transfer-evm nil
          :transfer-gas-topup nil
          :destination-input ""
          :withdraw-selected-asset-key :usdc
          :withdraw-generated-address nil
          :hyperunit-lifecycle {:direction nil
                                :asset-key nil
                                :operation-id nil
                                :state nil
                                :status nil
                                :source-tx-confirmations nil
                                :destination-tx-confirmations nil
                                :position-in-withdraw-queue nil
                                :destination-tx-hash nil
                                :state-next-at nil
                                :last-updated-ms nil
                                :error nil}
          :hyperunit-fee-estimate {:status :idle
                                   :by-chain {}
                                   :requested-at-ms nil
                                   :updated-at-ms nil
                                   :error nil}
          :hyperunit-withdrawal-queue {:status :idle
                                       :by-chain {}
                                       :requested-at-ms nil
                                       :updated-at-ms nil
                                       :error nil}
          :submitting? false
          :error nil}
         (modal-state/default-funding-modal-state))))

(deftest normalize-modal-state-merges-defaults-and-normalizes-application-fields-test
  (let [normalized (modal-state/normalize-modal-state
                    {:stored-modal {:open? true
                                    :anchor {:left "15"
                                             :top "24"}
                                    :withdraw-step "invalid"
                                    :withdraw-selected-asset-key "invalid"
                                    :hyperunit-lifecycle {:direction "withdraw"
                                                          :asset-key "btc"
                                                          :status "done"
                                                          :position-in-withdraw-queue "4"}
                                    :hyperunit-fee-estimate {:status "ready"
                                                             :by-chain {"bitcoin" {:withdrawal-fee "0.00001"}}}
                                    :hyperunit-withdrawal-queue {:status "ready"
                                                                 :by-chain {"bitcoin" {:withdrawal-queue-length "9"}}}}
                     :normalize-anchor-fn (fn [anchor]
                                            {:left (js/parseFloat (:left anchor))
                                             :top (js/parseFloat (:top anchor))})})]
    (is (= true (:open? normalized)))
    (is (= {:left 15
            :top 24}
           (:anchor normalized)))
    (is (= :asset-select (:withdraw-step normalized)))
    (is (= assets-domain/withdraw-default-asset-key
           (:withdraw-selected-asset-key normalized)))
    (is (= :withdraw (get-in normalized [:hyperunit-lifecycle :direction])))
    (is (= :btc (get-in normalized [:hyperunit-lifecycle :asset-key])))
    (is (= :done (get-in normalized [:hyperunit-lifecycle :status])))
    (is (= 4 (get-in normalized [:hyperunit-lifecycle :position-in-withdraw-queue])))
    (is (= :ready (get-in normalized [:hyperunit-fee-estimate :status])))
    (is (= 0.00001
           (get-in normalized [:hyperunit-fee-estimate :by-chain "bitcoin" :withdrawal-fee])))
    (is (= 9
           (get-in normalized [:hyperunit-withdrawal-queue :by-chain "bitcoin" :withdrawal-queue-length])))))

(deftest normalize-modal-state-coerces-the-transfer-keys-test
  (testing "places become keywords, a digit string an index, and non-map run state nil"
    (is (= {:transfer-from :spot
            :transfer-to :hyperevm
            :transfer-asset 150
            :transfer-evm nil
            :transfer-gas-topup nil}
           (select-keys (modal-state/normalize-modal-state
                         {:stored-modal {:transfer-from "spot"
                                         :transfer-to "HyperEVM"
                                         :transfer-asset "150"
                                         :transfer-evm "x"
                                         :transfer-gas-topup 1}})
                        [:transfer-from :transfer-to :transfer-asset
                         :transfer-evm :transfer-gas-topup]))))
  (testing "unknown places and non-index assets become nil; maps are kept"
    (let [normalized (modal-state/normalize-modal-state
                      {:stored-modal {:transfer-from "evm"
                                      :transfer-to :arbitrum
                                      :transfer-asset "-1"
                                      :transfer-evm {:phase :failed}
                                      :transfer-gas-topup {:status :sent}}})]
      (is (= [nil nil nil {:phase :failed} {:status :sent}]
             ((juxt :transfer-from :transfer-to :transfer-asset :transfer-evm :transfer-gas-topup)
              normalized))))))
