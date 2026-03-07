(ns hyperopen.vaults.application.route-loading-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.vaults.application.route-loading :as route-loading]))

(deftest load-vault-route-keeps-projection-first-ordering-for-detail-routes-test
  (is (= [[:effects/save [:vaults-ui :list-loading?] true]
          [:effects/save [:vaults-ui :detail-loading?] true]
          [:effects/save [:vaults-ui :detail-chart-hover-index] nil]
          [:effects/api-fetch-vault-index]
          [:effects/api-fetch-vault-summaries]
          [:effects/api-fetch-user-vault-equities "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"]
          [:effects/api-fetch-vault-details "0x1234567890abcdef1234567890abcdef12345678" "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"]
          [:effects/api-fetch-vault-webdata2 "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-fills "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-funding-history "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-order-history "0x1234567890abcdef1234567890abcdef12345678"]
          [:effects/api-fetch-vault-ledger-updates "0x1234567890abcdef1234567890abcdef12345678"]]
         (route-loading/load-vault-route
          {:wallet {:address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}}
          {:kind :detail
           :path "/vaults/0x1234567890abcdef1234567890abcdef12345678"
           :vault-address "0x1234567890abcdef1234567890abcdef12345678"}))))

(deftest load-vault-detail-fetches-component-history-and-benchmark-effects-test
  (let [state {:wallet {:address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
               :vaults {:merged-index-rows [{:vault-address "0x1234567890abcdef1234567890abcdef12345678"
                                             :relationship {:type :parent
                                                            :child-addresses ["0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]}}]}
               :vaults-ui {:snapshot-range :month
                           :detail-returns-benchmark-coins ["BTC"]}}]
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
            [:effects/fetch-candle-snapshot :coin "BTC" :interval :1h :bars 800]]
           (route-loading/load-vault-detail
            state
            "0x1234567890abcdef1234567890abcdef12345678")))))
