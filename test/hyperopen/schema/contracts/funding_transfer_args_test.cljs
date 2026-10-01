(ns hyperopen.schema.contracts.funding-transfer-args-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.schema.contracts :as contracts]
            [hyperopen.schema.runtime-registration.funding :as funding-registration]))

(defn- action-valid?
  [action-id args]
  (try
    (contracts/assert-action-args! action-id args {:phase :test})
    true
    (catch :default _ false)))

(deftest transfer-location-args-test
  (is (action-valid? :actions/set-funding-transfer-location [:from :hyperevm]))
  (is (action-valid? :actions/set-funding-transfer-location ["to" "spot"]))
  (is (not (action-valid? :actions/set-funding-transfer-location [:from :evm])))
  (is (not (action-valid? :actions/set-funding-transfer-location [:middle :spot])))
  (is (not (action-valid? :actions/set-funding-transfer-location [:from]))))

(deftest transfer-asset-and-percent-args-test
  (is (action-valid? :actions/select-funding-transfer-asset [150]))
  (is (not (action-valid? :actions/select-funding-transfer-asset ["150"])))
  (is (not (action-valid? :actions/select-funding-transfer-asset [-1])))
  (is (action-valid? :actions/set-funding-transfer-amount-percent [25]))
  (is (action-valid? :actions/set-funding-transfer-amount-percent [100]))
  (is (not (action-valid? :actions/set-funding-transfer-amount-percent [0])))
  (is (not (action-valid? :actions/set-funding-transfer-amount-percent [101]))))

(deftest transfer-no-arg-actions-test
  (doseq [action-id [:actions/swap-funding-transfer-locations
                     :actions/submit-funding-transfer-gas-topup
                     :actions/reset-funding-transfer-evm
                     :actions/retry-funding-transfer-capability
                     :actions/add-funding-transfer-token-to-wallet]]
    (is (action-valid? action-id []) (str action-id))
    (is (not (action-valid? action-id [1])) (str action-id))))

(deftest gas-topup-is-effect-order-covered-test
  (is (contains? funding-registration/effect-order-policy-required-action-ids
                 :actions/submit-funding-transfer-gas-topup)))
