(ns hyperopen.websocket.asset-selector-coverage-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.market-live-projection :as market-live-projection]
            [hyperopen.asset-selector.markets :as markets]))

(deftest ws-asset-selector-market-key-and-candidate-keys-test
  (is (= "perp:BTC" (markets/market-key {:market-type :perp :coin "BTC"})))
  (is (= "spot:PURR/USDC" (markets/coin->market-key "PURR/USDC")))
  (is (= "spot:@1" (markets/coin->market-key "@1")))
  (is (= "perp:ETH" (markets/coin->market-key "ETH")))
  (is (= [] (markets/candidate-market-keys nil)))
  (is (= [] (markets/candidate-market-keys "")))
  (is (= ["spot:@7" "spot:7" "perp:7"] (markets/candidate-market-keys "7")))
  (is (= ["spot:@7" "perp:@7"] (markets/candidate-market-keys "@7")))
  (is (= ["spot:PURR/USDC" "perp:PURR/USDC"] (markets/candidate-market-keys "PURR/USDC")))
  (is (= ["perp:BTC" "spot:BTC"] (markets/candidate-market-keys "BTC"))))

(deftest ws-asset-selector-resolve-market-by-coin-test
  (let [market-by-key {"perp:BTC" {:key "perp:BTC" :coin "BTC" :market-type :perp}
                       "spot:PURR/USDC" {:key "spot:PURR/USDC" :coin "PURR/USDC" :market-type :spot}
                       "spot:@1" {:key "spot:@1" :coin "@1" :market-type :spot}
                       "spot:MEOW/USDT" {:key "spot:MEOW/USDT"
                                         :coin "MEOW/USDT"
                                         :market-type :spot
                                         :base "MEOW"
                                         :quote "USDT"}
                       "spot:MEOW/USDC" {:key "spot:MEOW/USDC"
                                         :coin "MEOW/USDC"
                                         :market-type :spot
                                         :base "MEOW"
                                         :quote "USDC"}}]
    (is (= "perp:BTC" (:key (markets/resolve-market-by-coin market-by-key "BTC"))))
    (is (= "spot:PURR/USDC" (:key (markets/resolve-market-by-coin market-by-key "PURR/USDC"))))
    (is (= "spot:@1" (:key (markets/resolve-market-by-coin market-by-key "1"))))
    (is (= "spot:MEOW/USDC" (:key (markets/resolve-market-by-coin market-by-key "meow"))))
    (is (nil? (markets/resolve-market-by-coin market-by-key "hyna:MEOW")))
    (is (nil? (markets/resolve-market-by-coin market-by-key "@MEOW")))
    (is (nil? (markets/resolve-market-by-coin market-by-key "MEOW/USDC:ALT")))
    (is (nil? (markets/resolve-market-by-coin [] "BTC")))
    (is (nil? (markets/resolve-market-by-coin market-by-key nil)))))

