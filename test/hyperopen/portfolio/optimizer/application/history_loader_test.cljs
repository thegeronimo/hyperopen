(ns hyperopen.portfolio.optimizer.application.history-loader-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.history-loader :as history-loader]
            [hyperopen.portfolio.optimizer.application.history-loader-fixtures :as fixtures]))

(deftest build-history-request-plan-fetches-candles-for-all-assets-and-funding-for-perps-test
  (let [plan (history-loader/build-history-request-plan
              [{:instrument-id "perp:BTC"
                :market-type :perp
                :coin "BTC"}
               {:instrument-id "spot:PURR"
                :market-type :spot
                :coin "PURR"}
               {:instrument-id "perp:BTC-copy"
                :market-type :perp
                :coin "BTC"}
               {:instrument-id "missing-coin"
                :market-type :spot}]
              {:interval :1d
               :bars 180
               :priority :low
               :now-ms 2000000
               :funding-window-ms 86400000})]
    (is (= [{:coin "BTC"
             :instrument-ids ["perp:BTC" "perp:BTC-copy"]
             :opts {:interval :1d
                    :bars 180
                    :priority :low
                    :cache-key [:portfolio-optimizer :candles "BTC" :1d 180]
                    :dedupe-key [:portfolio-optimizer :candles "BTC" :1d 180]}}
            {:coin "PURR"
             :instrument-ids ["spot:PURR"]
             :opts {:interval :1d
                    :bars 180
                    :priority :low
                    :cache-key [:portfolio-optimizer :candles "PURR" :1d 180]
                    :dedupe-key [:portfolio-optimizer :candles "PURR" :1d 180]}}]
           (:candle-requests plan)))
    (is (= [{:coin "BTC"
             :instrument-ids ["perp:BTC" "perp:BTC-copy"]
             :opts {:priority :low
                    :start-time-ms (- 2000000 86400000)
                    :end-time-ms 2000000
                    :cache-key [:portfolio-optimizer :funding "BTC" (- 2000000 86400000) 2000000]
                    :dedupe-key [:portfolio-optimizer :funding "BTC" (- 2000000 86400000) 2000000]}}]
           (:funding-requests plan)))
    (is (= [{:code :missing-history-coin
             :instrument-id "missing-coin"
             :market-type :spot}]
           (:warnings plan)))))

(deftest build-history-request-plan-defaults-to-three-year-lookback-window-test
  (let [plan (history-loader/build-history-request-plan
              [{:instrument-id "perp:BTC"
                :market-type :perp
                :coin "BTC"}]
              {})
        candle (first (:candle-requests plan))]
    (is (= history-loader/default-bars (get-in candle [:opts :bars])))
    (is (>= history-loader/default-bars 1095)
        "default lookback window should be at least ~3 years (1095 daily bars)")))

(deftest build-history-request-plan-fetches-vault-details-without-market-candles-test
  (let [vault-address "0x1111111111111111111111111111111111111111"
        plan (history-loader/build-history-request-plan
              [{:instrument-id "perp:BTC"
                :market-type :perp
                :coin "BTC"}
               {:instrument-id (str "vault:" vault-address)
                :market-type :vault
                :coin (str "vault:" vault-address)
                :vault-address vault-address}]
              {:interval :1d
               :bars 180
               :priority :low
               :now-ms 2000000
               :funding-window-ms 86400000})]
    (is (= [{:coin "BTC"
             :instrument-ids ["perp:BTC"]
             :opts {:interval :1d
                    :bars 180
                    :priority :low
                    :cache-key [:portfolio-optimizer :candles "BTC" :1d 180]
                    :dedupe-key [:portfolio-optimizer :candles "BTC" :1d 180]}}]
           (:candle-requests plan)))
    (is (= [{:coin "BTC"
             :instrument-ids ["perp:BTC"]
             :opts {:priority :low
                    :start-time-ms (- 2000000 86400000)
                    :end-time-ms 2000000
                    :cache-key [:portfolio-optimizer :funding "BTC" (- 2000000 86400000) 2000000]
                    :dedupe-key [:portfolio-optimizer :funding "BTC" (- 2000000 86400000) 2000000]}}]
           (:funding-requests plan)))
    (is (= [{:vault-address vault-address
             :instrument-ids [(str "vault:" vault-address)]
             :opts {:priority :low
                    :cache-key [:portfolio-optimizer :vault-details vault-address]
                    :dedupe-key [:portfolio-optimizer :vault-details vault-address]}}]
           (:vault-detail-requests plan)))
    (is (= [] (:warnings plan)))))

