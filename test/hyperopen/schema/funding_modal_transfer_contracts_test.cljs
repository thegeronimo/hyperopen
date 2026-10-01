(ns hyperopen.schema.funding-modal-transfer-contracts-test
  (:require [cljs.spec.alpha :as s]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.funding.application.modal-vm :as funding-modal-vm]
            [hyperopen.funding.application.modal-vm.test-support :as funding-support]
            ;; The parent contract registers :funding-modal-vm.transfer/actions,
            ;; so it must load for the transfer submap spec to resolve.
            [hyperopen.schema.funding-modal-contracts :as funding-modal-contracts]))

(defn- transfer-vm
  []
  (funding-modal-vm/funding-modal-view-model
   (funding-support/base-deps)
   (funding-support/base-state {:modal {:mode :transfer
                                        :to-perp? false
                                        :amount-input "12"}})))

(deftest transfer-submap-contract-accepts-the-built-view-model-test
  (let [vm (transfer-vm)]
    (is (= :transfer/form (get-in vm [:content :kind])))
    (is (= #{:to-perp? :route :from-options :to-options :swap-action :asset :balances
             :destination :summary :usd-estimate :percent-actions :blocked :evm :amount
             :message :actions}
           (set (keys (:transfer vm)))))
    (is (true? (s/valid? :funding-modal-vm/transfer (:transfer vm))))
    (is (true? (funding-modal-contracts/funding-modal-vm-valid? vm)))))

(deftest transfer-submap-contract-resolves-the-parent-actions-alias-test
  (is (some? (s/get-spec :funding-modal-vm.transfer/actions)))
  (is (true? (s/valid? :funding-modal-vm.transfer/actions
                       {:submit-label "Transfer"
                        :submit-disabled? false
                        :submitting? false})))
  (is (false? (s/valid? :funding-modal-vm.transfer/actions
                        {:submit-label "Transfer"
                         :submit-disabled? false
                         :submitting? false
                         :unexpected true}))))

(deftest transfer-submap-contract-rejects-malformed-shapes-test
  (let [vm (transfer-vm)
        cases [["an extra key in the transfer submap"
                (assoc-in vm [:transfer :unexpected] true)]
               ["a missing to-perp? key"
                (update vm :transfer dissoc :to-perp?)]
               ["a non-boolean to-perp?"
                (assoc-in vm [:transfer :to-perp?] "false")]
               ["an extra key in the amount submap"
                (assoc-in vm [:transfer :amount :unexpected] true)]
               ["a non-string amount value"
                (assoc-in vm [:transfer :amount :value] 12)]
               ["a non-map actions value"
                (assoc-in vm [:transfer :actions] [:submit])]
               ["an extra key in the actions submap"
                (assoc-in vm [:transfer :actions :unexpected] true)]
               ["a non-boolean submit-disabled? flag"
                (assoc-in vm [:transfer :actions :submit-disabled?] nil)]]]
    (doseq [[label invalid-vm] cases]
      (testing label
        (is (false? (s/valid? :funding-modal-vm/transfer (:transfer invalid-vm))))
        (is (false? (funding-modal-contracts/funding-modal-vm-valid? invalid-vm)))))))

(deftest transfer-submap-contract-rejects-malformed-hyperevm-parts-test
  (let [vm (transfer-vm)
        cases [["an extra key in the route"
                (assoc-in vm [:transfer :route :unexpected] true)]
               ["an unknown route kind"
                (assoc-in vm [:transfer :route :kind] :evm->perps)]
               ["a location option without its data-role"
                (update-in vm [:transfer :from-options 0] dissoc :data-role)]
               ["an unknown blocked code"
                (assoc-in vm [:transfer :blocked] {:code :nope :checking? false :title nil
                                                   :message "x" :explorer-url nil :fix nil})]
               ["a blocked card without a boolean checking? flag"
                (assoc-in vm [:transfer :blocked] {:code :no-evm-gas :checking? nil :title nil
                                                   :message "x" :explorer-url nil :fix nil})]
               ["a fix without its status"
                (assoc-in vm [:transfer :blocked] {:code :no-evm-gas :checking? false :title nil
                                                   :message "x" :explorer-url nil
                                                   :fix {:label "Send" :action [:actions/x]
                                                         :status-message nil :disabled? false
                                                         :reason nil}})]
               ["an unknown run phase"
                (assoc-in vm [:transfer :evm] {:phase :done :flow-id nil :steps [] :step-index 0
                                               :step-count 0 :tx-url nil :error nil
                                               :arrival :idle :result nil :maybe-sent? false
                                               :retry-action nil :add-to-wallet? false})]
               ["a retry that is not an action"
                (assoc-in vm [:transfer :evm] {:phase :failed :flow-id nil :steps [] :step-index 0
                                               :step-count 0 :tx-url nil :error nil
                                               :arrival :idle :result nil :maybe-sent? false
                                               :retry-action "submit" :add-to-wallet? false})]
               ["a message that is not text"
                (assoc-in vm [:transfer :message] 42)]
               ["a summary row with an unknown tone"
                (assoc-in vm [:transfer :summary] [{:label "Fee" :value "x" :tone :loud}])]
               ["an amount submap without its notice"
                (update-in vm [:transfer :amount] dissoc :notice)]]]
    (doseq [[label invalid-vm] cases]
      (testing label
        (is (false? (s/valid? :funding-modal-vm/transfer (:transfer invalid-vm))))))))

