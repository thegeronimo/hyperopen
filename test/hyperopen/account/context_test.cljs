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

(deftest effective-account-address-prefers-spectate-when-active-test
  (let [owner "0x1111111111111111111111111111111111111111"
        spectate "0x2222222222222222222222222222222222222222"]
    (is (= spectate
           (account-context/effective-account-address
            {:wallet {:address owner}
             :account-context {:spectate-mode {:active? true
                                            :address spectate}}})))
    (is (= owner
           (account-context/effective-account-address
            {:wallet {:address owner}
             :account-context {:spectate-mode {:active? false
                                            :address spectate}}})))
    (is (= owner
           (account-context/effective-account-address
           {:wallet {:address owner}
             :account-context {:spectate-mode {:active? true
                                            :address "bad"}}})))))

(deftest effective-account-address-prefers-trader-portfolio-route-over-spectate-and-owner-test
  (let [owner "0x1111111111111111111111111111111111111111"
        spectate "0x2222222222222222222222222222222222222222"
        trader "0x3333333333333333333333333333333333333333"]
    (is (= trader
           (account-context/effective-account-address
            {:wallet {:address owner}
             :router {:path (str "/portfolio/trader/" trader)}
             :account-context {:spectate-mode {:active? true
                                               :address spectate}}})))
    (is (= trader
           (account-context/trader-portfolio-address
            {:router {:path (str "/portfolio/trader/" trader)}})))
    (is (true? (account-context/trader-portfolio-route-active?
                {:router {:path (str "/portfolio/trader/" trader)}})))))

(deftest mutations-allowed-is-disabled-during-spectate-mode-test
  (is (false? (account-context/mutations-allowed?
               {:account-context {:spectate-mode {:active? true
                                               :address "0x1111111111111111111111111111111111111111"}}})))
  (is (true? (account-context/mutations-allowed?
              {:account-context {:spectate-mode {:active? false
                                              :address "0x1111111111111111111111111111111111111111"}}}))))

(deftest mutations-allowed-is-disabled-on-trader-portfolio-route-test
  (let [trader "0x3333333333333333333333333333333333333333"]
    (is (false? (account-context/mutations-allowed?
                 {:router {:path (str "/portfolio/trader/" trader)}})))
    (is (= account-context/trader-portfolio-read-only-message
           (account-context/mutations-blocked-message
            {:router {:path (str "/portfolio/trader/" trader)}})))))

(deftest user-stream-subscriptions-are-disabled-only-on-trader-portfolio-routes-test
  (let [owner "0x1111111111111111111111111111111111111111"
        spectate "0x2222222222222222222222222222222222222222"
        trader "0x3333333333333333333333333333333333333333"]
    (is (true? (account-context/user-stream-subscriptions-enabled?
                {:wallet {:address owner}
                 :router {:path "/portfolio"}})))
    (is (true? (account-context/user-stream-subscriptions-enabled?
                {:wallet {:address owner}
                 :account-context {:spectate-mode {:active? true
                                                   :address spectate}}})))
    (is (false? (account-context/user-stream-subscriptions-enabled?
                 {:wallet {:address owner}
                  :router {:path (str "/portfolio/trader/" trader)}})))
    (is (= spectate
           (account-context/live-user-stream-address
            {:wallet {:address owner}
             :account-context {:spectate-mode {:active? true
                                               :address spectate}}})))
    (is (nil? (account-context/live-user-stream-address
               {:wallet {:address owner}
                :router {:path (str "/portfolio/trader/" trader)}})))))
