(ns hyperopen.funding.application.modal-vm.models
  (:require [hyperopen.funding.application.modal-vm.presentation :as presentation]))

(defn- modal-model
  [{:keys [open? mode title anchor]}]
  {:open? open?
   :mode mode
   :title title
   :anchor anchor})

(defn- deposit-model
  [{:keys [deposit-step
           deposit-search-input
           deposit-assets
           selected-deposit-asset
           selected-deposit-flow-kind
           selected-deposit-implemented?
           generated-address
           generated-signature-count
           hyperunit-fee-state
           hyperunit-fee-estimate-error
           amount-input
           deposit-quick-amounts
           deposit-min-amount
           deposit-min-input
           deposit-summary-rows
           deposit-lifecycle
           deposit-submit-label
           submit-disabled?
           submitting?
           deposit-unsupported-detail]}]
  {:step deposit-step
   :search {:value deposit-search-input
            :placeholder "Search a supported asset"}
   :assets deposit-assets
   :selected-asset selected-deposit-asset
   :flow {:kind selected-deposit-flow-kind
          :supported? selected-deposit-implemented?
          :generated-address generated-address
          :generated-signature-count generated-signature-count
          :unsupported-detail deposit-unsupported-detail
          :fee-estimate {:state hyperunit-fee-state
                         :message hyperunit-fee-estimate-error}}
   :amount {:value amount-input
            :quick-amounts deposit-quick-amounts
            :minimum-value deposit-min-amount
            :minimum-input deposit-min-input}
   :summary {:rows deposit-summary-rows}
   :lifecycle deposit-lifecycle
   :actions {:submit-label deposit-submit-label
             :submit-disabled? submit-disabled?
             :submitting? submitting?}})

(defn- transfer-model
  [{:keys [to-perp?
           amount-input
           transfer-max-display
           transfer-max-input
           submit-disabled?
           submitting?]}]
  {:to-perp? to-perp?
   :amount {:value amount-input
            :max-display transfer-max-display
            :max-input transfer-max-input
            :symbol "USDC"}
   :actions {:submit-label (if submitting? "Submitting..." "Transfer")
             :submit-disabled? submit-disabled?
             :submitting? submitting?}})

(defn- send-model
  [{:keys [send-token
           send-symbol
           send-prefix-label
           destination-input
           amount-input
           send-max-display
           submit-disabled?
           submitting?]}]
  {:asset {:token send-token
           :symbol send-symbol
           :prefix-label send-prefix-label}
   :destination {:value destination-input}
   :amount {:value amount-input
            :max-display send-max-display
            :symbol send-symbol}
   :actions {:submit-label (if submitting? "Submitting..." "Send")
             :submit-disabled? submit-disabled?
             :submitting? submitting?}})

(defn- withdraw-model
  [{:keys [withdraw-assets
           withdraw-step
           withdraw-search-input
           selected-withdraw-asset
           destination-input
           amount-input
           withdraw-max-display
           withdraw-max-input
           selected-withdraw-symbol
           selected-withdraw-flow-kind
           withdraw-generated-address
           hyperunit-fee-state
           hyperunit-fee-estimate-error
           withdrawal-queue-state
           withdraw-queue-length
           withdraw-queue-last-operation-tx-id
           withdraw-queue-last-operation-explorer-url
           hyperunit-withdrawal-queue-error
           withdraw-summary-rows
           withdraw-lifecycle
           submit-disabled?
           submitting?]}]
  {:step withdraw-step
   :search {:value withdraw-search-input
            :placeholder "Search a supported asset"}
   :assets withdraw-assets
   :selected-asset selected-withdraw-asset
   :destination {:value destination-input}
   :amount {:value amount-input
            :max-display withdraw-max-display
            :max-input withdraw-max-input
            :available-label (str withdraw-max-input
                                  " "
                                  selected-withdraw-symbol
                                  " available")
            :symbol selected-withdraw-symbol}
   :flow {:kind selected-withdraw-flow-kind
          :protocol-address withdraw-generated-address
          :fee-estimate {:state hyperunit-fee-state
                         :message hyperunit-fee-estimate-error}
          :withdrawal-queue {:state withdrawal-queue-state
                             :length withdraw-queue-length
                             :last-operation {:tx-id withdraw-queue-last-operation-tx-id
                                              :explorer-url withdraw-queue-last-operation-explorer-url}
                             :message hyperunit-withdrawal-queue-error}}
   :summary {:rows withdraw-summary-rows}
   :lifecycle withdraw-lifecycle
   :actions {:submit-label (if submitting? "Submitting..." "Withdraw")
             :submit-disabled? submit-disabled?
             :submitting? submitting?}})

(defn- legacy-model
  [{:keys [legacy-kind]}]
  {:kind legacy-kind
   :message (str "The " (name legacy-kind) " funding workflow is not available yet.")})

