(ns hyperopen.funding.application.modal-vm.async)

(defn fee-state
  [loading? error]
  (cond
    loading? :loading
    (seq error) :error
    :else :ready))

(defn withdrawal-queue-state
  [loading? queue-length error]
  (cond
    loading? :loading
    (number? queue-length) :ready
    (seq error) :error
    :else :idle))

(defn- withdraw-queue-length
  [selected-withdraw-flow-kind withdraw-chain-queue]
  (when (and (= selected-withdraw-flow-kind :hyperunit-address)
             (map? withdraw-chain-queue))
    (:withdrawal-queue-length withdraw-chain-queue)))

(defn- withdraw-queue-last-operation-tx-id
  [non-blank-text selected-withdraw-flow-kind withdraw-chain-queue]
  (when (and (= selected-withdraw-flow-kind :hyperunit-address)
             (map? withdraw-chain-queue))
    (non-blank-text
     (:last-withdraw-queue-operation-tx-id withdraw-chain-queue))))

(defn- withdraw-queue-last-operation-explorer-url
  [hyperunit-explorer-tx-url selected-withdraw-flow-kind withdraw-chain tx-id]
  (when (= selected-withdraw-flow-kind :hyperunit-address)
    (hyperunit-explorer-tx-url :withdraw withdraw-chain tx-id)))

(defn with-async-context
  [{:keys [modal
           selected-deposit-asset
           selected-withdraw-asset
           selected-withdraw-flow-kind] :as ctx}
   {:keys [normalize-hyperunit-fee-estimate
           normalize-hyperunit-withdrawal-queue
           hyperunit-source-chain
           hyperunit-fee-entry
           hyperunit-withdrawal-queue-entry
           hyperunit-explorer-tx-url
           non-blank-text]}]
  (let [hyperunit-fee-estimate (normalize-hyperunit-fee-estimate
                                (:hyperunit-fee-estimate modal))
        hyperunit-withdrawal-queue (normalize-hyperunit-withdrawal-queue
                                    (:hyperunit-withdrawal-queue modal))
        deposit-chain (hyperunit-source-chain selected-deposit-asset)
        withdraw-chain (hyperunit-source-chain selected-withdraw-asset)
        deposit-chain-fee (hyperunit-fee-entry hyperunit-fee-estimate deposit-chain)
        withdraw-chain-fee (hyperunit-fee-entry hyperunit-fee-estimate withdraw-chain)
        withdraw-chain-queue (hyperunit-withdrawal-queue-entry
                              hyperunit-withdrawal-queue
                              withdraw-chain)
        hyperunit-fee-estimate-loading? (= :loading (:status hyperunit-fee-estimate))
        hyperunit-fee-estimate-error (non-blank-text (:error hyperunit-fee-estimate))
        hyperunit-withdrawal-queue-loading? (= :loading
                                              (:status hyperunit-withdrawal-queue))
        hyperunit-withdrawal-queue-error (non-blank-text
                                          (:error hyperunit-withdrawal-queue))
        withdraw-queue-length (withdraw-queue-length selected-withdraw-flow-kind
                                                     withdraw-chain-queue)
        withdraw-queue-last-operation-tx-id (withdraw-queue-last-operation-tx-id
                                             non-blank-text
                                             selected-withdraw-flow-kind
                                             withdraw-chain-queue)
        withdraw-queue-last-operation-explorer-url (withdraw-queue-last-operation-explorer-url
                                                    hyperunit-explorer-tx-url
                                                    selected-withdraw-flow-kind
                                                    withdraw-chain
                                                    withdraw-queue-last-operation-tx-id)]
    (assoc ctx
           :hyperunit-fee-estimate hyperunit-fee-estimate
           :hyperunit-fee-estimate-loading? hyperunit-fee-estimate-loading?
           :hyperunit-fee-estimate-error hyperunit-fee-estimate-error
           :hyperunit-fee-state (fee-state hyperunit-fee-estimate-loading?
                                           hyperunit-fee-estimate-error)
           :hyperunit-withdrawal-queue hyperunit-withdrawal-queue
           :hyperunit-withdrawal-queue-loading? hyperunit-withdrawal-queue-loading?
           :hyperunit-withdrawal-queue-error hyperunit-withdrawal-queue-error
           :deposit-chain deposit-chain
           :withdraw-chain withdraw-chain
           :deposit-chain-fee deposit-chain-fee
           :withdraw-chain-fee withdraw-chain-fee
           :withdraw-chain-queue withdraw-chain-queue
           :withdraw-queue-length withdraw-queue-length
           :withdraw-queue-last-operation-tx-id withdraw-queue-last-operation-tx-id
           :withdraw-queue-last-operation-explorer-url
           withdraw-queue-last-operation-explorer-url
           :withdrawal-queue-state (withdrawal-queue-state
                                    hyperunit-withdrawal-queue-loading?
                                    withdraw-queue-length
                                    hyperunit-withdrawal-queue-error))))
