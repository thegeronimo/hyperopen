(ns hyperopen.api-wallets.domain.policy-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.api-wallets.domain.policy :as policy]))

(def owner-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def generated-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(deftest default-modal-state-and-form-validation-test
  (testing "default modal state stays fully reset"
    (is (= {:open? false
            :type nil
            :row nil
            :error nil
            :submitting? false}
           (policy/default-modal-state))))

  (testing "form normalization preserves the public field behavior"
    (is (= :days-valid
           (policy/normalize-form-field "days valid")))
    (is (= "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
           (policy/normalize-form-value :address
                                        " 0xABCDEFabcdefABCDEFabcdefABCDEFabcdefABCD ")))
    (is (= "309"
           (policy/normalize-form-value :days-valid "30d9"))))

  (testing "valid forms keep every field error clear"
    (is (= {:name nil
            :address nil
            :days-valid nil}
           (policy/form-errors {:name "Desk"
                                :address owner-address
                                :days-valid "180"})))
    (is (true? (policy/form-valid? {:name "Desk"
                                    :address owner-address
                                    :days-valid ""}))))

  (testing "blank and invalid inputs produce the expected field errors"
    (is (= {:name "Enter an API wallet name."
            :address "Enter a valid wallet address."
            :days-valid nil}
           (policy/form-errors {:name "   "
                                :address " "
                                :days-valid ""})))
    (is (= {:name "Enter an API wallet name."
            :address "Enter a valid wallet address."
            :days-valid "Enter a value from 1 to 180 days."}
           (policy/form-errors {:name nil
                                :address "bad-address"
                                :days-valid "0"})))
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
    (is (false? (policy/form-valid? {:name "Desk"
                                     :address "bad-address"
                                     :days-valid ""}))))

  (testing "each field error toggles between invalid and valid inputs"
    (is (= "Enter an API wallet name."
           (:name (policy/form-errors {:name ""
                                       :address owner-address
                                       :days-valid ""}))))
    (is (nil? (:name (policy/form-errors {:name "Desk"
                                          :address owner-address
                                          :days-valid ""}))))
    (is (= "Enter a valid wallet address."
           (:address (policy/form-errors {:name "Desk"
                                          :address ""
                                          :days-valid ""}))))
    (is (nil? (:address (policy/form-errors {:name "Desk"
                                             :address owner-address
                                             :days-valid ""}))))
    (is (nil? (:days-valid (policy/form-errors {:name "Desk"
                                                :address owner-address
                                                :days-valid ""}))))
    (is (= "Enter a value from 1 to 180 days."
           (:days-valid (policy/form-errors {:name "Desk"
                                             :address owner-address
                                             :days-valid "0"}))))
    (is (nil? (:days-valid (policy/form-errors {:name "Desk"
                                                :address owner-address
                                                :days-valid "30"}))))))

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

(deftest sorted-rows-valid-until-and-direction-tie-breaker-test
  (let [address-a "0x000000000000000000000000000000000000000a"
        address-b "0x000000000000000000000000000000000000000b"
        address-c "0x000000000000000000000000000000000000000c"
        missing-valid-until-rows [{:row-kind :named
                                   :name "Missing"
                                   :address address-c}
                                  {:row-kind :named
                                   :name "Present"
                                   :address address-b
                                   :valid-until-ms 1700000000000}]
        tied-valid-until-rows [{:row-kind :named
                                :name "Desk"
                                :address address-c}
                               {:row-kind :named
                                :name "Desk"
                                :address address-a}]
        identical-key-rows [{:row-kind :named
                             :name "Desk"
                             :address address-a
                             :tag :first}
                            {:row-kind :named
                             :name "Zulu"
                             :address address-c
                             :tag :zulu}
                            {:row-kind :named
                             :name "Desk"
                             :address address-a
                             :tag :second}]
        shuffled-name-rows [{:row-kind :named
                             :name "charlie"
                             :address address-c}
                            {:row-kind :named
                             :name "alpha"
                             :address address-a}
                            {:row-kind :named
                             :name "bravo"
                             :address address-b}]]
    (testing "ascending valid-until sorts rows without expiry last regardless of input order"
      (is (= [address-b address-c]
             (mapv :address
                   (policy/sorted-rows missing-valid-until-rows
                                       {:column :valid-until
                                        :direction :asc}))))
      (is (= [address-b address-c]
             (mapv :address
                   (policy/sorted-rows (vec (reverse missing-valid-until-rows))
                                       {:column :valid-until
                                        :direction :asc})))))

    (testing "rows with no expiry fall back to deterministic name then address ordering"
      (is (= [address-a address-c]
             (mapv :address
                   (policy/sorted-rows tied-valid-until-rows
                                       {:column :valid-until
                                        :direction :asc}))))
      (is (= [address-c address-a]
             (mapv :address
                   (policy/sorted-rows tied-valid-until-rows
                                       {:column :valid-until
                                        :direction :desc})))))

    (testing "name sorting honors direction from deliberately shuffled input"
      (is (= ["alpha" "bravo" "charlie"]
             (mapv :name
                   (policy/sorted-rows shuffled-name-rows
                                       {:column :name
                                        :direction :asc}))))
      (is (= ["charlie" "bravo" "alpha"]
             (mapv :name
                   (policy/sorted-rows shuffled-name-rows
                                       {:column :name
                                        :direction :desc})))))

    (testing "ties keep their input order for both ascending and descending sorts"
      (is (= [:first :second :zulu]
             (mapv :tag
                   (policy/sorted-rows identical-key-rows
                                       {:column :name
                                        :direction :asc}))))
      (is (= [:zulu :first :second]
             (mapv :tag
                   (policy/sorted-rows identical-key-rows
                                       {:column :name
                                        :direction :desc})))))))

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
