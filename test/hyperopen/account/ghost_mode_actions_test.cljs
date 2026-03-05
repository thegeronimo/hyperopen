(ns hyperopen.account.ghost-mode-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.ghost-mode-actions :as ghost-mode-actions]
            [hyperopen.platform :as platform]))

(def ^:private owner-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def ^:private spectated-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(def ^:private secondary-address
  "0x1111111111111111111111111111111111111111")

(deftest open-ghost-mode-modal-prefills-active-address-and-stores-anchor-test
  (let [state {:wallet {:address owner-address}
               :account-context {:ghost-mode {:active? true
                                              :address spectated-address}
                                 :watchlist [{:address spectated-address
                                              :label "Assistance"}]
                                 :ghost-ui {:search "0xdeadbeef"
                                            :label "Old"}}}]
    (is (= [[:effects/save-many [[[:account-context :ghost-ui :modal-open?] true]
                                 [[:account-context :ghost-ui :anchor] {:left 100
                                                                        :right 180
                                                                        :top 18
                                                                        :bottom 58
                                                                        :viewport-width 1440
                                                                        :viewport-height 900}]
                                 [[:account-context :ghost-ui :search] spectated-address]
                                 [[:account-context :ghost-ui :label] "Assistance"]
                                 [[:account-context :ghost-ui :editing-watchlist-address] nil]
                                 [[:account-context :ghost-ui :search-error] nil]]]]
           (ghost-mode-actions/open-ghost-mode-modal state
                                                     {:left 100
                                                      :right 180
                                                      :top 18
                                                      :bottom 58
                                                      :viewport-width 1440
                                                      :viewport-height 900
                                                      :ignored "noop"})))))

(deftest start-ghost-mode-persists-search-watchlist-and-active-state-test
  (with-redefs [platform/now-ms (fn [] 1710000000000)]
    (let [state {:wallet {:address owner-address}
                 :account-context {:ghost-ui {:search "0xABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCD"
                                              :label "Assistance"}
                                   :watchlist [{:address secondary-address
                                                :label nil}]}}
          effects (ghost-mode-actions/start-ghost-mode state)]
      (is (= [[:effects/save-many [[[:account-context :ghost-mode :active?] true]
                                   [[:account-context :ghost-mode :address] spectated-address]
                                   [[:account-context :ghost-mode :started-at-ms] 1710000000000]
                                   [[:account-context :ghost-ui :modal-open?] false]
                                   [[:account-context :ghost-ui :anchor] nil]
                                   [[:account-context :ghost-ui :search] spectated-address]
                                   [[:account-context :ghost-ui :last-search] spectated-address]
                                   [[:account-context :ghost-ui :label] ""]
                                   [[:account-context :ghost-ui :editing-watchlist-address] nil]
                                   [[:account-context :ghost-ui :search-error] nil]
                                   [[:account-context :watchlist] [{:address secondary-address
                                                                    :label nil}
                                                                   {:address spectated-address
                                                                    :label "Assistance"}]]]]
              [:effects/local-storage-set "ghost-mode-last-search:v1" spectated-address]
              [:effects/local-storage-set-json "ghost-mode-watchlist:v1" [{:address secondary-address
                                                                            :label nil}
                                                                           {:address spectated-address
                                                                            :label "Assistance"}]]]
             effects)))))

(deftest start-ghost-mode-rejects-invalid-address-test
  (let [state {:account-context {:ghost-ui {:search "not-an-address"}}}]
    (is (= [[:effects/save
             [:account-context :ghost-ui :search-error]
             "Enter a valid 0x-prefixed EVM address."]]
           (ghost-mode-actions/start-ghost-mode state)))))

(deftest watchlist-actions-persist-normalized-addresses-test
  (let [watchlist [{:address secondary-address
                    :label nil}
                   {:address spectated-address
                    :label "Old Label"}]
        add-effects (ghost-mode-actions/add-ghost-mode-watchlist-address
                     {:account-context {:ghost-ui {:search ""
                                                   :label ""}
                                        :watchlist watchlist}}
                     "0xABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCD")
        edit-effects (ghost-mode-actions/add-ghost-mode-watchlist-address
                      {:account-context {:ghost-ui {:search spectated-address
                                                    :label "Updated Label"
                                                    :editing-watchlist-address spectated-address}
                                         :watchlist watchlist}})
        remove-effects (ghost-mode-actions/remove-ghost-mode-watchlist-address
                        {:account-context {:watchlist watchlist}}
                        "0xABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCD")]
    (is (= [[:effects/save-many [[[:account-context :watchlist] watchlist]
                                 [[:account-context :ghost-ui :search] spectated-address]
                                 [[:account-context :ghost-ui :last-search] spectated-address]
                                 [[:account-context :ghost-ui :label] ""]
                                 [[:account-context :ghost-ui :editing-watchlist-address] nil]
                                 [[:account-context :ghost-ui :search-error] nil]]]
            [:effects/local-storage-set "ghost-mode-last-search:v1" spectated-address]
            [:effects/local-storage-set-json "ghost-mode-watchlist:v1" watchlist]]
           add-effects))
    (is (= [[:effects/save-many [[[:account-context :watchlist] [{:address secondary-address
                                                                  :label nil}
                                                                 {:address spectated-address
                                                                  :label "Updated Label"}]]
                                 [[:account-context :ghost-ui :search] spectated-address]
                                 [[:account-context :ghost-ui :last-search] spectated-address]
                                 [[:account-context :ghost-ui :label] ""]
                                 [[:account-context :ghost-ui :editing-watchlist-address] nil]
                                 [[:account-context :ghost-ui :search-error] nil]]]
            [:effects/local-storage-set "ghost-mode-last-search:v1" spectated-address]
            [:effects/local-storage-set-json "ghost-mode-watchlist:v1" [{:address secondary-address
                                                                          :label nil}
                                                                         {:address spectated-address
                                                                          :label "Updated Label"}]]]
           edit-effects))
    (is (= [[:effects/save-many [[[:account-context :watchlist] [{:address secondary-address
                                                                  :label nil}]]]]
            [:effects/local-storage-set-json "ghost-mode-watchlist:v1" [{:address secondary-address
                                                                          :label nil}]]]
           remove-effects))))

(deftest label-edit-and-copy-actions-emit-expected-effects-test
  (let [watchlist [{:address spectated-address
                    :label "Assistance"}]]
    (is (= [[:effects/save-many [[[:account-context :ghost-ui :search] spectated-address]
                                 [[:account-context :ghost-ui :label] "Assistance"]
                                 [[:account-context :ghost-ui :editing-watchlist-address] spectated-address]
                                 [[:account-context :ghost-ui :search-error] nil]]]]
           (ghost-mode-actions/edit-ghost-mode-watchlist-address
            {:account-context {:watchlist watchlist}}
            spectated-address)))
    (is (= [[:effects/save-many [[[:account-context :ghost-ui :label] ""]
                                 [[:account-context :ghost-ui :editing-watchlist-address] nil]
                                 [[:account-context :ghost-ui :search-error] nil]]]]
           (ghost-mode-actions/clear-ghost-mode-watchlist-edit {})))
    (is (= [[:effects/copy-wallet-address spectated-address]]
           (ghost-mode-actions/copy-ghost-mode-watchlist-address
            {}
            spectated-address)))))

(deftest stop-and-spectate-actions-clear-or-ignore-as-expected-test
  (is (= [[:effects/save-many [[[:account-context :ghost-mode :active?] false]
                               [[:account-context :ghost-mode :address] nil]
                               [[:account-context :ghost-mode :started-at-ms] nil]
                               [[:account-context :ghost-ui :modal-open?] false]
                               [[:account-context :ghost-ui :anchor] nil]
                               [[:account-context :ghost-ui :label] ""]
                               [[:account-context :ghost-ui :editing-watchlist-address] nil]
                               [[:account-context :ghost-ui :search-error] nil]]]]
         (ghost-mode-actions/stop-ghost-mode {})))
  (is (= []
         (ghost-mode-actions/spectate-ghost-mode-watchlist-address {} " "))))
