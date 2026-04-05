(ns hyperopen.account.spectate-mode-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.spectate-mode-actions :as spectate-mode-actions]
            [hyperopen.platform :as platform]))

(def ^:private owner-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def ^:private spectated-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(def ^:private secondary-address
  "0x1111111111111111111111111111111111111111")

(deftest open-spectate-mode-modal-prefills-active-address-and-stores-anchor-test
  (let [state {:wallet {:address owner-address}
               :account-context {:spectate-mode {:active? true
                                              :address spectated-address}
                                 :watchlist [{:address spectated-address
                                              :label "Assistance"}]
                                 :spectate-ui {:search "0xdeadbeef"
                                            :label "Old"}}}]
    (is (= [[:effects/save-many [[[:account-context :spectate-ui :modal-open?] true]
                                 [[:account-context :spectate-ui :anchor] {:left 100
                                                                        :right 180
                                                                        :top 18
                                                                        :bottom 58
                                                                        :viewport-width 1440
                                                                        :viewport-height 900}]
                                 [[:account-context :spectate-ui :search] spectated-address]
                                 [[:account-context :spectate-ui :label] "Assistance"]
                                 [[:account-context :spectate-ui :editing-watchlist-address] nil]
                                 [[:account-context :spectate-ui :search-error] nil]]]]
           (spectate-mode-actions/open-spectate-mode-modal state
                                                     {:left 100
                                                      :right 180
                                                      :top 18
                                                      :bottom 58
                                                      :viewport-width 1440
                                                      :viewport-height 900
                                                      :ignored "noop"})))))

(deftest start-spectate-mode-persists-search-watchlist-and-active-state-test
  (with-redefs [platform/now-ms (fn [] 1710000000000)]
    (let [state {:wallet {:address owner-address}
                 :account-context {:spectate-ui {:search "0xABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCD"
                                              :label "Assistance"}
                                   :watchlist [{:address secondary-address
                                                :label nil}]}}
          effects (spectate-mode-actions/start-spectate-mode state)]
      (is (= [[:effects/save-many [[[:account-context :spectate-mode :active?] true]
                                   [[:account-context :spectate-mode :address] spectated-address]
                                   [[:account-context :spectate-mode :started-at-ms] 1710000000000]
                                   [[:account-context :spectate-ui :modal-open?] false]
                                   [[:account-context :spectate-ui :anchor] nil]
                                   [[:account-context :spectate-ui :search] spectated-address]
                                   [[:account-context :spectate-ui :last-search] spectated-address]
                                   [[:account-context :spectate-ui :label] ""]
                                   [[:account-context :spectate-ui :editing-watchlist-address] nil]
                                   [[:account-context :spectate-ui :search-error] nil]
                                   [[:account-context :watchlist] [{:address secondary-address
                                                                    :label nil}
                                                                   {:address spectated-address
                                                                    :label "Assistance"}]]]]
              [:effects/replace-state
               "/trade?spectate=0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"]
              [:effects/local-storage-set "spectate-mode-last-search:v1" spectated-address]
              [:effects/local-storage-set-json "spectate-mode-watchlist:v1" [{:address secondary-address
                                                                            :label nil}
                                                                           {:address spectated-address
                                                                            :label "Assistance"}]]]
             effects)))))

(deftest start-spectate-mode-rejects-invalid-address-test
  (let [state {:account-context {:spectate-ui {:search "not-an-address"}}}]
    (is (= [[:effects/save
             [:account-context :spectate-ui :search-error]
             "Enter a valid 0x-prefixed EVM address."]]
           (spectate-mode-actions/start-spectate-mode state)))))

(deftest watchlist-actions-persist-normalized-addresses-test
  (let [watchlist [{:address secondary-address
                    :label nil}
                   {:address spectated-address
                    :label "Old Label"}]
        add-effects (spectate-mode-actions/add-spectate-mode-watchlist-address
                     {:account-context {:spectate-ui {:search ""
                                                   :label ""}
                                        :watchlist watchlist}}
                     "0xABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCD")
        edit-effects (spectate-mode-actions/add-spectate-mode-watchlist-address
                      {:account-context {:spectate-ui {:search spectated-address
                                                    :label "Updated Label"
                                                    :editing-watchlist-address spectated-address}
                                         :watchlist watchlist}})
        remove-effects (spectate-mode-actions/remove-spectate-mode-watchlist-address
                        {:account-context {:watchlist watchlist}}
                        "0xABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCD")]
    (is (= [[:effects/save-many [[[:account-context :watchlist] watchlist]
                                 [[:account-context :spectate-ui :search] spectated-address]
                                 [[:account-context :spectate-ui :last-search] spectated-address]
                                 [[:account-context :spectate-ui :label] ""]
                                 [[:account-context :spectate-ui :editing-watchlist-address] nil]
                                 [[:account-context :spectate-ui :search-error] nil]]]
            [:effects/local-storage-set "spectate-mode-last-search:v1" spectated-address]
            [:effects/local-storage-set-json "spectate-mode-watchlist:v1" watchlist]]
           add-effects))
    (is (= [[:effects/save-many [[[:account-context :watchlist] [{:address secondary-address
                                                                  :label nil}
                                                                 {:address spectated-address
                                                                  :label "Updated Label"}]]
                                 [[:account-context :spectate-ui :search] spectated-address]
                                 [[:account-context :spectate-ui :last-search] spectated-address]
                                 [[:account-context :spectate-ui :label] ""]
                                 [[:account-context :spectate-ui :editing-watchlist-address] nil]
                                 [[:account-context :spectate-ui :search-error] nil]]]
            [:effects/local-storage-set "spectate-mode-last-search:v1" spectated-address]
            [:effects/local-storage-set-json "spectate-mode-watchlist:v1" [{:address secondary-address
                                                                          :label nil}
                                                                         {:address spectated-address
                                                                          :label "Updated Label"}]]]
           edit-effects))
    (is (= [[:effects/save-many [[[:account-context :watchlist] [{:address secondary-address
                                                                  :label nil}]]]]
            [:effects/local-storage-set-json "spectate-mode-watchlist:v1" [{:address secondary-address
                                                                          :label nil}]]]
           remove-effects))))

(deftest label-edit-and-copy-actions-emit-expected-effects-test
  (let [watchlist [{:address spectated-address
                    :label "Assistance"}]]
    (is (= [[:effects/save-many [[[:account-context :spectate-ui :search] spectated-address]
                                 [[:account-context :spectate-ui :label] "Assistance"]
                                 [[:account-context :spectate-ui :editing-watchlist-address] spectated-address]
                                 [[:account-context :spectate-ui :search-error] nil]]]]
           (spectate-mode-actions/edit-spectate-mode-watchlist-address
            {:account-context {:watchlist watchlist}}
            spectated-address)))
    (is (= [[:effects/save-many [[[:account-context :spectate-ui :label] ""]
                                 [[:account-context :spectate-ui :editing-watchlist-address] nil]
                                 [[:account-context :spectate-ui :search-error] nil]]]]
           (spectate-mode-actions/clear-spectate-mode-watchlist-edit {})))
    (is (= [[:effects/copy-wallet-address spectated-address]]
           (spectate-mode-actions/copy-spectate-mode-watchlist-address
            {}
            spectated-address)))))

(deftest copy-spectate-mode-watchlist-link-emits-current-route-and_address-test
  (is (= [[:effects/copy-spectate-link "/portfolio" spectated-address]]
         (spectate-mode-actions/copy-spectate-mode-watchlist-link
          {:router {:path "/portfolio"}}
          spectated-address))))

(deftest stop-and-spectate-actions-clear-or-ignore-as-expected-test
  (is (= [[:effects/save-many [[[:account-context :spectate-mode :active?] false]
                               [[:account-context :spectate-mode :address] nil]
                               [[:account-context :spectate-mode :started-at-ms] nil]
                               [[:account-context :spectate-ui :modal-open?] false]
                               [[:account-context :spectate-ui :anchor] nil]
                               [[:account-context :spectate-ui :label] ""]
                               [[:account-context :spectate-ui :editing-watchlist-address] nil]
                               [[:account-context :spectate-ui :search-error] nil]]]
            [:effects/replace-state "/trade"]]
         (spectate-mode-actions/stop-spectate-mode {})))
  (is (= []
         (spectate-mode-actions/start-spectate-mode-watchlist-address {} " "))))

(deftest stop-spectate-mode-emits-disconnected-lifecycle-clear-when-no-account-remains-test
  (let [state {:router {:path "/trade"}
               :wallet {:connected? false
                        :address nil}
               :account-context {:spectate-mode {:active? true
                                                 :address spectated-address
                                                 :started-at-ms 1}}}]
    (is (= [[:effects/save-many [[[:account-context :spectate-mode :active?] false]
                                 [[:account-context :spectate-mode :address] nil]
                                 [[:account-context :spectate-mode :started-at-ms] nil]
                                 [[:account-context :spectate-ui :modal-open?] false]
                                 [[:account-context :spectate-ui :anchor] nil]
                                 [[:account-context :spectate-ui :label] ""]
                                 [[:account-context :spectate-ui :editing-watchlist-address] nil]
                                 [[:account-context :spectate-ui :search-error] nil]]]
             [:effects/replace-state "/trade"]
             [:effects/clear-disconnected-account-lifecycle spectated-address]]
           (spectate-mode-actions/stop-spectate-mode state)))))

(deftest spectate-mode-trade-route-links-preserve-market-and-tab-query-test
  (with-redefs [platform/now-ms (fn [] 1710000000000)]
    (let [state {:active-asset "ETH"
                 :router {:path "/trade/ETH"}
                 :account-info {:selected-tab :positions}
                 :account-context {:spectate-ui {:search spectated-address
                                                 :label ""}
                                   :watchlist []}}
          start-effects (spectate-mode-actions/start-spectate-mode state)]
      (is (= [:effects/replace-state
              "/trade?market=ETH&tab=positions&spectate=0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"]
             (second start-effects)))
      (is (= [:effects/replace-state
              "/trade?market=ETH&tab=positions"]
             (second (spectate-mode-actions/stop-spectate-mode state)))))))
