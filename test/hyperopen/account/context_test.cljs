(ns hyperopen.account.context-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.context :as account-context]))

(deftest normalize-address-handles-trim-case-and-invalid-values-test
  (is (= "0x1111111111111111111111111111111111111111"
         (account-context/normalize-address " 0x1111111111111111111111111111111111111111 ")))
  (is (= "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
         (account-context/normalize-address "0xAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")))
  (is (nil? (account-context/normalize-address "")))
  (is (nil? (account-context/normalize-address "0xabc")))
  (is (nil? (account-context/normalize-address "not-an-address"))))

(deftest normalize-watchlist-filters-invalid-and-deduplicates-test
  (is (= [{:address "0x1111111111111111111111111111111111111111"
           :label "Primary"}
          {:address "0x2222222222222222222222222222222222222222"
           :label "Treasury"}]
         (account-context/normalize-watchlist
           ["0x1111111111111111111111111111111111111111"
            {:address "0x1111111111111111111111111111111111111111"
             :label "Primary"}
            {"address" "0x2222222222222222222222222222222222222222"
             "label" "Treasury"}
            "bad"]))))

(deftest watchlist-entry-upsert-and-remove-support-labeled-entries-test
  (let [initial [{:address "0x1111111111111111111111111111111111111111"
                  :label nil}
                 {:address "0x2222222222222222222222222222222222222222"
                  :label "Treasury"}]]
    (is (= [{:address "0x1111111111111111111111111111111111111111"
             :label "Primary"}
            {:address "0x2222222222222222222222222222222222222222"
             :label "Treasury"}]
           (account-context/upsert-watchlist-entry
            initial
            "0x1111111111111111111111111111111111111111"
            "Primary")))
    (is (= [{:address "0x1111111111111111111111111111111111111111"
             :label nil}]
           (account-context/remove-watchlist-entry
            initial
            "0x2222222222222222222222222222222222222222")))))

(deftest effective-account-address-prefers-ghost-when-active-test
  (let [owner "0x1111111111111111111111111111111111111111"
        ghost "0x2222222222222222222222222222222222222222"]
    (is (= ghost
           (account-context/effective-account-address
            {:wallet {:address owner}
             :account-context {:ghost-mode {:active? true
                                            :address ghost}}})))
    (is (= owner
           (account-context/effective-account-address
            {:wallet {:address owner}
             :account-context {:ghost-mode {:active? false
                                            :address ghost}}})))
    (is (= owner
           (account-context/effective-account-address
            {:wallet {:address owner}
             :account-context {:ghost-mode {:active? true
                                            :address "bad"}}})))))

(deftest mutations-allowed-is-disabled-during-ghost-mode-test
  (is (false? (account-context/mutations-allowed?
               {:account-context {:ghost-mode {:active? true
                                               :address "0x1111111111111111111111111111111111111111"}}})))
  (is (true? (account-context/mutations-allowed?
              {:account-context {:ghost-mode {:active? false
                                              :address "0x1111111111111111111111111111111111111111"}}}))))
