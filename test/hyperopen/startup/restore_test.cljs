(ns hyperopen.startup.restore-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.platform :as platform]
            [hyperopen.startup.restore :as startup-restore]))

(deftest restore-ghost-mode-preferences-loads-watchlist-and-search-test
  (let [store (atom {:account-context {:ghost-ui {:search-error "old"}}})]
    (with-redefs [platform/local-storage-get (fn [key]
                                               (case key
                                                 "ghost-mode-watchlist:v1"
                                                 "[{\"address\":\"0x1111111111111111111111111111111111111111\",\"label\":\"Core\"},\"bad\",\"0x2222222222222222222222222222222222222222\"]"
                                                 "ghost-mode-last-search:v1"
                                                 " 0x3333333333333333333333333333333333333333 "
                                                 nil))]
      (startup-restore/restore-ghost-mode-preferences! store)
      (is (= [{:address "0x1111111111111111111111111111111111111111"
               :label "Core"}
              {:address "0x2222222222222222222222222222222222222222"
               :label nil}]
             (get-in @store [:account-context :watchlist])))
      (is (= true (get-in @store [:account-context :watchlist-loaded?])))
      (is (= "0x3333333333333333333333333333333333333333"
             (get-in @store [:account-context :ghost-ui :search])))
      (is (= "0x3333333333333333333333333333333333333333"
             (get-in @store [:account-context :ghost-ui :last-search])))
      (is (= "" (get-in @store [:account-context :ghost-ui :label])))
      (is (nil? (get-in @store [:account-context :ghost-ui :editing-watchlist-address])))
      (is (nil? (get-in @store [:account-context :ghost-ui :search-error]))))))

(deftest restore-ghost-mode-preferences-falls-back-on-malformed-storage-test
  (let [store (atom {:account-context (account-context/default-account-context-state)})]
    (with-redefs [platform/local-storage-get (fn [key]
                                               (case key
                                                 "ghost-mode-watchlist:v1"
                                                 "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,not-valid,0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                                                 "ghost-mode-last-search:v1"
                                                 " "
                                                 nil))]
      (startup-restore/restore-ghost-mode-preferences! store)
      (is (= [{:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
               :label nil}
              {:address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
               :label nil}]
             (get-in @store [:account-context :watchlist])))
      (is (= "" (get-in @store [:account-context :ghost-ui :search])))
      (is (= "" (get-in @store [:account-context :ghost-ui :last-search])))
      (is (= "" (get-in @store [:account-context :ghost-ui :label])))
      (is (nil? (get-in @store [:account-context :ghost-ui :editing-watchlist-address]))))))
