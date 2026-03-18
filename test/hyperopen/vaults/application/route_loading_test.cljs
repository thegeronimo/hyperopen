(ns hyperopen.vaults.application.route-loading-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.vaults.application.route-loading :as route-loading]))

(deftest load-vault-route-keeps-projection-first-ordering-for-detail-routes-test
  (is (= [[:effects/save [:vaults-ui :detail-loading?] true]
          [:effects/save [:vaults-ui :detail-chart-hover-index] nil]
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

(deftest load-vault-route-prefers-effective-account-address-for-user-scoped-reads-test
  (let [viewer-address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        state {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
               :account-context {:spectate-mode {:active? true
                                                 :address viewer-address}}
               :vaults-ui {:snapshot-range :month}
               :vaults {:merged-index-rows []}}]
    (is (= [[:effects/save [:vaults-ui :detail-loading?] true]
            [:effects/save [:vaults-ui :detail-chart-hover-index] nil]
            [:effects/api-fetch-user-vault-equities viewer-address]
            [:effects/api-fetch-vault-details "0x1234567890abcdef1234567890abcdef12345678" viewer-address]
            [:effects/api-fetch-vault-webdata2 "0x1234567890abcdef1234567890abcdef12345678"]
            [:effects/api-fetch-vault-fills "0x1234567890abcdef1234567890abcdef12345678"]
            [:effects/api-fetch-vault-funding-history "0x1234567890abcdef1234567890abcdef12345678"]
            [:effects/api-fetch-vault-order-history "0x1234567890abcdef1234567890abcdef12345678"]
            [:effects/api-fetch-vault-ledger-updates "0x1234567890abcdef1234567890abcdef12345678"]]
           (route-loading/load-vault-route
            state
            {:kind :detail
             :path "/vaults/0x1234567890abcdef1234567890abcdef12345678"
             :vault-address "0x1234567890abcdef1234567890abcdef12345678"})))))

(deftest load-vault-route-fetches-list-metadata-and-benchmark-details-when-vault-benchmarks-are-active-test
  (let [benchmark-address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        state {:wallet {:address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
               :vaults-ui {:snapshot-range :month
                           :detail-returns-benchmark-coins [(str "vault:" benchmark-address)]
                           :detail-returns-benchmark-suggestions-open? true}
               :vaults {:merged-index-rows []}}]
    (is (= [[:effects/save [:vaults-ui :list-loading?] true]
            [:effects/save [:vaults-ui :detail-loading?] true]
            [:effects/save [:vaults-ui :detail-chart-hover-index] nil]
            [:effects/api-fetch-user-vault-equities "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"]
            [:effects/api-fetch-vault-index]
            [:effects/api-fetch-vault-summaries]
            [:effects/api-fetch-vault-benchmark-details benchmark-address]
            [:effects/api-fetch-vault-details "0x1234567890abcdef1234567890abcdef12345678" "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"]
            [:effects/api-fetch-vault-webdata2 "0x1234567890abcdef1234567890abcdef12345678"]
            [:effects/api-fetch-vault-fills "0x1234567890abcdef1234567890abcdef12345678"]
            [:effects/api-fetch-vault-funding-history "0x1234567890abcdef1234567890abcdef12345678"]
            [:effects/api-fetch-vault-order-history "0x1234567890abcdef1234567890abcdef12345678"]
            [:effects/api-fetch-vault-ledger-updates "0x1234567890abcdef1234567890abcdef12345678"]]
           (route-loading/load-vault-route
            state
            {:kind :detail
             :path "/vaults/0x1234567890abcdef1234567890abcdef12345678"
             :vault-address "0x1234567890abcdef1234567890abcdef12345678"})))))

(deftest load-vault-route-keeps-portfolio-vault-benchmark-bootstrap-lazy-until-needed-test
  (is (= []
         (route-loading/load-vault-route
          {:wallet {:address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}}
          {:kind :other
           :path "/portfolio"})))
  (let [benchmark-address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
    (is (= [[:effects/save [:vaults-ui :list-loading?] true]
            [:effects/api-fetch-vault-index]
            [:effects/api-fetch-vault-summaries]
            [:effects/api-fetch-vault-benchmark-details benchmark-address]]
           (route-loading/load-vault-route
            {:portfolio-ui {:returns-benchmark-coins [(str "vault:" benchmark-address)]}
             :vaults {:merged-index-rows []}}
            {:kind :other
             :path "/portfolio"})))))