(deftest ws-asset-selector-build-perp-markets-branch-coverage-test
  (let [meta {:universe [{:name "hyna:SOL"
                          :maxLeverage 20
                          :szDecimals 3
                          :onlyIsolated "true"
                          :marginMode "strict_isolated"
                          :growthMode :enabled}
                         {:name "xyz:GOLD"
                          :maxLeverage 5
                          :szDecimals 1
                          :onlyIsolated false
                          :margin-mode :normal
                          :growthMode true}
                         {:name "BTC"
                          :maxLeverage 40
                          :szDecimals 5
                          :marginMode "unknown"
                          :growthMode "disabled"
                          :isDelisted true}
                         {:name "SKIP"
                          :maxLeverage 2
                          :szDecimals 2}]
              :collateralToken 0
              :perpDexIndex "2"}
        asset-ctxs [{:markPx "2" :prevDayPx "1" :dayNtlVlm "100" :openInterest "700000" :funding "0.001"}
                    {:markPx "20" :prevDayPx "0" :dayNtlVlm "200" :openInterest "100000" :funding "0.002"}
                    {:markPx "bad" :prevDayPx "bad" :dayNtlVlm nil :openInterest nil :funding nil}]
        token-map {0 "USDC"}
        markets (markets/build-perp-markets meta asset-ctxs token-map)
        sol (first markets)
        gold (second markets)
        btc (nth markets 2)
        unresolved-index-markets (markets/build-perp-markets {:universe [{:name "abc:ONE" :maxLeverage 1 :szDecimals 1}]
                                                              :collateralToken 999
                                                              :perpDexIndex "bad"}
                                                             [{:markPx "1" :prevDayPx "1" :dayNtlVlm "1" :openInterest "1" :funding "0"}]
                                                             {}
                                                             :dex "abc")
        unresolved (first unresolved-index-markets)]
    (is (= 3 (count markets)))
    (is (= "perp:hyna:SOL" (:key sol)))
    (is (= "SOL-USDC" (:symbol sol)))
    (is (= :crypto (:category sol)))
    (is (true? (:hip3? sol)))
    (is (true? (:hip3-eligible? sol)))
    (is (= :strict-isolated (:margin-mode sol)))
    (is (true? (:only-isolated? sol)))
    (is (true? (:growth-mode? sol)))
    (is (= 2 (:perp-dex-index sol)))
    (is (= 120000 (:asset-id sol)))
    (is (= "2" (:markRaw sol)))
    (is (= "1" (:prevDayRaw sol)))

    (is (= :tradfi (:category gold)))
    (is (true? (:hip3? gold)))
    (is (true? (:hip3-eligible? gold)))
    (is (= :normal (:margin-mode gold)))
    (is (false? (:only-isolated? gold)))
    (is (true? (:growth-mode? gold)))
    (is (nil? (:change24hPct gold)))

    (is (= "BTC-USDC" (:symbol btc)))
    (is (= :crypto (:category btc)))
    (is (false? (:hip3? btc)))
    (is (false? (:hip3-eligible? btc)))
    (is (false? (:growth-mode? btc)))
    (is (nil? (:margin-mode btc)))
    (is (nil? (:only-isolated? btc)))
    (is (zero? (:mark btc)))
    (is (zero? (:openInterest btc)))

    (is (= "abc" (:dex unresolved)))
    (is (nil? (:perp-dex-index unresolved)))
    (is (nil? (:asset-id unresolved)))))

(deftest ws-asset-selector-build-spot-markets-branch-coverage-test
  (let [spot-meta {:tokens [{:index 0 :tokenId 100 :name "USDC" :szDecimals 6}
                            {:index 1 :tokenId 101 :name "ABC" :szDecimals 2}
                            {:index 2 :tokenId 102 :name "USDH" :szDecimals 4}
                            {:index 3 :tokenId 103 :name "XYZ" :szDecimals 5}]
                   :universe [{:name "ABC/USDC" :index 0 :tokens [1 0]}
                              {:name "@9001" :index 1 :tokens ["3" "0"]}
                              {:name "USDH/USDC" :index 2 :tokens [2 0]}
                              {:name "LEGACY" :index 3 :tokens [999 100]}
                              {:name "NOCTX/USDC" :index 5 :tokens [1 0]}]}
        spot-ctxs [{:markPx "1.5" :prevDayPx "1.0" :dayNtlVlm "10"}
                   {:markPx "2" :prevDayPx "bad" :dayNtlVlm "20"}
                   {:markPx "1.0" :prevDayPx "1.0" :dayNtlVlm "5"}
                   {:markPx nil :prevDayPx nil :dayNtlVlm nil}]
        markets (markets/build-spot-markets spot-meta spot-ctxs)
        abc (first markets)
        xyz (second markets)
        stable (nth markets 2)
        legacy (nth markets 3)]
    (is (= 4 (count markets)))
    (is (= "ABC/USDC" (:symbol abc)))
    (is (= 2 (:szDecimals abc)))
    (is (false? (:stable-pair? abc)))
    (is (= :spot (:category abc)))

    (is (= "XYZ/USDC" (:symbol xyz)))
    (is (= "XYZ" (:base xyz)))
    (is (= "USDC" (:quote xyz)))
    (is (= 5 (:szDecimals xyz)))
    (is (nil? (:change24hPct xyz)))

    (is (= "USDH/USDC" (:symbol stable)))
    (is (true? (:stable-pair? stable)))

    (is (= "LEGACY/USDC" (:symbol legacy)))
    (is (= "LEGACY" (:base legacy)))
    (is (= "USDC" (:quote legacy)))
    (is (nil? (:szDecimals legacy)))
    (is (zero? (:mark legacy)))
    (is (zero? (:volume24h legacy)))))

