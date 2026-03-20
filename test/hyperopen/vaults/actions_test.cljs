(ns hyperopen.vaults.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.platform :as platform]
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
          [:effects/save [:vaults-ui :detail-chart-hover-index] nil]
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
  (is (= [[:effects/save [:vaults-ui :detail-loading?] true]
          [:effects/save [:vaults-ui :detail-chart-hover-index] nil]
          [:effects/api-fetch-user-vault-equities "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"]
          [:effects/api-fetch-vault-details "0x1234567890abcdef1234567890abcdef12345678" "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"]
          [:effects/api-fetch-vault-webdata2 "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-fills "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-funding-history "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-order-history "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-ledger-updates "0x1234567890abcdef1234567890abcdef12345678"]]
         (actions/load-vault-route
          {:wallet {:address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}}
          "/vaults/0x1234567890abcdef1234567890abcdef12345678")))
  (is (= []
         (actions/load-vault-route
          {:wallet {:address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}}
          "/portfolio")))
  (is (= []
         (actions/load-vault-route
          {:wallet {:address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}}
          "/trade"))))

(deftest load-vault-detail-fetches-returns-benchmark-candles-on-initial-load-test
  (is (= [[:effects/save [:vaults-ui :detail-loading?] true]
          [:effects/save [:vaults-ui :detail-chart-hover-index] nil]
          [:effects/api-fetch-vault-details "0x1234567890abcdef1234567890abcdef12345678" "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"]
          [:effects/api-fetch-vault-webdata2 "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-fills "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-funding-history "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-order-history "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-ledger-updates "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/fetch-candle-snapshot :coin "BTC" :interval :1h :bars 800]]
         (actions/load-vault-detail
          {:wallet {:address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
           :vaults-ui {:snapshot-range :month
                       :detail-returns-benchmark-coins ["BTC"
                                                        "vault:0x1234567890abcdef1234567890abcdef12345678"]
                       :detail-returns-benchmark-coin "BTC"}}
          "0x1234567890ABCDEF1234567890ABCDEF12345678"))))

(deftest load-vault-detail-fetches-component-history-for-parent-vaults-test
  (let [state {:wallet {:address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
               :vaults {:merged-index-rows [{:vault-address "0x1234567890abcdef1234567890abcdef12345678"
                                             :relationship {:type :parent
                                                            :child-addresses ["0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                                                             "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                                                                             "0x1234567890abcdef1234567890abcdef12345678"
                                                                             "not-an-address"]}}]}}]
    (is (= [[:effects/save [:vaults-ui :detail-loading?] true]
            [:effects/save [:vaults-ui :detail-chart-hover-index] nil]
            [:effects/api-fetch-vault-details "0x1234567890abcdef1234567890abcdef12345678" "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"]
            [:effects/api-fetch-vault-webdata2 "0x1234567890abcdef1234567890abcdef12345678"]
            [:effects/api-fetch-vault-fills "0x1234567890abcdef1234567890abcdef12345678"]
            [:effects/api-fetch-vault-funding-history "0x1234567890abcdef1234567890abcdef12345678"]
            [:effects/api-fetch-vault-order-history "0x1234567890abcdef1234567890abcdef12345678"]
            [:effects/api-fetch-vault-ledger-updates "0x1234567890abcdef1234567890abcdef12345678"]
            [:effects/api-fetch-vault-fills "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
            [:effects/api-fetch-vault-funding-history "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
            [:effects/api-fetch-vault-order-history "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
            [:effects/api-fetch-vault-fills "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]
            [:effects/api-fetch-vault-funding-history "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]
            [:effects/api-fetch-vault-order-history "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]]
           (actions/load-vault-detail state "0x1234567890abcdef1234567890abcdef12345678")))))

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
                               [[:vaults-ui :user-vaults-page] 1]
                               [[:vaults-ui :detail-chart-hover-index] nil]
                               [[:vaults-ui :detail-chart-timeframe-dropdown-open?] false]
                               [[:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?] false]]]
          [:effects/local-storage-set "vaults-snapshot-range" "all-time"]]
         (actions/set-vaults-snapshot-range {} "allTime")))
  (is (= [[:effects/save-many [[[:vaults-ui :snapshot-range] :three-month]
                               [[:vaults-ui :user-vaults-page] 1]
                               [[:vaults-ui :detail-chart-hover-index] nil]
                               [[:vaults-ui :detail-chart-timeframe-dropdown-open?] false]
                               [[:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?] false]]]
          [:effects/local-storage-set "vaults-snapshot-range" "three-month"]]
         (actions/set-vaults-snapshot-range {} "3m")))
  (is (= [[:effects/save-many [[[:vaults-ui :snapshot-range] :week]
                               [[:vaults-ui :user-vaults-page] 1]
                               [[:vaults-ui :detail-chart-hover-index] nil]
                               [[:vaults-ui :detail-chart-timeframe-dropdown-open?] false]
                               [[:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?] false]]]
          [:effects/local-storage-set "vaults-snapshot-range" "week"]
          [:effects/fetch-candle-snapshot :coin "BTC" :interval :15m :bars 800]
          [:effects/fetch-candle-snapshot :coin "ETH" :interval :15m :bars 800]]
         (actions/set-vaults-snapshot-range {:vaults-ui {:detail-chart-series :returns
                                                         :detail-returns-benchmark-coins ["BTC"
                                                                                          "vault:0x1234567890abcdef1234567890abcdef12345678"
                                                                                          "ETH"]}
                                             :router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}}
                                            :week)))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-chart-timeframe-dropdown-open?] true]
                               [[:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?] false]]]]
         (actions/toggle-vault-detail-chart-timeframe-dropdown
          {:vaults-ui {:detail-chart-timeframe-dropdown-open? false
                       :detail-performance-metrics-timeframe-dropdown-open? true}})))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-chart-timeframe-dropdown-open?] false]
                               [[:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?] false]]]]
         (actions/close-vault-detail-chart-timeframe-dropdown {})))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-chart-timeframe-dropdown-open?] false]
                               [[:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?] true]]]]
         (actions/toggle-vault-detail-performance-metrics-timeframe-dropdown
          {:vaults-ui {:detail-chart-timeframe-dropdown-open? true
                       :detail-performance-metrics-timeframe-dropdown-open? false}})))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-chart-timeframe-dropdown-open?] false]
                               [[:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?] false]]]]
         (actions/close-vault-detail-performance-metrics-timeframe-dropdown {})))
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
  (is (= [[:effects/save [:vaults-ui :detail-tab] :about]]
         (actions/set-vault-detail-tab {} "performanceMetrics")))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-activity-tab] :performance-metrics]
                               [[:vaults-ui :detail-activity-filter-open?] false]]]]
         (actions/set-vault-detail-activity-tab {} "performanceMetrics")))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-activity-tab] :open-orders]
                               [[:vaults-ui :detail-activity-filter-open?] false]]]]
         (actions/set-vault-detail-activity-tab {} "openOrders")))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-activity-tab] :performance-metrics]
                               [[:vaults-ui :detail-activity-filter-open?] false]]]]
         (actions/set-vault-detail-activity-tab {} "unknown-tab")))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-activity-sort-by-tab :positions]
                                {:column :size
                                 :direction :desc}]
                               [[:vaults-ui :detail-activity-filter-open?] false]]]]
         (actions/sort-vault-detail-activity {} :positions "Size")))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-activity-sort-by-tab :positions]
                                {:column :size
                                 :direction :asc}]
                               [[:vaults-ui :detail-activity-filter-open?] false]]]]
         (actions/sort-vault-detail-activity
          {:vaults-ui {:detail-activity-sort-by-tab {:positions {:column :size
                                                                  :direction :desc}}}}
          :positions
          "Size")))
  (is (= [[:effects/save [:vaults-ui :detail-activity-filter-open?] true]]
         (actions/toggle-vault-detail-activity-filter-open
          {:vaults-ui {:detail-activity-filter-open? false}})))
  (is (= [[:effects/save [:vaults-ui :detail-activity-filter-open?] false]]
         (actions/close-vault-detail-activity-filter {})))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-activity-direction-filter] :short]
                               [[:vaults-ui :detail-activity-filter-open?] false]]]]
         (actions/set-vault-detail-activity-direction-filter {} "short")))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-activity-direction-filter] :all]
                               [[:vaults-ui :detail-activity-filter-open?] false]]]]
         (actions/set-vault-detail-activity-direction-filter {} "not-real")))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-chart-series] :account-value]
                               [[:vaults-ui :detail-chart-hover-index] nil]]]]
         (actions/set-vault-detail-chart-series {} "accountValue")))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-chart-series] :returns]
                               [[:vaults-ui :detail-chart-hover-index] nil]]]
          [:effects/fetch-candle-snapshot :coin "BTC" :interval :1h :bars 800]]
         (actions/set-vault-detail-chart-series {:vaults-ui {:snapshot-range :month
                                                             :detail-returns-benchmark-coins ["BTC"
                                                                                              "vault:0x1234567890abcdef1234567890abcdef12345678"]}}
                                                :returns)))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-chart-series] :returns]
                               [[:vaults-ui :detail-chart-hover-index] nil]]]]
         (actions/set-vault-detail-chart-series {} "unknown-series")))
  (is (= [[:effects/save [:vaults-ui :detail-returns-benchmark-search] "42"]]
         (actions/set-vault-detail-returns-benchmark-search {} 42)))
  (is (= [[:effects/save [:vaults-ui :detail-returns-benchmark-suggestions-open?] true]]
         (actions/set-vault-detail-returns-benchmark-suggestions-open
          {:vaults {:merged-index-rows [{:vault-address "0xabc"}]}}
          true)))
  (is (= [[:effects/save [:vaults-ui :detail-returns-benchmark-suggestions-open?] true]
          [:effects/api-fetch-vault-index]
          [:effects/api-fetch-vault-summaries]]
         (actions/set-vault-detail-returns-benchmark-suggestions-open
          {:vaults {}}
          true)))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-returns-benchmark-coins] ["ETH"]]
                               [[:vaults-ui :detail-returns-benchmark-coin] "ETH"]
                               [[:vaults-ui :detail-returns-benchmark-search] ""]
                               [[:vaults-ui :detail-returns-benchmark-suggestions-open?] true]]]
          [:effects/fetch-candle-snapshot :coin "ETH" :interval :1h :bars 800]]
         (actions/select-vault-detail-returns-benchmark {:vaults-ui {:snapshot-range :month
                                                                     :detail-chart-series :returns
                                                                     :detail-returns-benchmark-coins []}
                                                         :router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}}
                                                        "ETH")))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-returns-benchmark-coins] ["vault:0x1234567890abcdef1234567890abcdef12345678"]]
                               [[:vaults-ui :detail-returns-benchmark-coin] "vault:0x1234567890abcdef1234567890abcdef12345678"]
                               [[:vaults-ui :detail-returns-benchmark-search] ""]
                               [[:vaults-ui :detail-returns-benchmark-suggestions-open?] true]]]
          [:effects/api-fetch-vault-benchmark-details "0x1234567890abcdef1234567890abcdef12345678"]]
         (actions/select-vault-detail-returns-benchmark {:vaults-ui {:snapshot-range :month
                                                                     :detail-chart-series :returns
                                                                     :detail-returns-benchmark-coins []}
                                                         :router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}}
                                                        "vault:0x1234567890abcdef1234567890abcdef12345678")))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-returns-benchmark-coins] ["BTC"]]
                               [[:vaults-ui :detail-returns-benchmark-coin] "BTC"]]]]
         (actions/remove-vault-detail-returns-benchmark {:vaults-ui {:detail-returns-benchmark-coins ["BTC" "ETH"]}}
                                                        "ETH")))
  (is (= [[:effects/save [:vaults-ui :detail-returns-benchmark-suggestions-open?] false]]
         (actions/handle-vault-detail-returns-benchmark-search-keydown {} "Escape" nil)))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-returns-benchmark-coins] []]
                               [[:vaults-ui :detail-returns-benchmark-coin] nil]
                               [[:vaults-ui :detail-returns-benchmark-search] ""]
                               [[:vaults-ui :detail-returns-benchmark-suggestions-open?] false]]]]
         (actions/clear-vault-detail-returns-benchmark {}))))

