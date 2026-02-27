(ns hyperopen.views.vaults.detail-vm-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.vaults.detail-vm :as detail-vm]))

(def sample-state
  {:router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}
   :vaults-ui {:detail-tab :about
               :detail-activity-tab :positions
               :detail-chart-series :pnl
               :snapshot-range :month
               :detail-loading? false}
   :vaults {:errors {:details-by-address {}
                     :webdata-by-vault {}
                     :fills-by-vault {}
                     :funding-history-by-vault {}
                     :order-history-by-vault {}
                     :ledger-updates-by-vault {}}
            :loading {:fills-by-vault {}
                      :funding-history-by-vault {}
                      :order-history-by-vault {}
                      :ledger-updates-by-vault {}}
            :details-by-address {"0x1234567890abcdef1234567890abcdef12345678"
                                 {:name "Vault Detail"
                                  :leader "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                                  :description "Sample vault"
                                  :portfolio {:month {:accountValueHistory [[1 10] [2 11] [3 15]]
                                                      :pnlHistory [[1 -1] [2 0.5] [3 2.5]]}}
                                  :followers [{:user "0xf1"} {:user "0xf2"}]
                                  :leader-commission 0.15
                                  :relationship {:type :child
                                                 :parent-address "0x9999999999999999999999999999999999999999"}
                                  :follower-state {:vault-equity 50
                                                   :all-time-pnl 12}}}
            :webdata-by-vault {"0x1234567890abcdef1234567890abcdef12345678"
                               {:fills [{:time 3
                                         :coin "BTC"
                                         :side "buy"
                                         :sz "0.5"
                                         :px "101"}
                                        {:time 4
                                         :coin "ETH"
                                         :side "sell"
                                         :sz "1.2"
                                         :px "202"}]
                               :openOrders [{:order {:coin "BTC"
                                                      :side "B"
                                                      :sz "0.1"
                                                      :limitPx "100"
                                                      :timestamp 9}}]
                                :twapStates [{:coin "BTC"
                                              :sz "1.0"
                                              :executedSz "0.2"
                                              :avgPx "101"
                                              :creationTime 12}]
                                :clearinghouseState {:assetPositions [{:position {:coin "BTC"
                                                                                   :szi "0.2"
                                                                                   :entryPx "100"
                                                                                   :positionValue "20"
                                                                                   :unrealizedPnl "1"
                                                                                   :returnOnEquity "0.05"}}
                                                                       {:position {:coin "ETH"
                                                                                   :szi "-1.2"
                                                                                   :entryPx "200"
                                                                                   :positionValue "240"
                                                                                   :unrealizedPnl "-2"
                                                                                   :returnOnEquity "-0.1"}}]}}}
            :fills-by-vault {"0x1234567890abcdef1234567890abcdef12345678"
                             [{:time 3
                               :coin "BTC"
                               :side "buy"
                               :sz "0.5"
                               :px "101"}
                              {:time 4
                               :coin "ETH"
                               :side "sell"
                               :sz "1.2"
                               :px "202"}]}
            :funding-history-by-vault {"0x1234567890abcdef1234567890abcdef12345678"
                                       [{:time-ms 5
                                         :coin "BTC"
                                         :fundingRate 0.001
                                         :positionSize 3
                                         :payment -4.2}]}
            :order-history-by-vault {"0x1234567890abcdef1234567890abcdef12345678"
                                     [{:order {:coin "BTC"
                                               :side "B"
                                               :origSz "1.0"
                                               :limitPx "99"
                                               :orderType "Limit"
                                               :timestamp 10}
                                       :status "filled"
                                       :statusTimestamp 11}]}
            :ledger-updates-by-vault {"0x1234567890abcdef1234567890abcdef12345678"
                                      [{:time 12
                                        :hash "0xabc"
                                        :delta {:type "vaultDeposit"
                                                :vault "0x1234567890abcdef1234567890abcdef12345678"
                                                :usdc "10.0"}}]}
            :user-equity-by-address {"0x1234567890abcdef1234567890abcdef12345678"
                                     {:equity 50}}
            :merged-index-rows [{:name "Vault Detail"
                                 :vault-address "0x1234567890abcdef1234567890abcdef12345678"
                                 :leader "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                                 :tvl 200
                                 :apr 0.2
                                 :snapshot-by-key {:month [0.1 0.2]
                                                   :all-time [0.5]}}]}})

