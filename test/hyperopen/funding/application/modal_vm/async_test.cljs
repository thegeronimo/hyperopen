(ns hyperopen.funding.application.modal-vm.async-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.application.modal-vm.async :as async]
            [hyperopen.funding.application.modal-vm.context :as context]
            [hyperopen.funding.application.modal-vm.test-support :as support]))

(deftest async-context-derives-fee-and-queue-state-for-hyperunit-withdrawals-test
  (let [deps (support/base-deps)
        btc-asset (support/withdraw-asset :key :btc
                                          :symbol "BTC"
                                          :flow-kind :hyperunit-address
                                          :source-chain "bitcoin"
                                          :min 0.0003
                                          :max 1.25)
        state (support/base-state {:modal {:mode :withdraw
                                           :hyperunit-fee-estimate {:status :ready
                                                                    :by-chain {"bitcoin" {:withdrawal-eta "~20 mins"
                                                                                          :withdrawal-fee "0.00001"}}}
                                           :hyperunit-withdrawal-queue {:status :ready
                                                                        :by-chain {"bitcoin" {:withdrawal-queue-length 9
                                                                                              :last-withdraw-queue-operation-tx-id
                                                                                              "queue-123"}}}}
                                   :withdraw-assets [btc-asset]
                                   :withdraw-asset btc-asset})
        ctx (-> (context/base-context deps state)
                (context/with-asset-context deps)
                (async/with-async-context deps))]
    (is (= :ready (:hyperunit-fee-state ctx)))
    (is (= :ready (:withdrawal-queue-state ctx)))
    (is (= "bitcoin" (:withdraw-chain ctx)))
    (is (= 9 (:withdraw-queue-length ctx)))
    (is (= "queue-123" (:withdraw-queue-last-operation-tx-id ctx)))
    (is (= "https://explorer/withdraw/bitcoin/queue-123"
           (:withdraw-queue-last-operation-explorer-url ctx)))))

(deftest async-context-idles-withdrawal-queue-for-non-hyperunit-assets-test
  (let [deps (support/base-deps)
        ctx (-> (context/base-context deps (support/base-state))
                (context/with-asset-context deps)
                (async/with-async-context deps))]
    (is (= :ready (:hyperunit-fee-state ctx)))
    (is (= :idle (:withdrawal-queue-state ctx)))
    (is (nil? (:withdraw-queue-length ctx)))))
