(ns hyperopen.funding.application.modal-vm.presentation-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.application.modal-vm.test-support :as support]))

(deftest presentation-context-hides-preview-feedback-before-deposit-amount-entry-test
  (let [state (support/base-state {:modal {:deposit-step :asset-select}
                                   :deposit-asset nil
                                   :preview-result {:ok? false
                                                    :display-message "Enter a valid amount."}})
        ctx (support/build-presented-context (support/base-deps) state)]
    (is (= :deposit/select (:content-kind ctx)))
    (is (nil? (:status-message ctx)))
    (is (false? (:show-status-message? ctx)))
    (is (true? (:submit-disabled? ctx)))))

(deftest presentation-context-marks-unsupported-deposit-flows-test
  (let [unsupported-asset (support/deposit-asset :flow-kind :route
                                                 :implemented? false)
        state (support/base-state {:deposit-assets [unsupported-asset]
                                   :deposit-asset unsupported-asset
                                   :preview-result {:ok? false
                                                    :display-message "Deposit routing unavailable."}})
        ctx (support/build-presented-context (support/base-deps) state)]
    (is (= :deposit/unavailable (:content-kind ctx)))
    (is (= "Route-based bridge/swap flow will be implemented in the next milestone."
           (:deposit-unsupported-detail ctx)))
    (is (= "Deposit unavailable" (:deposit-submit-label ctx)))))
