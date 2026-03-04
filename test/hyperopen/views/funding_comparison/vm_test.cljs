(ns hyperopen.views.funding-comparison.vm-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.views.funding-comparison.vm :as vm]))

(defn- reset-funding-comparison-vm-cache-fixture
  [f]
  (vm/reset-funding-comparison-vm-cache!)
  (f)
  (vm/reset-funding-comparison-vm-cache!))

(use-fixtures :each reset-funding-comparison-vm-cache-fixture)

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

(deftest funding-comparison-vm-column-order-takes-precedence-over-favorites-test
  (let [state {:funding-comparison-ui {:query ""
                                       :timeframe :8hour
                                       :sort {:column :coin
                                              :direction :asc}}
               :funding-comparison {:predicted-fundings
                                    [["AAA" [["HlPerp" {:fundingRate "0.00001"}]
                                             ["BinPerp" {:fundingRate "0.00008"
                                                         :fundingIntervalHours 8}]]]
                                     ["ZZZ" [["HlPerp" {:fundingRate "0.00001"}]
                                             ["BinPerp" {:fundingRate "0.00008"
                                                         :fundingIntervalHours 8}]]]]}
               :asset-selector {:favorites #{"perp:ZZZ"}
                                :market-by-key {"perp:AAA" {:coin "AAA" :openInterest 1}
                                                "perp:ZZZ" {:coin "ZZZ" :openInterest 1}}}}
        rows (:rows (vm/funding-comparison-vm state))]
    (is (= ["AAA" "ZZZ"] (mapv :coin rows)))))

(deftest funding-comparison-vm-search-and-timeframe-scaling-test
  (let [state {:funding-comparison-ui {:query "eth"
                                       :timeframe :day
                                       :sort {:column :coin
                                              :direction :asc}}
               :funding-comparison {:predicted-fundings
                                    [["ETH"
                                      [["HlPerp" {:fundingRate "0.0000125"
                                                   :fundingIntervalHours 1}]
                                       ["BinPerp" {:fundingRate "0.0001"
                                                    :fundingIntervalHours 8}]]]
                                     ["BTC"
                                      [["HlPerp" {:fundingRate "0.0000125"
                                                   :fundingIntervalHours 1}]
                                       ["BinPerp" {:fundingRate "0.0001"
                                                    :fundingIntervalHours 8}]]]]}
               :asset-selector {:favorites #{}
                                :market-by-key {"perp:ETH" {:coin "ETH" :openInterest 10}
                                                "perp:BTC" {:coin "BTC" :openInterest 20}}}}
        rows (:rows (vm/funding-comparison-vm state))]
    (is (= 1 (count rows)))
    (is (= "ETH" (:coin (first rows))))
    ;; Day multiplier is 24 for hourly-normalized rates.
    (is (= (* 24 0.0000125)
           (get-in (first rows) [:hyperliquid :rate])))))

(deftest funding-comparison-vm-filters-rows-when-both-cex-rates-missing-test
  (let [state {:funding-comparison-ui {:query ""
                                       :timeframe :8hour
                                       :sort {:column :coin
                                              :direction :asc}}
               :funding-comparison {:predicted-fundings
                                    [["NOPE" [["HlPerp" {:fundingRate "0.00001"}]]]
                                     ["YES" [["HlPerp" {:fundingRate "0.00001"}]
                                             ["BybitPerp" {:fundingRate "0.00008"
                                                           :fundingIntervalHours 8}]]]]}
               :asset-selector {:favorites #{}
                                :market-by-key {"perp:NOPE" {:coin "NOPE" :openInterest 1}
                                                "perp:YES" {:coin "YES" :openInterest 1}}}}
        rows (:rows (vm/funding-comparison-vm state))]
    (is (= ["YES"] (mapv :coin rows)))))

(deftest funding-comparison-vm-sorts-arb-by-signed-diff-not-absolute-test
  (let [state {:funding-comparison-ui {:query ""
                                       :timeframe :8hour
                                       :sort {:column :binance-hl-arb
                                              :direction :desc}}
               :funding-comparison {:predicted-fundings
                                    [["POS" [["HlPerp" {:fundingRate "0.0001" :fundingIntervalHours 1}]
                                             ["BinPerp" {:fundingRate "0.0012" :fundingIntervalHours 8}]]]
                                     ["NEG" [["HlPerp" {:fundingRate "0.0001" :fundingIntervalHours 1}]
                                             ["BinPerp" {:fundingRate "0.00008" :fundingIntervalHours 8}]]]]}
               :asset-selector {:favorites #{}
                                :market-by-key {"perp:POS" {:coin "POS" :openInterest 1}
                                                "perp:NEG" {:coin "NEG" :openInterest 1}}}}
        rows (:rows (vm/funding-comparison-vm state))]
    (is (= ["POS" "NEG"] (mapv :coin rows)))
    (is (< 0 (get-in (first rows) [:binance-hl-arb :raw-diff])))
    (is (> 0 (get-in (second rows) [:binance-hl-arb :raw-diff])))))

(deftest funding-comparison-vm-memoizes-large-parse-filter-sort-pipeline-test
  (let [row-count 240
        predicted-fundings
        (mapv (fn [idx]
                (let [coin (str "COIN" idx)]
                  [coin
                   [["HlPerp" {:fundingRate "0.0000125" :fundingIntervalHours 1}]
                    ["BinPerp" {:fundingRate "0.0001" :fundingIntervalHours 8}]
                    ["BybitPerp" {:fundingRate "0.00008" :fundingIntervalHours 4}]]]))
              (range row-count))
        market-by-key
        (into {}
              (map (fn [idx]
                     (let [coin (str "COIN" idx)]
                       [(str "perp:" coin) {:coin coin
                                            :openInterest (+ 1000 idx)}]))
                   (range row-count)))
        base-state {:funding-comparison-ui {:query ""
                                            :timeframe :8hour
                                            :sort {:column :open-interest
                                                   :direction :desc}}
                    :funding-comparison {:predicted-fundings predicted-fundings}
                    :asset-selector {:favorites #{}
                                     :market-by-key market-by-key}}
        parse-calls (atom 0)
        filter-calls (atom 0)
        sort-calls (atom 0)
        parse* vm/*parse-predicted-row*
        filter* vm/*has-cex-funding-rate?*
        sort* vm/*sort-rows*]
    (with-redefs [vm/*parse-predicted-row*
                  (fn [row]
                    (swap! parse-calls inc)
                    (parse* row))
                  vm/*has-cex-funding-rate?*
                  (fn [row]
                    (swap! filter-calls inc)
                    (filter* row))
                  vm/*sort-rows*
                  (fn [rows sort-state]
                    (swap! sort-calls inc)
                    (sort* rows sort-state))]
      (let [first-result (vm/funding-comparison-vm base-state)]
        (is (= row-count (:row-count first-result))))
      (vm/funding-comparison-vm base-state)
      (is (= row-count @parse-calls))
      (is (= row-count @filter-calls))
      (is (= 1 @sort-calls))

      (let [churned-state (assoc-in base-state
                                    [:funding-comparison :predicted-fundings]
                                    (into [] predicted-fundings))]
        (vm/funding-comparison-vm churned-state))
      (is (= row-count @parse-calls))
      (is (= row-count @filter-calls))
      (is (= 1 @sort-calls))

      (let [favorites-state (assoc-in base-state
                                      [:asset-selector :favorites]
                                      #{"perp:COIN42"})]
        (vm/funding-comparison-vm favorites-state))
      (is (= (* 2 row-count) @parse-calls))
      (is (= (* 2 row-count) @filter-calls))
      (is (= 2 @sort-calls)))))
