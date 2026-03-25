(ns hyperopen.funding.application.modal-vm.models-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.application.modal-vm.models :as models]
            [hyperopen.funding.application.modal-vm.test-support :as support]))

(deftest build-view-model-assembles-modal-feedback-and-deposit-sections-test
  (let [view-model (->> (support/base-state {:modal {:deposit-generated-asset-key :btc
                                                     :deposit-generated-address "bc1generated"
                                                     :deposit-generated-signatures ["sig-a" "sig-b"]}})
                        (support/build-presented-context (support/base-deps))
                        models/build-view-model)]
    (is (= {:open? true
            :mode :deposit
            :title "Deposit BTC"
            :anchor nil}
           (:modal view-model)))
    (is (= {:kind :deposit/address} (:content view-model)))
    (is (= {:message nil
            :visible? false
            :tone :error}
           (:feedback view-model)))
    (is (= "bc1generated" (get-in view-model [:deposit :flow :generated-address])))
    (is (= 2 (get-in view-model [:deposit :flow :generated-signature-count])))))