(deftest restore-vaults-snapshot-range-loads-normalized-local-storage-preference-test
  (let [store (atom {:vaults-ui {:snapshot-range :month}})]
    (with-redefs [platform/local-storage-get (fn [_] "allTime")]
      (actions/restore-vaults-snapshot-range! store))
    (is (= :all-time (get-in @store [:vaults-ui :snapshot-range]))))
  (let [store (atom {:vaults-ui {:snapshot-range :all-time}})]
    (with-redefs [platform/local-storage-get (fn [_] "not-a-range")]
      (actions/restore-vaults-snapshot-range! store))
    (is (= :month (get-in @store [:vaults-ui :snapshot-range])))))

(deftest vault-transfer-actions-open-update-and-submit-test
  (let [vault-address "0x1234567890abcdef1234567890abcdef12345678"
        leader-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        base-state {:router {:path (str "/vaults/" vault-address)}
                    :wallet {:address leader-address}
                    :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                                 :leader leader-address
                                                                 :allow-deposits? true}}
                             :merged-index-rows [{:vault-address vault-address
                                                  :name "Vault Detail"
                                                  :leader leader-address}]}}]
    (is (= [[:effects/save
             [:vaults-ui :vault-transfer-modal]
             {:open? true
              :mode :deposit
              :vault-address vault-address
              :amount-input ""
              :withdraw-all? false
              :submitting? false
              :error nil}]]
           (actions/open-vault-transfer-modal base-state vault-address :deposit)))
    (is (= [[:effects/save [:vaults-ui :vault-transfer-modal]
             {:open? false
              :mode :deposit
              :vault-address nil
              :amount-input ""
              :withdraw-all? false
              :submitting? false
              :error nil}]]
           (actions/close-vault-transfer-modal base-state)))
    (is (= [[:effects/save [:vaults-ui :vault-transfer-modal]
             {:open? true
              :mode :withdraw
              :vault-address vault-address
              :amount-input "12.3456"
              :withdraw-all? false
              :submitting? false
              :error nil}]]
           (actions/set-vault-transfer-amount
            {:vaults-ui {:vault-transfer-modal {:open? true
                                                :mode :withdraw
                                                :vault-address vault-address
                                                :amount-input ""
                                                :withdraw-all? true
                                                :submitting? false
                                                :error nil}}}
            "12.3456")))
    (is (= [[:effects/save [:vaults-ui :vault-transfer-modal]
             {:open? true
              :mode :withdraw
              :vault-address vault-address
              :amount-input ""
              :withdraw-all? true
              :submitting? false
              :error nil}]]
           (actions/set-vault-transfer-withdraw-all
            {:vaults-ui {:vault-transfer-modal {:open? true
                                                :mode :withdraw
                                                :vault-address vault-address
                                                :amount-input "10"
                                                :withdraw-all? false
                                                :submitting? false
                                                :error nil}}}
            true)))
    (is (= [[:effects/save-many [[[:vaults-ui :vault-transfer-modal :submitting?] true]
                                 [[:vaults-ui :vault-transfer-modal :error] nil]]]
            [:effects/api-submit-vault-transfer
             {:vault-address vault-address
              :action {:type "vaultTransfer"
                       :vaultAddress vault-address
                       :isDeposit true
                       :usd 2500000}}]]
           (actions/submit-vault-transfer
            (assoc-in base-state
                      [:vaults-ui :vault-transfer-modal]
                      {:open? true
                       :mode :deposit
                       :vault-address vault-address
                       :amount-input "2.5"
                       :withdraw-all? false
                       :submitting? false
                       :error nil}))))
    (is (= [[:effects/save-many [[[:vaults-ui :vault-transfer-modal :submitting?] true]
                                 [[:vaults-ui :vault-transfer-modal :error] nil]]]
            [:effects/api-submit-vault-transfer
             {:vault-address vault-address
              :action {:type "vaultTransfer"
                       :vaultAddress vault-address
                       :isDeposit false
                       :usd 0}}]]
           (actions/submit-vault-transfer
            (assoc-in base-state
                      [:vaults-ui :vault-transfer-modal]
                      {:open? true
                       :mode :withdraw
                       :vault-address vault-address
                       :amount-input ""
                       :withdraw-all? true
                       :submitting? false
                       :error nil}))))
    (is (= [[:effects/save-many [[[:vaults-ui :vault-transfer-modal :submitting?] false]
                                 [[:vaults-ui :vault-transfer-modal :error] "Enter an amount greater than 0."]]]]
           (actions/submit-vault-transfer
            (assoc-in base-state
                      [:vaults-ui :vault-transfer-modal]
                      {:open? true
                       :mode :withdraw
                       :vault-address vault-address
                       :amount-input ""
                       :withdraw-all? false
                       :submitting? false
                       :error nil}))))))

