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
