(ns hyperopen.portfolio.optimizer.universe-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions :as actions]))

(def ^:private selection-prefetch-effect
  [:effects/load-portfolio-optimizer-history
   {:source :selection-prefetch
    :queue? true
    :merge? true}])

(defn- queued-prefetch-state
  [instruments]
  {:queue (vec instruments)
   :active-instrument-id nil
   :by-instrument-id
   (into {}
         (map (fn [instrument]
                [(:instrument-id instrument)
                 {:status :queued
                  :started-at-ms nil
                  :completed-at-ms nil
                  :error nil
                  :warnings []}]))
         instruments)})

(deftest set-draft-universe-from-current-holdings-test
  (let [btc-instrument {:instrument-id "perp:BTC"
                        :market-type :perp
                        :coin "BTC"
                        :shortable? true}
        purr-instrument {:instrument-id "spot:PURR"
                         :market-type :spot
                         :coin "PURR"
                         :shortable? false
                         :symbol "PURR/USDC"
                         :base "PURR"
                         :quote "USDC"}
        state {:webdata2 {:clearinghouseState
                          {:marginSummary {:accountValue "1000"}
                           :assetPositions
                           [{:position {:coin "BTC"
                                        :szi "0.5"
                                        :positionValue "500"
                                        :leverage {:type "cross"
                                                   :value "5"}}}]}}
               :spot {:balances [{:coin "PURR"
                                  :total "10"}]}
               :asset-selector {:market-by-key
                                {"spot:PURR" {:key "spot:PURR"
                                              :market-type :spot
                                              :coin "PURR/USDC"
                                              :symbol "PURR/USDC"
                                              :base "PURR"
                                              :quote "USDC"
                                              :mark "2"}}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [btc-instrument purr-instrument]]
              [[:portfolio :optimizer :history-prefetch]
               (queued-prefetch-state [btc-instrument purr-instrument])]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]
            selection-prefetch-effect]
           (actions/set-portfolio-optimizer-universe-from-current state)))))

(deftest set-draft-universe-from-current-holdings-ignores-empty-snapshot-test
  (is (= []
         (actions/set-portfolio-optimizer-universe-from-current {}))))

(deftest add-draft-universe-instrument-from-asset-selector-market-test
  (let [btc-instrument {:instrument-id "perp:BTC"
                        :market-type :perp
                        :coin "BTC"
                        :shortable? true}
        eth-instrument {:instrument-id "perp:ETH"
                        :market-type :perp
                        :coin "ETH"
                        :shortable? true
                        :dex "hl"
                        :symbol "ETH-USDC"
                        :base "ETH"
                        :quote "USDC"}
        purr-instrument {:instrument-id "spot:PURR/USDC"
                         :market-type :spot
                         :coin "PURR/USDC"
                         :shortable? false
                         :symbol "PURR/USDC"
                         :base "PURR"
                         :quote "USDC"}
        state {:portfolio {:optimizer {:draft {:universe [btc-instrument]}}}
               :asset-selector {:market-by-key
                                {"perp:ETH" {:key "perp:ETH"
                                             :market-type :perp
                                             :coin "ETH"
                                             :symbol "ETH-USDC"
                                             :base "ETH"
                                             :quote "USDC"
                                             :dex "hl"
                                             :maxLeverage 50}
                                 "spot:PURR/USDC" {:key "spot:PURR/USDC"
                                                   :market-type :spot
                                                   :coin "PURR/USDC"
                                                   :symbol "PURR/USDC"
                                                   :base "PURR"
                                                   :quote "USDC"}}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [btc-instrument eth-instrument]]
              [[:portfolio-ui :optimizer :universe-search-query]
               ""]
              [[:portfolio-ui :optimizer :universe-search-active-index]
               0]
              [[:portfolio :optimizer :history-prefetch]
               (queued-prefetch-state [eth-instrument])]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]
            selection-prefetch-effect]
           (actions/add-portfolio-optimizer-universe-instrument
            state
            "perp:ETH")))
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [btc-instrument purr-instrument]]
              [[:portfolio-ui :optimizer :universe-search-query]
               ""]
              [[:portfolio-ui :optimizer :universe-search-active-index]
               0]
              [[:portfolio :optimizer :history-prefetch]
               (queued-prefetch-state [purr-instrument])]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]
            selection-prefetch-effect]
           (actions/add-portfolio-optimizer-universe-instrument
            state
            "spot:PURR/USDC")))))

