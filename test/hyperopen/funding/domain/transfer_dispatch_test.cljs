(ns hyperopen.funding.domain.transfer-dispatch-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.funding.domain.availability :as availability]
            [hyperopen.funding.domain.policy :as policy]
            [hyperopen.funding.domain.preview :as preview]
            [hyperopen.funding.domain.transfer-dispatch :as transfer-dispatch]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]))

(deftest perps-spot-route-delegates-to-the-legacy-functions-test
  (let [state (support/state)
        modal {:mode :transfer :to-perp? false :amount-input "2.25"}]
    (is (= (preview/transfer-preview state modal)
           (transfer-dispatch/transfer-preview* state modal support/now-ms)))
    (is (= {:ok? true :request {:action {:type "usdClassTransfer" :amount "2.25" :toPerp false}}}
           (transfer-dispatch/transfer-preview* state modal)))
    (is (= (availability/transfer-max-amount state modal)
           (transfer-dispatch/transfer-max-amount* state modal)))
    (is (number? (transfer-dispatch/transfer-max-amount* state modal))
        "the legacy MAX stays a number")))

(deftest an-explicit-perps-spot-pair-drives-the-legacy-direction-test
  (let [state (support/state)]
    (is (= {:ok? true :request {:action {:type "usdClassTransfer" :amount "5" :toPerp true}}}
           (transfer-dispatch/transfer-preview* state {:mode :transfer
                                                      :to-perp? false
                                                      :transfer-from :spot
                                                      :transfer-to :perps
                                                      :amount-input "5"})))))

(deftest hyperevm-routes-use-the-evm-preview-test
  (let [state (support/state)
        modal {:mode :transfer :transfer-from :spot :transfer-to :hyperevm
               :transfer-asset support/purr-index :amount-input "100"}]
    (is (= "sendAsset" (get-in (transfer-dispatch/transfer-preview* state modal support/now-ms)
                               [:request :action :type])))
    (is (= "9800" (transfer-dispatch/transfer-max-amount* state modal support/now-ms)))
    (is (= (transfer-dispatch/transfer-preview* state modal support/now-ms)
           (transfer-dispatch/preview* state modal support/now-ms)))))

(deftest an-invalid-route-never-falls-back-to-the-legacy-path-test
  (let [modal {:mode :transfer :to-perp? true :transfer-from :spot :amount-input "1"}]
    (is (= :invalid-route
           (get-in (transfer-dispatch/transfer-preview* (support/state) modal)
                   [:blocked :code])))
    (is (nil? (transfer-dispatch/transfer-max-amount* (support/state) modal)))))

(deftest policy-points-at-the-route-aware-dispatch-test
  (is (identical? policy/transfer-preview transfer-dispatch/transfer-preview*))
  (is (identical? policy/transfer-max-amount transfer-dispatch/transfer-max-amount*))
  (is (identical? policy/preview transfer-dispatch/preview*))
  (testing "other modes keep the legacy dispatcher"
    (is (= (preview/preview {} {:mode :withdraw})
           (transfer-dispatch/preview* {} {:mode :withdraw})))))
