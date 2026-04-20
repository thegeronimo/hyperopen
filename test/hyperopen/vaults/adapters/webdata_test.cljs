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

(deftest fills-normalizes-aliases-and-sorts-newest-first-test
  (let [rows (webdata/fills {:fills [{:timeMs 20
                                      :symbol "BTC"
                                      :dir "sell"
                                      :size "1.25"
                                      :price "100.5"
                                      :closed-pnl "2.5"}
                                     {:timestamp 10
                                      :asset "ETH"
                                      :side "buy"
                                      :closedSize "2"
                                      :px "50"
                                      :pnl -1}]})
        first-row (first rows)
        second-row (second rows)]
    (is (= 2 (count rows)))
    (is (= 20 (:time-ms first-row)))
    (is (= "BTC" (:coin first-row)))
    (is (= "Short" (:side first-row)))
    (is (= :short (:side-key first-row)))
    (is (= 1.25 (:size first-row)))
    (is (= 100.5 (:price first-row)))
    (is (= 125.625 (:trade-value first-row)))
    (is (= 2.5 (:closed-pnl first-row)))
    (is (= 10 (:time-ms second-row)))
    (is (= "ETH" (:coin second-row)))
    (is (= "Long" (:side second-row)))
    (is (= :long (:side-key second-row)))
    (is (= 2 (:size second-row)))
    (is (= 50 (:price second-row)))
    (is (= 100 (:trade-value second-row)))
    (is (= -1 (:closed-pnl second-row)))))

(deftest fills-preserves-hyperliquid-open-close-direction-labels-test
  (let [rows (webdata/fills {:fills [{:time 40
                                      :coin "HYPE"
                                      :side "A"
                                      :dir "Close Long"
                                      :sz "2"
                                      :px "41.496"}
                                     {:time 30
                                      :coin "HYPE"
                                      :side "B"
                                      :dir "Open Long"
                                      :sz "1"
                                      :px "41.297"}
                                     {:time 20
                                      :coin "BTC"
                                      :side "A"
                                      :dir "Open Short"
                                      :sz "3"
                                      :px "100"}
                                     {:time 10
                                      :coin "BTC"
                                      :side "B"
                                      :dir "Close Short"
                                      :sz "4"
                                      :px "99"}]})]
    (is (= ["Close Long" "Open Long" "Open Short" "Close Short"]
           (mapv :side rows)))
    (is (= [:short :long :short :long]
           (mapv :side-key rows)))
    (is (= [:long :long :short :short]
           (mapv :direction-key rows)))))

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

(deftest balances-prefers-spot-state-balances-and-sorts-by-absolute-total-test
  (let [rows (webdata/balances {:spotState {:balances [{:token "USDC"
                                                        :hold "2"
                                                        :free "8"
                                                        :usdValue "10"}
                                                       {:coin "ETH"
                                                        :total "5"
                                                        :availableBalance "4"
                                                        :usdcValue "9"}]}
                               :balances [{:coin "SHOULD-NOT-SEE"
                                           :total "999"}]
                               :data {:spotState {:balances [{:coin "ALT"
                                                              :total "100"}]}}})
        first-row (first rows)
        second-row (second rows)]
    (is (= 2 (count rows)))
    (is (= "ETH" (:coin first-row)))
    (is (= 5 (:total first-row)))
    (is (= 4 (:available first-row)))
    (is (= 9 (:usdc-value first-row)))
    (is (= "USDC" (:coin second-row)))
    (is (= 2 (:total second-row)))
    (is (= 8 (:available second-row)))
    (is (= 10 (:usdc-value second-row)))))

(deftest balances-falls-back-to-perps-row-when-spot-balances-are-missing-test
  (let [rows (webdata/balances {:clearinghouseState {:marginSummary {:accountValue 159.379
                                                                     :totalMarginUsed 10.001}
                                                     :withdrawable 150}})]
    (is (= 1 (count rows)))
    (is (= "USDC (Perps)" (:coin (first rows))))
    (is (= 159.379 (:total (first rows))))
    (is (= 150 (:available (first rows))))
    (is (= 159.379 (:usdc-value (first rows))))))

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