(deftest vault-transfer-preview-rejects-when-deposit-gating-disables-vault-test
  (let [vault-address "0x1234567890abcdef1234567890abcdef12345678"
        state {:router {:path (str "/vaults/" vault-address)}
               :wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
               :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                            :leader "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                                                            :allow-deposits? false}}
                        :merged-index-rows [{:vault-address vault-address
                                             :name "Vault Detail"
                                             :leader "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}]}}
        result (actions/vault-transfer-preview state
                                               {:open? true
                                                :mode :deposit
                                                :vault-address vault-address
                                                :amount-input "1"
                                                :withdraw-all? false})]
    (is (false? (:ok? result)))
    (is (= "Deposits are disabled for this vault."
           (:display-message result)))))

(deftest vault-transfer-submit-blocks-mutations-while-spectate-mode-active-test
  (let [vault-address "0x1234567890abcdef1234567890abcdef12345678"
        leader-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        state {:router {:path (str "/vaults/" vault-address)}
               :wallet {:address leader-address}
               :account-context {:spectate-mode {:active? true
                                              :address "0x1234567890abcdef1234567890abcdef12345678"}}
               :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                            :leader leader-address
                                                            :allow-deposits? true}}
                        :merged-index-rows [{:vault-address vault-address
                                             :name "Vault Detail"
                                             :leader leader-address}]}
               :vaults-ui {:vault-transfer-modal {:open? true
                                                  :mode :deposit
                                                  :vault-address vault-address
                                                  :amount-input "2.5"
                                                  :withdraw-all? false
                                                  :submitting? false
                                                  :error nil}}}]
    (is (= [[:effects/save-many [[[:vaults-ui :vault-transfer-modal :submitting?] false]
                                 [[:vaults-ui :vault-transfer-modal :error]
                                  account-context/spectate-mode-read-only-message]]]]
           (actions/submit-vault-transfer state)))))

