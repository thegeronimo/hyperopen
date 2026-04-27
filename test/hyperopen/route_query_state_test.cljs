(ns hyperopen.route-query-state-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.route-query-state :as route-query-state]))

(deftest shareable-route-browser-path-preserves-unrelated-query-and-replaces-portfolio-state-test
  (is (= "/portfolio?spectate=0xabcdefabcdefabcdefabcdefabcdefabcdefabcd&foo=bar&range=3m&scope=all&chart=returns&bench=BTC&bench=ETH&tab=positions"
         (route-query-state/shareable-route-browser-path
          {:portfolio-ui {:summary-time-range :three-month
                          :summary-scope :all
                          :chart-tab :returns
                          :returns-benchmark-coins ["BTC" "ETH"]
                          :account-info-tab :positions}}
          "/portfolio"
          "?spectate=0xabcdefabcdefabcdefabcdefabcdefabcdefabcd&range=24h&bench=SOL&chart=pnl&foo=bar"))))

(deftest shareable-route-browser-path-removes-stale-route-owned-params-for-active-surface-test
  (is (= "/vaults?spectate=0xabcdefabcdefabcdefabcdefabcdefabcdefabcd&range=30d&roles=deposited&sort=tvl%3Adesc&page=2&pageSize=25"
         (route-query-state/shareable-route-browser-path
          {:vaults-ui {:snapshot-range :month
                       :search-query ""
                       :filter-leading? false
                       :filter-deposited? true
                       :filter-others? false
                       :filter-closed? false
                       :sort {:column :tvl
                              :direction :desc}
                       :user-vaults-page 2
                       :user-vaults-page-size 25}}
          "/vaults"
          "?scope=perps&chart=pnl&bench=ETH&tab=positions&activity=trade-history&side=long&spectate=0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"))))

(deftest shareable-route-browser-path-serializes-vault-detail-state-test
  (is (= "/vaults/0x1234567890abcdef1234567890abcdef12345678?foo=bar&range=6m&chart=returns&bench=BTC&bench=vault%3A0xabcdefabcdefabcdefabcdefabcdefabcdefabcd&tab=vault-performance&activity=trade-history&side=long"
         (route-query-state/shareable-route-browser-path
          {:vaults-ui {:snapshot-range :six-month
                       :detail-chart-series :returns
                       :detail-returns-benchmark-coins ["BTC"
                                                        "vault:0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"]
                       :detail-tab :vault-performance
                       :detail-activity-tab :trade-history
                       :detail-activity-direction-filter :long}}
          "/vaults/0x1234567890abcdef1234567890abcdef12345678"
          "?foo=bar&range=30d&page=3"))))

(deftest shareable-route-browser-path-distinguishes-cleared-benchmarks-test
  (is (= "/portfolio?range=3m&scope=all&chart=returns&bench=&tab=positions"
         (route-query-state/shareable-route-browser-path
          {:portfolio-ui {:summary-time-range :three-month
                          :summary-scope :all
                          :chart-tab :returns
                          :returns-benchmark-coins []
                          :returns-benchmark-coin nil
                          :account-info-tab :positions}}
          "/portfolio"
          ""))))

(deftest shareable-route-browser-path-restores-optimizer-state-on-optimizer-routes-test
  (is (= "/portfolio/optimize/scn_01?foo=bar&ofilter=executed&osort=updated-asc&oview=tracking&otab=recommendation&odiag=sensitivity"
         (route-query-state/shareable-route-browser-path
          {:portfolio-ui {:optimizer {:list-filter :executed
                                      :list-sort :updated-asc
                                      :workspace-panel :tracking
                                      :results-tab :recommendation
                                      :diagnostics-tab :sensitivity}}}
          "/portfolio/optimize/scn_01"
          "?range=24h&bench=SOL&tab=positions&foo=bar&ofilter=saved&odiag=data"))))

(deftest shareable-route-browser-path-ignores-non-shareable-routes-test
  (is (nil? (route-query-state/shareable-route-browser-path
             {}
             "/trade"
             "?market=ETH"))))

(deftest apply-route-query-state-restores-portfolio-before-local-preferences-win-test
  (is (= {:portfolio-ui {:summary-time-range :three-month
                         :summary-scope :perps
                         :chart-tab :pnl
                         :returns-benchmark-coins ["BTC" "ETH"]
                         :returns-benchmark-coin "BTC"
                         :account-info-tab :positions}}
         (route-query-state/apply-route-query-state
          {:portfolio-ui {:summary-time-range :one-year
                          :summary-scope :all
                          :chart-tab :returns
                          :returns-benchmark-coins ["SOL"]
                          :returns-benchmark-coin "SOL"
                          :account-info-tab :balances}}
          "/portfolio"
          "?range=3m&scope=perps&chart=pnl&bench=BTC&bench=ETH&tab=positions"))))

(deftest apply-route-query-state-restores-optimizer-state-for-optimizer-routes-test
  (is (= {:portfolio-ui {:optimizer {:list-filter :saved
                                      :list-sort :name-desc
                                      :workspace-panel :results
                                      :results-tab :recommendation
                                      :diagnostics-tab :constraints}}}
         (route-query-state/apply-route-query-state
          {:portfolio-ui {:optimizer {:list-filter :active
                                      :list-sort :updated-desc
                                      :workspace-panel :setup
                                      :results-tab :recommendation
                                      :diagnostics-tab :conditioning}}}
          "/portfolio/optimize"
          "?ofilter=saved&osort=name-desc&oview=results&otab=frontier&odiag=constraints"))))

(deftest apply-route-query-state-defaults-bare-scenario-routes-to-recommendation-test
  (is (= :recommendation
         (get-in (route-query-state/apply-route-query-state
                  {:portfolio-ui {:optimizer {:results-tab :tracking
                                              :list-filter :active
                                              :list-sort :updated-desc
                                              :workspace-panel :setup
                                              :diagnostics-tab :conditioning}}}
                  "/portfolio/optimize/scn_02"
                  "")
                 [:portfolio-ui :optimizer :results-tab]))))

(deftest apply-route-query-state-restores-vault-list-and-detail-by-route-kind-test
  (is (= {:vaults-ui {:snapshot-range :week
                      :search-query "lp"
                      :filter-leading? true
                      :filter-deposited? false
                      :filter-others? true
                      :filter-closed? true
                      :sort {:column :apr :direction :asc}
                      :user-vaults-page 5
                      :user-vaults-page-size 50}}
         (route-query-state/apply-route-query-state
          {:vaults-ui {}}
          "/vaults"
          "?range=7d&q=lp&roles=leading,others&closed=1&sort=apr:asc&page=5&pageSize=50")))
  (is (= {:vaults-ui {:snapshot-range :all-time
                      :detail-chart-series :account-value
                      :detail-returns-benchmark-coins ["ETH"]
                      :detail-returns-benchmark-coin "ETH"
                      :detail-tab :your-performance
                      :detail-activity-tab :funding-history
                      :detail-activity-direction-filter :short}}
         (route-query-state/apply-route-query-state
          {:vaults-ui {}}
          "/vaults/0x1234567890abcdef1234567890abcdef12345678"
          "?range=all&chart=account-value&bench=ETH&tab=your-performance&activity=funding-history&side=short"))))
