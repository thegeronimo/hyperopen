(ns hyperopen.vaults.adapters.webdata-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.vaults.adapters.webdata :as webdata]))

(deftest rows-from-source-supports-channel-wrapped-payloads-test
  (is (= [{:time 1}]
         (webdata/rows-from-source {:data {:fills [{:time 1}]}}
                                   [:fills :userFills])))
  (is (= [{:time 2}]
         (webdata/rows-from-source {:openOrders {:orders [{:time 2}]}}
                                   [:openOrders :orders]))))

(deftest positions-normalizes-clearinghouse-shapes-test
  (let [rows (webdata/positions {:clearinghouseState {:assetPositions [{:position {:coin "BTC"
                                                                                     :szi "0.5"
                                                                                     :entryPx "100"
                                                                                     :positionValue "50"
                                                                                     :returnOnEquity "0.1"}}]}})]
    (is (= 1 (count rows)))
    (is (= "BTC" (get-in rows [0 :coin])))
    (is (= 10 (get-in rows [0 :roe])))))

(deftest twaps-normalizes-running-window-with-injected-now-ms-test
  (let [rows (webdata/twaps {:twapStates {:states [{:coin "BTC"
                                                    :sz "1.0"
                                                    :executedSz "0.2"
                                                    :avgPx "101"
                                                    :startTime 1000
                                                    :durationMs 10800000
                                                    :creationTime 12}]}}
                           3661000)]
    (is (= 1 (count rows)))
    (is (= 3660000 (get-in rows [0 :running-ms])))
    (is (= "1h 1m / 3h 0m" (get-in rows [0 :running-label])))))

(deftest ledger-updates-filters-to-target-vault-address-test
  (let [rows (webdata/ledger-updates {:nonFundingLedgerUpdates
                                      [{:time 1
                                        :delta {:type "vaultDeposit"
                                                :vault "0xaaa"
                                                :usdc "10"}}
                                       {:time 2
                                        :delta {:type "vaultWithdraw"
                                                :vault "0xbbb"
                                                :usdc "5"}}]}
                                     "0xaaa")]
    (is (= 1 (count rows)))
    (is (= "Deposit" (get-in rows [0 :type-label])))))