(deftest ws-asset-selector-market-live-projection-branch-coverage-test
  (testing "numeric coin updates every matching selector market key"
    (let [perp-market {:key "perp:7"
                       :coin "7"
                       :market-type :perp
                       :mark 1
                       :volume24h 10
                       :fundingRate 0.9
                       :openInterest 99}
          spot-market {:key "spot:@7"
                       :coin "@7"
                       :market-type :spot
                       :mark 1
                       :volume24h 10
                       :fundingRate 0.8
                       :openInterest 88}
          state {:asset-selector {:markets [perp-market spot-market]
                                  :market-by-key {"perp:7" perp-market
                                                  "spot:@7" spot-market}}}
          next-state (market-live-projection/apply-active-asset-ctx-update
                      state
                      "7"
                      {:markPx "5"
                       :prevDayPx "0"
                       :dayNtlVlm "not-a-number"
                       :funding "not-a-number"
                       :openInterest "3"})]
      (is (= 5 (get-in next-state [:asset-selector :market-by-key "perp:7" :mark])))
      (is (= "5" (get-in next-state [:asset-selector :market-by-key "perp:7" :markRaw])))
      (is (= "0" (get-in next-state [:asset-selector :market-by-key "perp:7" :prevDayRaw])))
      (is (nil? (get-in next-state [:asset-selector :market-by-key "perp:7" :change24h])))
      (is (nil? (get-in next-state [:asset-selector :market-by-key "perp:7" :change24hPct])))
      (is (= 15 (get-in next-state [:asset-selector :market-by-key "perp:7" :openInterest])))
      (is (= 0.9 (get-in next-state [:asset-selector :market-by-key "perp:7" :fundingRate])))
      (is (= 10 (get-in next-state [:asset-selector :market-by-key "perp:7" :volume24h])))

      (is (= 5 (get-in next-state [:asset-selector :market-by-key "spot:@7" :mark])))
      (is (nil? (get-in next-state [:asset-selector :market-by-key "spot:@7" :openInterest])))
      (is (nil? (get-in next-state [:asset-selector :market-by-key "spot:@7" :fundingRate])))))

  (testing "invalid inputs and unknown markets are no-ops"
    (let [state {:asset-selector {:markets []
                                  :market-by-key {}}}]
      (is (= state (market-live-projection/apply-active-asset-ctx-update state nil {:markPx "1"})))
      (is (= state (market-live-projection/apply-active-asset-ctx-update state "BTC" nil)))
      (is (= state (market-live-projection/apply-active-asset-ctx-update state "BTC" {:markPx "1"})))))

  (testing "non-sequential selector markets normalize to an empty vector"
    (let [market {:key "perp:BTC"
                  :coin "BTC"
                  :market-type :perp
                  :mark 100}
          state {:asset-selector {:markets {:unexpected true}
                                  :market-by-key {"perp:BTC" market}}}
          next-state (market-live-projection/apply-active-asset-ctx-update
                      state
                      "BTC"
                      {:prevDayPx "90"})]
      (is (= [] (get-in next-state [:asset-selector :markets])))
      (is (= "90" (get-in next-state [:asset-selector :market-by-key "perp:BTC" :prevDayRaw])))
      (is (= 100 (get-in next-state [:asset-selector :market-by-key "perp:BTC" :mark]))))))
