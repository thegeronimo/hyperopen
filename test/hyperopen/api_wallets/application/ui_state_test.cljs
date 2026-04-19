(ns hyperopen.api-wallets.application.ui-state-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api-wallets.application.ui-state :as ui-state]))

(deftest default-ui-state-and-form-normalization-stay-stable-test
  (is (= {:column :name
          :direction :asc}
         (ui-state/default-sort-state)))
  (is (= {:name ""
          :address ""
          :days-valid ""}
         (ui-state/default-form)))
  (is (= {:open? false
          :type nil
          :row nil
          :error nil
          :submitting? false}
         (ui-state/default-modal-state)))
  (is (= {:address nil
          :private-key nil}
         (ui-state/default-generated-state)))
  (is (= :days-valid
         (ui-state/normalize-form-field "days valid")))
  (is (= "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
         (ui-state/normalize-form-value
          :address
          " 0xABCDEFabcdefABCDEFabcdefABCDEFabcdefABCD ")))
  (is (= "309"
         (ui-state/normalize-form-value :days-valid "30d9"))))

(deftest sort-ui-state-normalizes-legacy-inputs-and-toggles-directions-test
  (is (= {:column :address
          :direction :asc}
         (ui-state/normalize-sort-state
          {:column "wallet-address"
           :direction "ASC"})))
  (is (= {:column :valid-until
          :direction :desc}
         (ui-state/next-sort-state
          {:column :name
           :direction :asc}
          :valid-until)))
  (is (= {:column :valid-until
          :direction :asc}
         (ui-state/next-sort-state
          {:column :valid-until
           :direction :desc}
          :valid-until)))
  (is (= {:column :name
          :direction :asc}
         (ui-state/normalize-sort-state
          {:column "garbage"
           :direction "garbage"})))
  (is (nil? (ui-state/normalize-form-field "unsupported-field")))
  (is (= ""
         (ui-state/normalize-form-value :days-valid nil))))

(deftest sort-columns-directions-and-states-normalize-edge-inputs-test
  (doseq [[value expected] [[:name :name]
                            [" Name " :name]
                            [:address :address]
                            ["wallet" :address]
                            ["wallet address" :address]
                            ["api_wallet_address" :address]
                            [:valid-until :valid-until]
                            ["validUntil" :valid-until]
                            ["valid-until-ms" :valid-until]
                            [nil :name]
                            [42 :name]
                            ["unsupported" :name]]]
    (is (= expected (ui-state/normalize-sort-column value))
        (str "sort column " (pr-str value))))
  (doseq [[value expected] [[:asc :asc]
                            [:desc :desc]
                            [" ASC " :asc]
                            ["desc" :desc]
                            ["sideways" :asc]
                            [nil :asc]
                            [42 :asc]]]
    (is (= expected (ui-state/normalize-sort-direction value))
        (str "sort direction " (pr-str value))))
  (is (= {:column :name
          :direction :asc}
         (ui-state/normalize-sort-state nil)))
  (is (= {:column :address
          :direction :desc}
         (ui-state/normalize-sort-state {:column "wallet"
                                         :direction " DESC "}))))

(deftest next-sort-state-toggles-normalized-columns-and-defaults-new-columns-test
  (is (= {:column :address
          :direction :desc}
         (ui-state/next-sort-state {:column "wallet-address"
                                    :direction "ASC"}
                                   "wallet")))
  (is (= {:column :name
          :direction :asc}
         (ui-state/next-sort-state {:column :address
                                    :direction :desc}
                                   :name)))
  (is (= {:column :valid-until
          :direction :desc}
         (ui-state/next-sort-state nil "validUntil")))
  (is (= {:column :name
          :direction :desc}
         (ui-state/next-sort-state nil "unsupported"))))

(deftest form-fields-and-values-normalize-supported-inputs-only-test
  (doseq [[value expected] [[:name :name]
                            [" Name " :name]
                            [:address :address]
                            ["address" :address]
                            [:days-valid :days-valid]
                            ["days valid" :days-valid]
                            ["days_valid" :days-valid]
                            [nil nil]
                            [42 nil]
                            ["wallet" nil]
                            ["expires-at" nil]]]
    (is (= expected (ui-state/normalize-form-field value))
        (str "form field " (pr-str value))))
  (is (= "  Desk API  "
         (ui-state/normalize-form-value :name "  Desk API  ")))
  (is (= ""
         (ui-state/normalize-form-value :name nil)))
  (is (= "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
         (ui-state/normalize-form-value
          :address
          " 0xABCDEFabcdefABCDEFabcdefABCDEFabcdefABCD ")))
  (is (= ""
         (ui-state/normalize-form-value :address nil)))
  (is (= "309"
         (ui-state/normalize-form-value :days-valid " 30 days, 9 hours ")))
  (is (= ""
         (ui-state/normalize-form-value :days-valid nil)))
  (is (= "42"
         (ui-state/normalize-form-value :unsupported 42)))
  (is (= ""
         (ui-state/normalize-form-value :unsupported nil))))
