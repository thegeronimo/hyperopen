(ns hyperopen.api.projections.user-fees-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api.projections :as projections]))

(deftest user-fees-projections-track-request-address-scope-test
  (let [address "0x2ba553d9f990a3b66b03b2dc0d030dfc1c061036"
        payload {:dailyUserVlm [{:date "2026-04-16"
                                 :exchange "100"}]}
        loading (projections/begin-user-fees-load {} address)
        success (projections/apply-user-fees-success loading address payload)
        failed (projections/apply-user-fees-error loading address (js/Error. "fees-fail"))]
    (is (= true (get-in loading [:portfolio :user-fees-loading?])))
    (is (= address (get-in loading [:portfolio :user-fees-loading-for-address])))
    (is (= payload (get-in success [:portfolio :user-fees])))
    (is (= false (get-in success [:portfolio :user-fees-loading?])))
    (is (nil? (get-in success [:portfolio :user-fees-loading-for-address])))
    (is (= address (get-in success [:portfolio :user-fees-loaded-for-address])))
    (is (number? (get-in success [:portfolio :user-fees-loaded-at-ms])))
    (is (= false (get-in failed [:portfolio :user-fees-loading?])))
    (is (nil? (get-in failed [:portfolio :user-fees-loading-for-address])))
    (is (= "Error: fees-fail" (get-in failed [:portfolio :user-fees-error])))
    (is (= address (get-in failed [:portfolio :user-fees-error-for-address])))))
