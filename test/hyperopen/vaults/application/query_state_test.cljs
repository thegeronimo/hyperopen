(ns hyperopen.vaults.application.query-state-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.vaults.application.query-state :as query-state]))

(deftest parse-vault-list-query-normalizes-shareable-list-state-test
  (is (= {:snapshot-range :month
          :search-query "lp vault"
          :roles #{:deposited :others}
          :filter-closed? true
          :sort {:column :apr
                 :direction :asc}
          :user-vaults-page 3
          :user-vaults-page-size 25}
         (query-state/parse-vault-list-query
          "?range=30d&q=lp+vault&roles=deposited,others&closed=1&sort=apr:asc&page=3&pageSize=25"))))

(deftest parse-vault-list-query-supports-empty-role-selection-test
  (is (= {:roles #{}}
         (query-state/parse-vault-list-query "?roles=")))
  (is (= {}
         (query-state/parse-vault-list-query ""))))

(deftest parse-vault-detail-query-normalizes-shareable-detail-state-test
  (is (= {:snapshot-range :six-month
          :detail-chart-series :returns
          :detail-returns-benchmark-coins ["BTC" "ETH"]
          :detail-tab :vault-performance
          :detail-activity-tab :trade-history
          :detail-activity-direction-filter :long}
         (query-state/parse-vault-detail-query
          "?range=6m&chart=returns&bench=BTC&bench=ETH&bench=BTC&tab=vaultPerformance&activity=tradeHistory&side=long"))))

(deftest parse-vault-detail-query-supports-cleared-benchmarks-test
  (is (= {:detail-returns-benchmark-coins []}
         (query-state/parse-vault-detail-query "?bench="))))

(deftest apply-vault-query-state-merges-list-and-detail-fields-test
  (is (= {:vaults-ui {:snapshot-range :week
                      :search-query "dao"
                      :filter-leading? true
                      :filter-deposited? false
                      :filter-others? true
                      :filter-closed? true
                      :sort {:column :age :direction :desc}
                      :user-vaults-page 2
                      :user-vaults-page-size 50
                      :detail-chart-series :pnl
                      :detail-returns-benchmark-coins ["ETH"]
                      :detail-returns-benchmark-coin "ETH"
                      :detail-tab :your-performance
                      :detail-activity-tab :positions
                      :detail-activity-direction-filter :short
                      :detail-returns-benchmark-search "draft"}}
         (query-state/apply-vault-query-state
          {:vaults-ui {:detail-returns-benchmark-search "draft"}}
          {:snapshot-range :week
           :search-query "dao"
           :roles #{:leading :others}
           :filter-closed? true
           :sort {:column :age :direction :desc}
           :user-vaults-page 2
           :user-vaults-page-size 50
           :detail-chart-series :pnl
           :detail-returns-benchmark-coins ["ETH"]
           :detail-tab :your-performance
           :detail-activity-tab :positions
           :detail-activity-direction-filter :short}))))

(deftest vault-list-query-params-serializes-deterministic-shareable-snapshot-test
  (is (= [["range" "7d"]
          ["q" "lp"]
          ["roles" "leading,others"]
          ["closed" "1"]
          ["sort" "your-deposit:asc"]
          ["page" "4"]
          ["pageSize" "50"]]
         (query-state/vault-list-query-params
          {:vaults-ui {:snapshot-range :week
                       :search-query "lp"
                       :filter-leading? true
                       :filter-deposited? false
                       :filter-others? true
                       :filter-closed? true
                       :sort {:column :your-deposit
                              :direction :asc}
                       :user-vaults-page 4
                       :user-vaults-page-size 50}}))))

(deftest vault-detail-query-params-serializes-deterministic-shareable-snapshot-test
  (is (= [["range" "2y"]
          ["chart" "account-value"]
          ["bench" "BTC"]
          ["bench" "vault:0x1234567890abcdef1234567890abcdef12345678"]
          ["tab" "about"]
          ["activity" "funding-history"]
          ["side" "short"]]
         (query-state/vault-detail-query-params
          {:vaults-ui {:snapshot-range :two-year
                       :detail-chart-series :account-value
                       :detail-returns-benchmark-coins ["BTC"
                                                        "vault:0x1234567890abcdef1234567890abcdef12345678"
                                                        "BTC"]
                       :detail-tab :about
                       :detail-activity-tab :funding-history
                       :detail-activity-direction-filter :short}}))))

(deftest vault-detail-query-params-serializes-cleared-benchmarks-test
  (is (= [["range" "3m"]
          ["chart" "returns"]
          ["bench" ""]
          ["tab" "vault-performance"]
          ["activity" "performance-metrics"]
          ["side" "all"]]
         (query-state/vault-detail-query-params
          {:vaults-ui {:snapshot-range :three-month
                       :detail-chart-series :returns
                       :detail-returns-benchmark-coins []
                       :detail-returns-benchmark-coin nil
                       :detail-tab :vault-performance
                       :detail-activity-tab :performance-metrics
                       :detail-activity-direction-filter :all}}))))
