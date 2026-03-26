(ns hyperopen.vaults.application.list-commands-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.vaults.application.list-commands :as list-commands]
            [hyperopen.vaults.infrastructure.routes :as routes]))

(deftest set-vaults-snapshot-range-persists-preference-and-fetches-benchmarks-on-detail-route-test
  (is (= [[:effects/save-many [[[:vaults-ui :snapshot-range] :week]
                               [[:vaults-ui :user-vaults-page] 1]
                               [[:vaults-ui :detail-chart-hover-index] nil]
                               [[:vaults-ui :detail-chart-timeframe-dropdown-open?] false]
                               [[:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?] false]]]
          [:effects/local-storage-set "vaults-snapshot-range" "week"]
          [:effects/fetch-candle-snapshot :coin "BTC" :interval :15m :bars 800 :detail-route-vault-address "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/fetch-candle-snapshot :coin "ETH" :interval :15m :bars 800 :detail-route-vault-address "0x1234567890abcdef1234567890abcdef12345678"]]
         (list-commands/set-vaults-snapshot-range
          {:parse-vault-route-fn routes/parse-vault-route
           :snapshot-range-save-effect-fn (fn [snapshot-range]
                                            [:effects/local-storage-set "vaults-snapshot-range" (name snapshot-range)])}
          {:vaults-ui {:detail-chart-series :returns
                       :detail-returns-benchmark-coins ["BTC"
                                                        "vault:0x1234567890abcdef1234567890abcdef12345678"
                                                        "ETH"]}
           :router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}}
          :week))))

(deftest vault-detail-timeframe-dropdown-actions-open-one-menu-and-close-both-test
  (is (= [[:effects/save-many [[[:vaults-ui :detail-chart-timeframe-dropdown-open?] true]
                               [[:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?] false]]]]
         (list-commands/toggle-vault-detail-chart-timeframe-dropdown
          {:vaults-ui {:detail-chart-timeframe-dropdown-open? false
                       :detail-performance-metrics-timeframe-dropdown-open? true}})))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-chart-timeframe-dropdown-open?] false]
                               [[:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?] false]]]]
         (list-commands/toggle-vault-detail-chart-timeframe-dropdown
          {:vaults-ui {:detail-chart-timeframe-dropdown-open? true
                       :detail-performance-metrics-timeframe-dropdown-open? false}})))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-chart-timeframe-dropdown-open?] false]
                               [[:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?] true]]]]
         (list-commands/toggle-vault-detail-performance-metrics-timeframe-dropdown
          {:vaults-ui {:detail-chart-timeframe-dropdown-open? true
                       :detail-performance-metrics-timeframe-dropdown-open? false}})))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-chart-timeframe-dropdown-open?] false]
                               [[:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?] false]]]]
         (list-commands/close-vault-detail-chart-timeframe-dropdown {})))
  (is (= [[:effects/save-many [[[:vaults-ui :detail-chart-timeframe-dropdown-open?] false]
                               [[:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?] false]]]]
         (list-commands/close-vault-detail-performance-metrics-timeframe-dropdown {}))))
