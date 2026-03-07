(ns hyperopen.api-wallets.domain.policy-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api-wallets.domain.policy :as policy]))

(def owner-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def generated-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(deftest form-normalization-and-validation-test
  (is (= :days-valid
         (policy/normalize-form-field "days valid")))
  (is (= "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
         (policy/normalize-form-value :address
                                      " 0xABCDEFabcdefABCDEFabcdefABCDEFabcdefABCD ")))
  (is (= "309"
         (policy/normalize-form-value :days-valid "30d9")))
  (is (= {:name nil
          :address nil
          :days-valid nil}
         (policy/form-errors {:name "Desk"
                              :address owner-address
                              :days-valid "180"})))
  (is (= "Enter a valid wallet address."
         (:address (policy/form-errors {:name "Desk"
                                        :address "bad-address"
                                        :days-valid ""}))))
  (is (= "Enter a value from 1 to 180 days."
         (:days-valid (policy/form-errors {:name "Desk"
                                          :address owner-address
                                          :days-valid "0"}))))
  (is (= "Enter an API wallet name."
         (policy/first-form-error {:name ""
                                   :address owner-address
                                   :days-valid ""})))
  (is (true? (policy/form-valid? {:name "Desk"
                                  :address owner-address
                                  :days-valid ""})))
  (is (false? (policy/form-valid? {:name "Desk"
                                   :address "bad-address"
                                   :days-valid ""}))))

(deftest sort-policy-merges-rows-and-toggles-directions-test
  (let [rows [{:row-kind :named
               :name "Desk"
               :address generated-address
               :valid-until-ms 1700000000000}
              {:row-kind :default
               :name "app.hyperopen.xyz"
               :address owner-address
               :valid-until-ms 1800000000000}]
        merged (policy/merged-rows [(first rows)] (second rows))]
    (is (= 2 (count merged)))
    (is (= {:column :valid-until
            :direction :desc}
           (policy/next-sort-state {:column :name
                                    :direction :asc}
                                   :valid-until)))
    (is (= {:column :name
            :direction :desc}
           (policy/next-sort-state {:column :name
                                    :direction :asc}
                                   :name)))
    (is (= ["app.hyperopen.xyz" "Desk"]
           (mapv :name
                 (policy/sorted-rows rows {:column :valid-until
                                           :direction :desc}))))
    (is (= ["app.hyperopen.xyz" "Desk"]
           (mapv :name
                 (policy/sorted-rows rows {:column :name
                                           :direction :asc}))))))

(deftest generated-key-preview-and-approval-name-policy-test
  (is (= "0xpriv"
         (policy/generated-private-key {:address generated-address
                                        :private-key "0xpriv"}
                                       "0xABCDEFabcdefABCDEFabcdefABCDEFabcdefABCD")))
  (is (nil? (policy/generated-private-key {:address generated-address
                                           :private-key "0xpriv"}
                                          owner-address)))
  (is (= 1702592000000
         (policy/valid-until-preview-ms 1700000000000 "30")))
  (is (= "Desk valid_until 1700000000000"
         (policy/approval-name-for-row {:row-kind :named
                                        :name "Desk"
                                        :approval-name "Desk valid_until 1700000000000"})))
  (is (nil? (policy/approval-name-for-row {:row-kind :default
                                           :name "app.hyperopen.xyz"}))))
