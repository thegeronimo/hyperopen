(ns hyperopen.views.funding-comparison.vm-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.funding-comparison.vm :as vm]))

(deftest funding-comparison-vm-builds-rates-open-interest-and-arb-columns-test
  (let [state {:funding-comparison-ui {:query ""
                                       :timeframe :8hour
                                       :sort {:column :open-interest
                                              :direction :desc}}
               :funding-comparison {:predicted-fundings
                                    [["BTC"
                                      [["HlPerp" {:fundingRate "0.0000125"
                                                   :fundingIntervalHours 1}]
                                       ["BinPerp" {:fundingRate "0.0001"
                                                    :fundingIntervalHours 8}]
                                       ["BybitPerp" {:fundingRate "0.00005"
                                                      :fundingIntervalHours 4}]]]]}
               :asset-selector {:favorites #{"perp:BTC"}
                                :market-by-key {"perp:BTC" {:coin "BTC"
                                                             :openInterest 1250000}}}}
        result (vm/funding-comparison-vm state)
        row (first (:rows result))]
    (is (= 1 (:row-count result)))
    (is (= "BTC" (:coin row)))
    (is (= true (:favorite? row)))
    (is (= 1250000 (:open-interest row)))
    (is (= 1.0e-4 (get-in row [:hyperliquid :rate])))
    (is (= 1.0e-4 (get-in row [:binance :rate])))
    (is (= 1.0e-4 (get-in row [:bybit :rate])))
    (is (= 0 (get-in row [:binance-hl-arb :value])))
    (is (= 0 (get-in row [:bybit-hl-arb :value])))))

(deftest funding-comparison-vm-uses-venue-fallback-interval-heuristics-test
  (let [state {:funding-comparison-ui {:query ""
                                       :timeframe :8hour
                                       :sort {:column :coin
                                              :direction :asc}}
               :funding-comparison {:predicted-fundings
                                    [["KAITO"
                                      [["HlPerp" {:fundingRate "0.0000125"}]
                                       ["BinPerp" {:fundingRate "0.0002"}]
                                       ["BybitPerp" {:fundingRate "0.0002"}]]]]}
               :asset-selector {:favorites #{}
                                :market-by-key {"perp:KAITO" {:coin "KAITO"
                                                               :openInterest "10"}}}}
        row (first (:rows (vm/funding-comparison-vm state)))]
    ;; Binance KAITO defaults to 4h fallback: 0.0002 / 4 * 8 = 0.0004
    (is (= 4.0e-4 (get-in row [:binance :rate])))
    ;; Bybit KAITO defaults to 1h fallback: 0.0002 / 1 * 8 = 0.0016
    (is (= 0.0016 (get-in row [:bybit :rate])))))

(deftest funding-comparison-vm-favorites-sort-before-column-order-test
  (let [state {:funding-comparison-ui {:query ""
                                       :timeframe :8hour
                                       :sort {:column :coin
                                              :direction :asc}}
               :funding-comparison {:predicted-fundings
                                    [["AAA" [["HlPerp" {:fundingRate "0.00001"}]]]
                                     ["ZZZ" [["HlPerp" {:fundingRate "0.00001"}]]]]}
               :asset-selector {:favorites #{"perp:ZZZ"}
                                :market-by-key {"perp:AAA" {:coin "AAA" :openInterest 1}
                                                "perp:ZZZ" {:coin "ZZZ" :openInterest 1}}}}
        rows (:rows (vm/funding-comparison-vm state))]
    (is (= ["ZZZ" "AAA"] (mapv :coin rows)))))

(deftest funding-comparison-vm-search-and-timeframe-scaling-test
  (let [state {:funding-comparison-ui {:query "eth"
                                       :timeframe :day
                                       :sort {:column :coin
                                              :direction :asc}}
               :funding-comparison {:predicted-fundings
                                    [["ETH"
                                      [["HlPerp" {:fundingRate "0.0000125"
                                                   :fundingIntervalHours 1}]]]
                                     ["BTC"
                                      [["HlPerp" {:fundingRate "0.0000125"
                                                   :fundingIntervalHours 1}]]]]}
               :asset-selector {:favorites #{}
                                :market-by-key {"perp:ETH" {:coin "ETH" :openInterest 10}
                                                "perp:BTC" {:coin "BTC" :openInterest 20}}}}
        rows (:rows (vm/funding-comparison-vm state))]
    (is (= 1 (count rows)))
    (is (= "ETH" (:coin (first rows))))
    ;; Day multiplier is 24 for hourly-normalized rates.
    (is (= (* 24 0.0000125)
           (get-in (first rows) [:hyperliquid :rate])))))
