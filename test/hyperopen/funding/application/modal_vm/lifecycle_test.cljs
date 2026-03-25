(ns hyperopen.funding.application.modal-vm.lifecycle-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.application.modal-vm.async :as async]
            [hyperopen.funding.application.modal-vm.context :as context]
            [hyperopen.funding.application.modal-vm.lifecycle :as lifecycle]
            [hyperopen.funding.application.modal-vm.test-support :as support]))

(deftest lifecycle-context-builds-terminal-withdraw-panel-state-test
  (let [deps (support/base-deps)
        btc-asset (support/withdraw-asset :key :btc
                                          :symbol "BTC"
                                          :flow-kind :hyperunit-address
                                          :source-chain "bitcoin"
                                          :min 0.0003
                                          :max 1.25)
        state (support/base-state {:modal {:mode :withdraw
                                           :hyperunit-lifecycle {:direction :withdraw
                                                                 :asset-key :btc
                                                                 :state :failed
                                                                 :status :terminal
                                                                 :state-next-at 42
                                                                 :position-in-withdraw-queue 4
                                                                 :destination-tx-hash "tx-123"
                                                                 :recovery-hint "Retry from the activity panel."}}
                                   :withdraw-assets [btc-asset]
                                   :withdraw-asset btc-asset})
        ctx (-> (context/base-context deps state)
                (context/with-asset-context deps)
                (async/with-async-context deps)
                (lifecycle/with-lifecycle-context deps))]
    (is (= :failure (:hyperunit-lifecycle-outcome ctx)))
    (is (= "Needs Attention" (:hyperunit-lifecycle-outcome-label ctx)))
    (is (= "Retry from the activity panel." (:hyperunit-lifecycle-recovery-hint ctx)))
    (is (= "https://explorer/withdraw/bitcoin/tx-123"
           (:hyperunit-lifecycle-destination-explorer-url ctx)))
    (is (= "Failed" (get-in ctx [:withdraw-lifecycle :stage-label])))
    (is (= "Terminal" (get-in ctx [:withdraw-lifecycle :status-label])))
    (is (= 4 (get-in ctx [:withdraw-lifecycle :queue-position])))
    (is (= "Scheduled" (get-in ctx [:withdraw-lifecycle :next-check-label])))))
