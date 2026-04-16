(ns hyperopen.portfolio.query-state-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.query-state :as query-state]))

(deftest parse-portfolio-query-normalizes-route-params-test
  (is (= {:summary-time-range :three-month
          :summary-scope :perps
          :chart-tab :pnl
          :returns-benchmark-coins ["BTC" "ETH" "vault:0x1234567890abcdef1234567890abcdef12345678"]
          :account-info-tab :positions}
         (query-state/parse-portfolio-query
          "?range=3m&scope=perp&chart=pnl&bench=BTC&bench=ETH&bench=BTC&bench=vault%3A0x1234567890abcdef1234567890abcdef12345678&tab=positions"))))

(deftest parse-portfolio-query-supports-cleared-benchmarks-test
  (is (= {:returns-benchmark-coins []}
         (query-state/parse-portfolio-query "?bench="))))

(deftest apply-portfolio-query-state-merges-only-recognized-view-state-test
  (is (= {:portfolio-ui {:summary-time-range :six-month
                         :summary-scope :all
                         :chart-tab :returns
                         :returns-benchmark-coins ["BTC" "ETH"]
                         :returns-benchmark-coin "BTC"
                         :account-info-tab :trade-history
                         :returns-benchmark-search "draft"}}
         (query-state/apply-portfolio-query-state
          {:portfolio-ui {:summary-time-range :one-year
                          :summary-scope :perps
                          :chart-tab :pnl
                          :returns-benchmark-coins ["SOL"]
                          :returns-benchmark-coin "SOL"
                          :account-info-tab :balances
                          :returns-benchmark-search "draft"}}
          {:summary-time-range :six-month
           :summary-scope :all
           :chart-tab :returns
           :returns-benchmark-coins ["BTC" "ETH"]
           :account-info-tab :trade-history}))))

(deftest portfolio-query-params-serializes-deterministic-shareable-snapshot-test
  (is (= [["range" "all"]
          ["scope" "perps"]
          ["chart" "returns"]
          ["bench" "BTC"]
          ["bench" "vault:0x1234567890abcdef1234567890abcdef12345678"]
          ["tab" "open-orders"]]
         (query-state/portfolio-query-params
          {:portfolio-ui {:summary-time-range :all-time
                          :summary-scope :perps
                          :chart-tab :returns
                          :returns-benchmark-coins ["BTC"
                                                    "vault:0x1234567890abcdef1234567890abcdef12345678"
                                                    "BTC"]
                          :account-info-tab :open-orders}}))))

(deftest portfolio-query-params-serializes-cleared-benchmarks-test
  (is (= [["range" "3m"]
          ["scope" "all"]
          ["chart" "returns"]
          ["bench" ""]
          ["tab" "performance-metrics"]]
         (query-state/portfolio-query-params
          {:portfolio-ui {:summary-time-range :three-month
                          :summary-scope :all
                          :chart-tab :returns
                          :returns-benchmark-coins []
                          :returns-benchmark-coin nil
                          :account-info-tab :performance-metrics}}))))

(deftest public-range-tokens-round-trip-test
  (is (= :day (query-state/parse-range-value "24h")))
  (is (= :week (query-state/parse-range-value "7d")))
  (is (= :month (query-state/parse-range-value "30d")))
  (is (= :all-time (query-state/parse-range-value "all")))
  (is (= "3m" (query-state/range-token :three-month)))
  (is (= "1y" (query-state/range-token :one-year))))
