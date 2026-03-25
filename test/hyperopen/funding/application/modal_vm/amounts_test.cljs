(ns hyperopen.funding.application.modal-vm.amounts-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.application.modal-vm.test-support :as support]))

(deftest amount-context-derives-deposit-minimum-and-chain-estimates-test
  (let [deposit-asset (support/deposit-asset :key :btc
                                             :symbol "BTC"
                                             :flow-kind :hyperunit-address
                                             :source-chain "bitcoin"
                                             :minimum 0.0001)
        state (support/base-state {:modal {:hyperunit-fee-estimate {:status :ready
                                                                    :by-chain {"bitcoin" {:deposit-eta "~30 mins"
                                                                                          :deposit-fee "0.00002"}}}}
                                   :deposit-assets [deposit-asset]
                                   :deposit-asset deposit-asset})
        ctx (support/build-context (support/base-deps) state)]
    (is (= 0.0001 (:deposit-min-amount ctx)))
    (is (= "0.0001" (:deposit-min-input ctx)))
    (is (= "~30 mins" (:deposit-estimated-time ctx)))
    (is (= "0.00002@bitcoin" (:deposit-network-fee ctx)))))

(deftest amount-context-derives-withdraw-max-and-available-symbol-test
  (let [btc-asset (support/withdraw-asset :key :btc
                                          :symbol "BTC"
                                          :flow-kind :hyperunit-address
                                          :source-chain "bitcoin"
                                          :min 0.0003
                                          :max 1.25)
        state (support/base-state {:modal {:mode :withdraw
                                           :hyperunit-fee-estimate {:status :ready
                                                                    :by-chain {"bitcoin" {:withdrawal-eta "~20 mins"
                                                                                          :withdrawal-fee "0.00001"}}}}
                                   :withdraw-assets [btc-asset]
                                   :withdraw-asset btc-asset})
        ctx (support/build-context (support/base-deps) state)]
    (is (= 1.25 (:max-amount ctx)))
    (is (= "1.25" (:max-display ctx)))
    (is (= "1.25" (:withdraw-max-input ctx)))
    (is (= "BTC" (:max-symbol ctx)))
    (is (= "~20 mins" (:withdraw-estimated-time ctx)))
    (is (= "0.00001@bitcoin" (:withdraw-network-fee ctx)))))