(defn build-view-model
  [{:keys [open?
           mode
           legacy-kind
           anchor
           title
           deposit-step
           deposit-search-input
           withdraw-step
           withdraw-search-input
           deposit-assets
           selected-deposit-asset
           selected-deposit-flow-kind
           selected-deposit-implemented?
           generated-address
           generated-signatures
           withdraw-assets
           withdraw-all-assets
           selected-withdraw-asset
           selected-withdraw-asset-key
           selected-withdraw-flow-kind
           withdraw-generated-address
           amount-input
           to-perp?
           destination-input
           hyperunit-lifecycle
           hyperunit-lifecycle-terminal?
           hyperunit-lifecycle-outcome
           hyperunit-lifecycle-outcome-label
           hyperunit-lifecycle-recovery-hint
           hyperunit-lifecycle-destination-explorer-url
           hyperunit-withdrawal-queue
           max-display
           max-input
           max-symbol
           submitting?
           submit-disabled?
           preview-ok?
           status-message
           deposit-submit-label
           deposit-quick-amounts
           deposit-min-usdc
           deposit-min-amount
           deposit-estimated-time
           deposit-network-fee
           send-token
           send-symbol
           send-prefix-label
           send-max-display
           withdraw-estimated-time
           withdraw-network-fee
           withdraw-queue-length
           withdraw-queue-last-operation-tx-id
           withdraw-queue-last-operation-explorer-url
           hyperunit-fee-estimate-loading?
           hyperunit-fee-estimate-error
           hyperunit-withdrawal-queue-loading?
           hyperunit-withdrawal-queue-error
           submit-label
           withdraw-min-usdc
           withdraw-min-amount
           selected-withdraw-symbol
           content-kind] :as ctx}]
  {:open? open?
   :mode mode
   :legacy-kind legacy-kind
   :anchor anchor
   :title title
   :deposit-step deposit-step
   :deposit-search-input deposit-search-input
   :withdraw-step withdraw-step
   :withdraw-search-input withdraw-search-input
   :deposit-assets deposit-assets
   :deposit-selected-asset selected-deposit-asset
   :deposit-flow-kind selected-deposit-flow-kind
   :deposit-flow-supported? selected-deposit-implemented?
   :deposit-generated-address generated-address
   :deposit-generated-signatures generated-signatures
   :withdraw-assets withdraw-assets
   :withdraw-all-assets withdraw-all-assets
   :withdraw-selected-asset selected-withdraw-asset
   :withdraw-selected-asset-key selected-withdraw-asset-key
   :withdraw-flow-kind selected-withdraw-flow-kind
   :withdraw-generated-address withdraw-generated-address
   :amount-input amount-input
   :to-perp? to-perp?
   :destination-input destination-input
   :hyperunit-lifecycle hyperunit-lifecycle
   :hyperunit-lifecycle-terminal? hyperunit-lifecycle-terminal?
   :hyperunit-lifecycle-outcome hyperunit-lifecycle-outcome
   :hyperunit-lifecycle-outcome-label hyperunit-lifecycle-outcome-label
   :hyperunit-lifecycle-recovery-hint hyperunit-lifecycle-recovery-hint
   :hyperunit-lifecycle-destination-explorer-url
   hyperunit-lifecycle-destination-explorer-url
   :hyperunit-withdrawal-queue hyperunit-withdrawal-queue
   :max-display max-display
   :max-input max-input
   :max-symbol max-symbol
   :submitting? submitting?
   :submit-disabled? submit-disabled?
   :preview-ok? preview-ok?
   :status-message status-message
   :deposit-submit-label deposit-submit-label
   :deposit-quick-amounts deposit-quick-amounts
   :deposit-min-usdc deposit-min-usdc
   :deposit-min-amount deposit-min-amount
   :deposit-estimated-time deposit-estimated-time
   :deposit-network-fee deposit-network-fee
   :withdraw-estimated-time withdraw-estimated-time
   :withdraw-network-fee withdraw-network-fee
   :withdraw-queue-length withdraw-queue-length
   :withdraw-queue-last-operation-tx-id withdraw-queue-last-operation-tx-id
   :withdraw-queue-last-operation-explorer-url withdraw-queue-last-operation-explorer-url
   :hyperunit-fee-estimate-loading? hyperunit-fee-estimate-loading?
   :hyperunit-fee-estimate-error hyperunit-fee-estimate-error
   :hyperunit-withdrawal-queue-loading? hyperunit-withdrawal-queue-loading?
   :hyperunit-withdrawal-queue-error hyperunit-withdrawal-queue-error
   :submit-label submit-label
   :min-withdraw-usdc withdraw-min-usdc
   :min-withdraw-amount withdraw-min-amount
   :min-withdraw-symbol selected-withdraw-symbol
   :modal (modal-model ctx)
   :content {:kind content-kind}
   :feedback (presentation/feedback-model ctx)
   :deposit (deposit-model ctx)
   :send (send-model ctx)
   :transfer (transfer-model ctx)
   :withdraw (withdraw-model ctx)
   :legacy (legacy-model ctx)})