(deftest vault-transfer-preview-parses-localized-amount-input-test
  (let [vault-address "0x1234567890abcdef1234567890abcdef12345678"
        leader-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        state {:router {:path (str "/vaults/" vault-address)}
               :ui {:locale "fr-FR"}
               :wallet {:address leader-address}
               :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                            :leader leader-address
                                                            :allow-deposits? true}}
                        :merged-index-rows [{:vault-address vault-address
                                             :name "Vault Detail"
                                             :leader leader-address}]}}
        result (actions/vault-transfer-preview state
                                               {:open? true
                                                :mode :deposit
                                                :vault-address vault-address
                                                :amount-input "2,5"
                                                :withdraw-all? false})]
    (is (true? (:ok? result)))
    (is (= 2500000 (get-in result [:request :action :usd])))))

(deftest set-and-clear-vault-detail-chart-hover-test
  (is (= [[:effects/save [:vaults-ui :detail-chart-hover-index] 2]]
         (actions/set-vault-detail-chart-hover
          {}
          140
          {:left 100
           :width 80}
          5)))
  (is (= [[:effects/save [:vaults-ui :detail-chart-hover-index] 4]]
         (actions/set-vault-detail-chart-hover
          {}
          1000
          {:left 100
           :width 80}
          5)))
  (is (= []
         (actions/set-vault-detail-chart-hover
          {:vaults-ui {:detail-chart-hover-index 4}}
          1000
          {:left 100
           :width 80}
          5)))
  (is (= []
         (actions/set-vault-detail-chart-hover
          {:vaults-ui {:detail-chart-hover-index 2}}
          nil
          {:left 100
           :width 80}
          5)))
  (is (= [[:effects/save [:vaults-ui :detail-chart-hover-index] 0]]
         (actions/set-vault-detail-chart-hover
          {:vaults-ui {:detail-chart-hover-index nil}}
          nil
          nil
          5)))
  (is (= []
         (actions/set-vault-detail-chart-hover
          {:vaults-ui {:detail-chart-hover-index 2}}
          nil
          {:left 100
           :width 0}
          5)))
  (is (= [[:effects/save [:vaults-ui :detail-chart-hover-index] nil]]
         (actions/clear-vault-detail-chart-hover
          {:vaults-ui {:detail-chart-hover-index 1}})))
  (is (= []
         (actions/clear-vault-detail-chart-hover
          {:vaults-ui {:detail-chart-hover-index nil}}))))
