(ns hyperopen.views.hip3-display-names-test
  "Hyperliquid display names (`xyz:HO` -> DIESEL) on the trade-page surfaces
   that read them off the enriched market: positions, the order form, the
   order book and the chart legend."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.state.trading :as trading-state]
            [hyperopen.views.account-info.positions-vm :as positions-vm]
            [hyperopen.views.account-info.vm :as account-info-vm]
            [hyperopen.views.l2-orderbook.model :as l2-model]
            [hyperopen.views.trade.order-form-summary-display :as summary-display]
            [hyperopen.views.trading-chart.derived-cache :as chart-derived-cache]
            [hyperopen.views.trading-chart.vm :as chart-vm]))

(def ^:private diesel-market
  {:key "perp:xyz:HO"
   :coin "xyz:HO"
   :symbol "HO-USDC"
   :base "HO"
   :quote "USDC"
   :dex "xyz"
   :market-type :perp
   :display-name "DIESEL"
   :display-symbol "DIESEL-USDC"
   :mark 4.67})

(defn- positions-state
  [market-by-key]
  {:account-info {:selected-tab :positions}
   :asset-selector {:market-by-key market-by-key}
   :webdata2 {:clearinghouseState {:assetPositions []}}
   :perp-dex-clearinghouse {"xyz" {:assetPositions [{:position {:coin "xyz:HO"
                                                                :szi "-12"
                                                                :entryPx "4.70"
                                                                :positionValue "56.0"
                                                                :unrealizedPnl "0.3"
                                                                :returnOnEquity "0.01"
                                                                :marginUsed "5.6"
                                                                :cumFunding {:allTime "0"}}}]}}
   :orders {:open-orders []
            :open-orders-snapshot []
            :open-orders-snapshot-by-dex {}
            :order-history []}
   :spot {:meta nil :clearinghouse-state nil}
   :account {:mode :classic}})

(deftest positions-carry-and-show-the-market-display-name-test
  (let [row (first (:positions (account-info-vm/account-info-vm
                                (positions-state {"perp:xyz:HO" diesel-market}))))
        row-vm (positions-vm/position-row-vm row)]
    (is (= "xyz:HO" (get-in row [:position :coin])))
    (is (= "DIESEL" (get-in row [:position :display-name])))
    (is (= "DIESEL" (:coin-label row-vm)))
    (is (= "xyz" (:dex-label row-vm)))
    (is (= "12 DIESEL" (positions-vm/format-position-size row)))
    (testing "the coin filter matches the display name and the wire coin"
      (is (some #{"diesel"} (:normalized-coin-search-candidates row-vm)))
      (is (some #{"ho"} (:normalized-coin-search-candidates row-vm))))))

(deftest positions-fall-back-to-coin-base-without-a-display-name-test
  (let [row (first (:positions (account-info-vm/account-info-vm
                                (positions-state {"perp:xyz:HO" (dissoc diesel-market :display-name)}))))]
    (is (nil? (get-in row [:position :display-name])))
    (is (= "HO" (:coin-label (positions-vm/position-row-vm row))))))

(deftest order-form-current-position-uses-display-name-test
  (let [summary {:current-position {:coin "xyz:HO" :abs-size 12}}]
    (is (= "12.0000 DIESEL"
           (:current-position (summary-display/summary-display summary 4 "DIESEL"))))
    (is (= "12.0000 xyz:HO"
           (:current-position (summary-display/summary-display summary 4))))
    (is (= "0.0000 DIESEL"
           (summary-display/format-position-label nil 4 "DIESEL")))
    (is (= "0.0000 --"
           (summary-display/format-position-label nil 4 "")))))

(deftest order-form-market-info-exposes-display-name-test
  (let [info (trading-state/market-info {:active-asset "xyz:HO"
                                         :active-market diesel-market})]
    (is (= "DIESEL" (:display-name info)))
    (testing "the trading base symbol is still the coin-derived one"
      (is (= "HO" (:base-symbol info))))))

(deftest orderbook-size-unit-uses-display-name-test
  (is (= "DIESEL" (l2-model/resolve-base-symbol "xyz:HO" diesel-market)))
  (is (= "HO" (l2-model/resolve-base-symbol "xyz:HO" (dissoc diesel-market :display-name)))))

(defn- chart-state
  [active-asset active-market]
  {:active-asset active-asset
   :active-market active-market
   :asset-selector {:market-by-key {}}
   :candles {}
   :chart-options {:selected-timeframe :1d
                   :selected-chart-type :candlestick
                   :active-indicators {}
                   :volume-visible? true}
   :orders {:open-orders [] :fills []}
   :trading-settings {:show-chart-trades? false}})

(deftest chart-legend-shows-display-name-but-keys-on-the-coin-test
  (chart-derived-cache/reset-derived-cache!)
  (binding [chart-derived-cache/*process-candle-data* (fn [_] [])]
    (let [legend (:legend-meta (chart-vm/chart-view-model (chart-state "xyz:HO" diesel-market)
                                                          (fn [& _] nil)))]
      (is (= "xyz:HO" (:symbol legend)))
      (is (= "xyz:DIESEL" (:display-symbol legend))))
    (testing "a stale active market from the previous asset is ignored"
      (let [legend (:legend-meta (chart-vm/chart-view-model (chart-state "BTC" diesel-market)
                                                            (fn [& _] nil)))]
        (is (= "BTC" (:symbol legend)))
        (is (nil? (:display-symbol legend)))))))

(deftest chart-legend-shows-the-spot-pair-not-its-wire-coin-test
  (chart-derived-cache/reset-derived-cache!)
  (binding [chart-derived-cache/*process-candle-data* (fn [_] [])]
    (let [hype {:key "spot:@107" :coin "@107" :symbol "HYPE/USDC" :base "HYPE" :quote "USDC"
                :market-type :spot :dex nil}
          legend (:legend-meta (chart-vm/chart-view-model (chart-state "@107" hype) (fn [& _] nil)))]
      (is (= "@107" (:symbol legend)) "the chart stays keyed on the coin")
      (is (= "HYPE/USDC" (:display-symbol legend))))
    (testing "a perp without a display name keeps its coin"
      (let [btc {:key "perp:BTC" :coin "BTC" :symbol "BTC-USDC" :market-type :perp}
            legend (:legend-meta (chart-vm/chart-view-model (chart-state "BTC" btc) (fn [& _] nil)))]
        (is (= "BTC" (:symbol legend)))
        (is (nil? (:display-symbol legend)))))))