(deftest add-draft-universe-instrument-from-vault-row-test
  (let [vault-address "0x1111111111111111111111111111111111111111"
        vault-instrument {:instrument-id (str "vault:" vault-address)
                          :market-type :vault
                          :coin (str "vault:" vault-address)
                          :vault-address vault-address
                          :shortable? false
                          :name "Alpha Yield"
                          :symbol "Alpha Yield"
                          :tvl 500}
        state {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"
                                                            :market-type :perp
                                                            :coin "BTC"
                                                            :shortable? true}]}}}
               :vaults {:merged-index-rows [{:name "Alpha Yield"
                                             :vault-address "0x1111111111111111111111111111111111111111"
                                             :relationship {:type :normal}
                                             :tvl 500}]}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [{:instrument-id "perp:BTC"
                 :market-type :perp
                 :coin "BTC"
                 :shortable? true}
                vault-instrument]]
              [[:portfolio-ui :optimizer :universe-search-query]
               ""]
              [[:portfolio-ui :optimizer :universe-search-active-index]
               0]
              [[:portfolio :optimizer :history-prefetch]
               (queued-prefetch-state [vault-instrument])]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]
            selection-prefetch-effect]
           (actions/add-portfolio-optimizer-universe-instrument
            state
            (str "vault:" vault-address))))))

(deftest add-draft-universe-instrument-skips-prefetch-when-history-is-loaded-test
  (let [eth-instrument {:instrument-id "perp:ETH"
                        :market-type :perp
                        :coin "ETH"
                        :shortable? true}
        state {:portfolio {:optimizer
                           {:draft {:universe []}
                            :history-data {:candle-history-by-coin
                                           {"ETH" [{:time 1000 :close "100"}
                                                   {:time 2000 :close "101"}]}
                                           :funding-history-by-coin
                                           {"ETH" [{:time-ms 1000
                                                   :funding-rate-raw 0}]}}}}
               :asset-selector {:market-by-key
                                {"perp:ETH" {:key "perp:ETH"
                                             :market-type :perp
                                             :coin "ETH"}}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [eth-instrument]]
              [[:portfolio-ui :optimizer :universe-search-query]
               ""]
              [[:portfolio-ui :optimizer :universe-search-active-index]
               0]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]]
           (actions/add-portfolio-optimizer-universe-instrument
            state
            "perp:ETH")))))

(deftest add-draft-universe-instrument-rejects-missing-or-duplicate-market-test
  (let [state {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"
                                                            :market-type :perp
                                                            :coin "BTC"}]}}}
               :asset-selector {:market-by-key {"perp:BTC" {:key "perp:BTC"
                                                            :market-type :perp
                                                            :coin "BTC"}}}}]
    (is (= []
           (actions/add-portfolio-optimizer-universe-instrument
            state
            "perp:BTC")))
    (is (= []
           (actions/add-portfolio-optimizer-universe-instrument
            state
            "perp:ETH")))
    (is (= []
           (actions/add-portfolio-optimizer-universe-instrument
            state
            " ")))))

(deftest remove-draft-universe-instrument-cleans-dependent-constraints-test
  (let [state {:portfolio
               {:optimizer
                {:draft
                 {:universe [{:instrument-id "perp:BTC"
                              :market-type :perp
                              :coin "BTC"}
                             {:instrument-id "perp:ETH"
                              :market-type :perp
                              :coin "ETH"}]
                  :constraints {:allowlist ["perp:BTC" "perp:ETH"]
                                :blocklist ["perp:ETH"]
                                :held-locks ["perp:ETH"]
                                :asset-overrides {"perp:ETH" {:max-weight 0.2}
                                                  "perp:BTC" {:max-weight 0.5}}
                                :perp-leverage {"perp:ETH" {:max-weight 0.4}}}}}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [{:instrument-id "perp:BTC"
                 :market-type :perp
                 :coin "BTC"}]]
              [[:portfolio :optimizer :draft :constraints :allowlist]
               ["perp:BTC"]]
              [[:portfolio :optimizer :draft :constraints :blocklist]
               []]
              [[:portfolio :optimizer :draft :constraints :held-locks]
               []]
              [[:portfolio :optimizer :draft :constraints :asset-overrides]
               {"perp:BTC" {:max-weight 0.5}}]
              [[:portfolio :optimizer :draft :constraints :perp-leverage]
               {}]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]]
           (actions/remove-portfolio-optimizer-universe-instrument
            state
            "perp:ETH")))
    (is (= []
           (actions/remove-portfolio-optimizer-universe-instrument
            state
            "perp:SOL")))))
