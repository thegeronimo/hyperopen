(ns hyperopen.api-wallets.application.form-policy-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api-wallets.application.form-policy :as form-policy]))

(def owner-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def generated-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(deftest valid-forms-keep-errors-clear-and-drive-previews-test
  (let [form {:name "Desk"
              :address owner-address
              :days-valid "180"}]
    (is (= {:name nil
            :address nil
            :days-valid nil}
           (form-policy/form-errors form)))
    (is (true? (form-policy/form-valid? form)))
    (is (nil? (form-policy/first-form-error form)))
    (is (= "0xpriv"
           (form-policy/generated-private-key
            {:address generated-address
             :private-key "0xpriv"}
            "0xABCDEFabcdefABCDEFabcdefABCDEFabcdefABCD")))
    (is (= 1702592000000
           (form-policy/valid-until-preview-ms 1700000000000 "30")))))

(deftest invalid-or-partial-forms-surface-session-policy-errors-test
  (is (= {:name "Enter an API wallet name."
          :address "Enter a valid wallet address."
          :days-valid nil}
         (form-policy/form-errors {:name "   "
                                   :address " "
                                   :days-valid ""})))
  (is (= {:name "Enter an API wallet name."
          :address "Enter a valid wallet address."
          :days-valid "Enter a value from 1 to 180 days."}
         (form-policy/form-errors {:name nil
                                   :address "bad-address"
                                   :days-valid "0"})))
  (is (= "Enter an API wallet name."
         (form-policy/first-form-error {:name ""
                                        :address owner-address
                                        :days-valid ""})))
  (is (false? (form-policy/form-valid? {:name "Desk"
                                        :address "bad-address"
                                        :days-valid ""})))
  (is (nil? (form-policy/generated-private-key {:address generated-address
                                                :private-key "0xpriv"}
                                               owner-address)))
  (is (nil? (form-policy/valid-until-preview-ms "1700000000000" "30"))))
