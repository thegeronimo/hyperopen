(ns hyperopen.vaults.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.vaults.actions :as actions]))

(deftest parse-vault-route-covers-list-detail-and-invalid-address-branches-test
  (is (= {:kind :list
          :path "/vaults"}
         (actions/parse-vault-route "/vaults/")))
  (is (= {:kind :detail
          :path "/vaults/0x1234567890abcdef1234567890abcdef12345678"
          :raw-vault-address "0x1234567890abcdef1234567890abcdef12345678"
          :vault-address "0x1234567890abcdef1234567890abcdef12345678"}
         (actions/parse-vault-route "/vaults/0x1234567890abcdef1234567890abcdef12345678?tab=about")))
  (is (= {:kind :detail
          :path "/vaults/not-an-address"
          :raw-vault-address "not-an-address"
          :vault-address nil}
         (actions/parse-vault-route "/vaults/not-an-address")))
  (is (= {:kind :other
          :path "/trade"}
         (actions/parse-vault-route "/trade"))))

(deftest load-vault-actions-emit-projection-before-api-effects-test
  (is (= [[:effects/save [:vaults-ui :list-loading?] true]
          [:effects/api-fetch-vault-index]
          [:effects/api-fetch-vault-summaries]
          [:effects/api-fetch-user-vault-equities "0x1234567890abcdef1234567890abcdef12345678"]]
         (actions/load-vaults
          {:wallet {:address "0x1234567890abcdef1234567890abcdef12345678"}})))
  (is (= [[:effects/save [:vaults-ui :detail-loading?] true]
          [:effects/api-fetch-vault-details "0x1234567890abcdef1234567890abcdef12345678" "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"]
          [:effects/api-fetch-vault-webdata2 "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-fills "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-funding-history "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-order-history "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-ledger-updates "0x1234567890abcdef1234567890abcdef12345678"]]
         (actions/load-vault-detail
          {:wallet {:address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}}
          "0x1234567890ABCDEF1234567890ABCDEF12345678")))
  (is (= []
         (actions/load-vault-detail {} "not-a-vault")))
  (is (= [[:effects/save [:vaults-ui :list-loading?] true]
          [:effects/save [:vaults-ui :detail-loading?] true]
          [:effects/api-fetch-vault-index]
          [:effects/api-fetch-vault-summaries]
          [:effects/api-fetch-user-vault-equities "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"]
          [:effects/api-fetch-vault-details "0x1234567890abcdef1234567890abcdef12345678" "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"]
          [:effects/api-fetch-vault-webdata2 "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-fills "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-funding-history "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-order-history "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-ledger-updates "0x1234567890abcdef1234567890abcdef12345678"]]
         (actions/load-vault-route
          {:wallet {:address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}}
          "/vaults/0x1234567890abcdef1234567890abcdef12345678"))))

(deftest vault-ui-actions-normalize-input-and-toggle-states-test
  (is (= [[:effects/save-many [[[:vaults-ui :search-query] "vault"]
                               [[:vaults-ui :user-vaults-page] 1]]]]
         (actions/set-vaults-search-query {} "vault")))
  (is (= [[:effects/save-many [[[:vaults-ui :search-query] "42"]
                               [[:vaults-ui :user-vaults-page] 1]]]]
         (actions/set-vaults-search-query {} 42)))
  (is (= [[:effects/save-many [[[:vaults-ui :filter-leading?] false]
                               [[:vaults-ui :user-vaults-page] 1]]]]
         (actions/toggle-vaults-filter {:vaults-ui {:filter-leading? true}} :leading)))
  (is (= []
         (actions/toggle-vaults-filter {:vaults-ui {:filter-leading? true}} :unknown)))
  (is (= [[:effects/save-many [[[:vaults-ui :snapshot-range] :all-time]
                               [[:vaults-ui :user-vaults-page] 1]]]]
         (actions/set-vaults-snapshot-range {} "allTime")))
  (is (= [[:effects/save-many [[[:vaults-ui :sort] {:column :tvl
                                                    :direction :asc}]
                               [[:vaults-ui :user-vaults-page] 1]]]]
         (actions/set-vaults-sort {:vaults-ui {:sort {:column :tvl
                                                      :direction :desc}}}
                                  :tvl)))
  (is (= [[:effects/save-many [[[:vaults-ui :sort] {:column :apr
                                                    :direction :desc}]
                               [[:vaults-ui :user-vaults-page] 1]]]]
         (actions/set-vaults-sort {:vaults-ui {:sort {:column :tvl
                                                      :direction :desc}}}
                                  "apr")))
  (is (= [[:effects/save-many [[[:vaults-ui :user-vaults-page-size] 25]
                               [[:vaults-ui :user-vaults-page] 1]
                               [[:vaults-ui :user-vaults-page-size-dropdown-open?] false]]]]
         (actions/set-vaults-user-page-size {} "25")))
  (is (= [[:effects/save-many [[[:vaults-ui :user-vaults-page-size] 10]
                               [[:vaults-ui :user-vaults-page] 1]
                               [[:vaults-ui :user-vaults-page-size-dropdown-open?] false]]]]
         (actions/set-vaults-user-page-size {} "999")))
  (is (= [[:effects/save [:vaults-ui :user-vaults-page-size-dropdown-open?] true]]
         (actions/toggle-vaults-user-page-size-dropdown
          {:vaults-ui {:user-vaults-page-size-dropdown-open? false}})))
  (is (= [[:effects/save [:vaults-ui :user-vaults-page-size-dropdown-open?] false]]
         (actions/close-vaults-user-page-size-dropdown {})))
  (is (= [[:effects/save [:vaults-ui :user-vaults-page] 2]]
         (actions/set-vaults-user-page {} "3" 2)))
  (is (= [[:effects/save [:vaults-ui :user-vaults-page] 4]]
         (actions/next-vaults-user-page {:vaults-ui {:user-vaults-page 3}} 5)))
  (is (= [[:effects/save [:vaults-ui :user-vaults-page] 1]]
         (actions/prev-vaults-user-page {:vaults-ui {:user-vaults-page 2}} 5)))
  (is (= [[:effects/save [:vaults-ui :detail-tab] :vault-performance]]
         (actions/set-vault-detail-tab {} "vaultPerformance")))
  (is (= [[:effects/save [:vaults-ui :detail-activity-tab] :open-orders]]
         (actions/set-vault-detail-activity-tab {} "openOrders")))
  (is (= [[:effects/save [:vaults-ui :detail-activity-tab] :positions]]
         (actions/set-vault-detail-activity-tab {} "unknown-tab")))
  (is (= [[:effects/save [:vaults-ui :detail-chart-series] :account-value]]
         (actions/set-vault-detail-chart-series {} "accountValue")))
  (is (= [[:effects/save [:vaults-ui :detail-chart-series] :pnl]]
         (actions/set-vault-detail-chart-series {} "unknown-series"))))