(deftest vault-detail-vm-builds-metrics-relationship-chart-and-activity-test
  (let [vm (detail-vm/vault-detail-vm sample-state)]
    (is (= :detail (:kind vm)))
    (is (= "Vault Detail" (:name vm)))
    (is (= "0x1234567890abcdef1234567890abcdef12345678" (:vault-address vm)))
    (is (= :child (get-in vm [:relationship :type])))
    (is (= "0x9999999999999999999999999999999999999999"
           (get-in vm [:relationship :parent-address])))
    (is (= 200 (get-in vm [:metrics :tvl])))
    (is (= 20 (get-in vm [:metrics :past-month-return])))
    (is (= 50 (get-in vm [:metrics :your-deposit])))
    (is (= 12 (get-in vm [:metrics :all-time-earned])))
    (is (= 2 (:followers vm)))
    (is (seq (get-in vm [:chart :points])))
    (is (seq (get-in vm [:chart :path])))
    (is (= :pnl (get-in vm [:chart :selected-series])))
    (is (= :month (get-in vm [:chart :selected-timeframe])))
    (is (= 4 (count (get-in vm [:chart :timeframe-options]))))
    (is (= :positions (:selected-activity-tab vm)))
    (is (= 2 (count (:activity-positions vm))))
    (is (= 1 (count (:activity-open-orders vm))))
    (is (= 2 (count (:activity-fills vm))))
    (is (= 1 (count (:activity-funding-history vm))))
    (is (= 1 (count (:activity-order-history vm))))
    (is (= 1 (count (:activity-deposits-withdrawals vm))))
    (is (= 2 (count (:activity-depositors vm))))
    (is (= 2 (get-in vm [:activity-summary :fill-count])))
    (is (= 1 (get-in vm [:activity-summary :open-order-count])))
    (is (= 2 (get-in vm [:activity-summary :position-count])))))

(deftest vault-detail-vm-flags-invalid-vault-addresses-test
  (let [vm (detail-vm/vault-detail-vm (assoc-in sample-state [:router :path] "/vaults/not-an-address"))]
    (is (= :detail (:kind vm)))
    (is (true? (:invalid-address? vm)))))

(deftest vault-detail-vm-prefers-apr-for-past-month-return-and-normalizes-depositor-count-test
  (let [state (-> sample-state
                  (assoc-in [:vaults :details-by-address "0x1234567890abcdef1234567890abcdef12345678" :apr] 0.21)
                  (assoc-in [:vaults :details-by-address "0x1234567890abcdef1234567890abcdef12345678" :followers] [])
                  (assoc-in [:vaults :details-by-address "0x1234567890abcdef1234567890abcdef12345678" :followers-count] 137)
                  (assoc-in [:vaults :merged-index-rows 0 :snapshot-by-key :month] [0.0 19098892.322411]))
        vm (detail-vm/vault-detail-vm state)]
    (is (= 21 (get-in vm [:metrics :past-month-return])))
    (is (= 137 (:followers vm)))
    (is (= 137
           (some->> (:activity-tabs vm)
                    (filter #(= :depositors (:value %)))
                    first
                    :count)))))

(deftest vault-detail-vm-selects-account-value-series-when-user-selects-it-test
  (let [state (assoc-in sample-state [:vaults-ui :detail-chart-series] :account-value)
        vm (detail-vm/vault-detail-vm state)]
    (is (= :account-value (get-in vm [:chart :selected-series])))))

(deftest vault-detail-vm-aggregates-component-history-and-accepts-channel-shaped-sources-test
  (let [child-address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        state (-> sample-state
                  (assoc-in [:vaults :details-by-address "0x1234567890abcdef1234567890abcdef12345678" :relationship]
                            {:type :parent
                             :child-addresses [child-address]})
                  (assoc-in [:vaults :fills-by-vault child-address]
                            [{:time 8
                              :coin "SOL"
                              :side "buy"
                              :sz "1.0"
                              :px "30"}])
                  (assoc-in [:vaults :funding-history-by-vault child-address]
                            [{:time 9
                              :coin "SOL"
                              :fundingRate 0.002
                              :szi 1.0
                              :usdc 1.5}])
                  (assoc-in [:vaults :order-history-by-vault child-address]
                            [{:order {:coin "SOL"
                                      :side "B"
                                      :origSz "1.0"
                                      :limitPx "31"
                                      :orderType "Limit"
                                      :timestamp 10}
                              :status "filled"
                              :statusTimestamp 11}])
                  (assoc-in [:vaults :webdata-by-vault "0x1234567890abcdef1234567890abcdef12345678" :openOrders]
                            {:orders [{:order {:coin "BTC"
                                               :side "B"
                                               :sz "0.1"
                                               :limitPx "100"
                                               :timestamp 9}}]})
                  (assoc-in [:vaults :webdata-by-vault "0x1234567890abcdef1234567890abcdef12345678" :twapStates]
                            {:states [{:coin "BTC"
                                       :sz "1.0"
                                       :executedSz "0.2"
                                       :avgPx "101"
                                       :creationTime 12}]})
                  (assoc-in [:vaults :ledger-updates-by-vault "0x1234567890abcdef1234567890abcdef12345678"]
                            {:nonFundingLedgerUpdates [{:time 12
                                                       :hash "0xabc"
                                                       :delta {:type "vaultDeposit"
                                                               :vault "0x1234567890abcdef1234567890abcdef12345678"
                                                               :usdc "10.0"}}]}))
        vm (detail-vm/vault-detail-vm state)]
    (is (= 3 (count (:activity-fills vm))))
    (is (= 2 (count (:activity-funding-history vm))))
    (is (= 2 (count (:activity-order-history vm))))
    (is (= 1 (count (:activity-open-orders vm))))
    (is (= 1 (count (:activity-twaps vm))))
    (is (= 1 (count (:activity-deposits-withdrawals vm))))
    (is (= 3 (get-in vm [:activity-summary :fill-count])))))