(deftest build-history-request-plan-uses-perp-base-coin-instead-of-display-pair-test
  (let [plan (history-loader/build-history-request-plan
              [{:instrument-id "perp:ETH"
                :market-type :perp
                :coin "ETH-USDC"
                :symbol "ETH-USDC"
                :base "ETH"
                :quote "USDC"}]
              {:interval :1d
               :bars 180
               :priority :low
               :now-ms 2000000
               :funding-window-ms 86400000})]
    (is (= [{:coin "ETH"
             :instrument-ids ["perp:ETH"]
             :opts {:interval :1d
                    :bars 180
                    :priority :low
                    :cache-key [:portfolio-optimizer :candles "ETH" :1d 180]
                    :dedupe-key [:portfolio-optimizer :candles "ETH" :1d 180]}}]
           (:candle-requests plan)))
    (is (= [{:coin "ETH"
             :instrument-ids ["perp:ETH"]
             :opts {:priority :low
                    :start-time-ms (- 2000000 86400000)
                    :end-time-ms 2000000
                    :cache-key [:portfolio-optimizer :funding "ETH" (- 2000000 86400000) 2000000]
                    :dedupe-key [:portfolio-optimizer :funding "ETH" (- 2000000 86400000) 2000000]}}]
           (:funding-requests plan)))))

(deftest funding-carry-annualizes-hourly-by-default-test
  (is (= 8760 history-loader/default-funding-periods-per-year))
  (let [aligned (history-loader/align-history-inputs
                 {:universe [{:instrument-id "perp:BTC"
                              :market-type :perp
                              :coin "BTC"}]
                  :candle-history-by-coin {"BTC" [{:time 1000 :close "100"}
                                                  {:time 2000 :close "110"}
                                                  {:time 3000 :close "121"}]}
                  :funding-history-by-coin {"BTC" [{:time-ms 1000 :funding-rate-raw "0.001"}
                                                   {:time-ms 2000 :fundingRate 0.002}]}
                  :as-of-ms 4000
                  :stale-after-ms 5000})]
    (is (fixtures/near? 0.0015
                        (get-in aligned [:funding-by-instrument "perp:BTC" :average-rate])))
    (is (fixtures/near? 13.14
                        (get-in aligned [:funding-by-instrument "perp:BTC" :annualized-carry])))))

(deftest align-history-inputs-aligns-common-calendar-and-exposes-funding-carry-test
  (let [aligned (history-loader/align-history-inputs
                 {:universe [{:instrument-id "perp:BTC"
                              :market-type :perp
                              :coin "BTC"}
                             {:instrument-id "spot:PURR"
                              :market-type :spot
                              :coin "PURR"}]
                  :candle-history-by-coin {"BTC" [{:time 1000 :close "100"}
                                                  {:time 2000 :close "110"}
                                                  {:time 3000 :close "121"}]
                                           "PURR" [{:time-ms 1000 :c "10"}
                                                   {:time-ms 2000 :c "11"}
                                                   {:time-ms 3000 :c "11"}]}
                  :funding-history-by-coin {"BTC" [{:time-ms 1000 :funding-rate-raw "0.001"}
                                                   {:time-ms 2000 :fundingRate 0.002}]}
                  :as-of-ms 4000
                  :stale-after-ms 5000
                  :funding-periods-per-year 100})]
    (is (= [1000 2000 3000] (:calendar aligned)))
    (is (= [2000 3000] (:return-calendar aligned)))
    (is (= ["perp:BTC" "spot:PURR"]
           (mapv :instrument-id (:eligible-instruments aligned))))
    (is (fixtures/near? 0.1 (get-in aligned [:return-series-by-instrument "perp:BTC" 0])))
    (is (fixtures/near? 0.1 (get-in aligned [:return-series-by-instrument "perp:BTC" 1])))
    (is (fixtures/near? 0.1 (get-in aligned [:return-series-by-instrument "spot:PURR" 0])))
    (is (fixtures/near? 0.0 (get-in aligned [:return-series-by-instrument "spot:PURR" 1])))
    (is (fixtures/near? 0.15 (get-in aligned [:funding-by-instrument "perp:BTC" :annualized-carry])))
    (is (= :market-funding-history
           (get-in aligned [:funding-by-instrument "perp:BTC" :source])))
    (is (= :not-applicable
           (get-in aligned [:funding-by-instrument "spot:PURR" :source])))
    (is (= [] (:excluded-instruments aligned)))
    (is (= [] (:warnings aligned)))
    (is (= {:as-of-ms 4000
            :latest-common-ms 3000
            :oldest-common-ms 1000
            :age-ms 1000
            :stale? false}
           (:freshness aligned)))))
