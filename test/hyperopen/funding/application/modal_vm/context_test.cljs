(ns hyperopen.funding.application.modal-vm.context-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.application.modal-vm.context :as context]
            [hyperopen.funding.application.modal-vm.test-support :as support]))

(deftest generated-address-context-only-surfaces-the-active-asset-test
  (let [deps (support/base-deps)
        active-state (support/base-state {:modal {:deposit-generated-asset-key :btc
                                                  :deposit-generated-address "bc1generated"
                                                  :deposit-generated-signatures ["sig-a" "sig-b"]}})
        inactive-state (support/base-state {:modal {:deposit-generated-asset-key :eth
                                                    :deposit-generated-address "0xignored"
                                                    :deposit-generated-signatures ["sig-a"]}})
        active-ctx (-> (context/base-context deps active-state)
                       (context/with-asset-context deps)
                       (context/with-generated-address-context deps))
        inactive-ctx (-> (context/base-context deps inactive-state)
                         (context/with-asset-context deps)
                         (context/with-generated-address-context deps))]
    (is (= "bc1generated" (:generated-address active-ctx)))
    (is (= 2 (:generated-signature-count active-ctx)))
    (is (nil? (:generated-address inactive-ctx)))
    (is (= 0 (:generated-signature-count inactive-ctx)))))

(deftest preview-context-exposes-preview-result-shape-test
  (let [deps (support/base-deps)
        state (support/base-state {:preview-result {:ok? false
                                                    :display-message "Preview failed."}})
        ctx (-> (context/base-context deps state)
                (context/with-preview-context deps))]
    (is (false? (:preview-ok? ctx)))
    (is (= "Preview failed." (:preview-message ctx)))))
