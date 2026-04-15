(ns hyperopen.asset-selector.markets-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.markets :as markets]))

(deftest build-perp-markets-test
  (testing "build-perp-markets builds symbols and dex correctly"
    (let [meta {:universe [{:name "BTC" :maxLeverage 40 :szDecimals 5}
                           {:name "hyna:ETH"
                            :maxLeverage 25
                            :szDecimals 4
                            :onlyIsolated true
                            :marginMode "noCross"
                            :isDelisted false
                            :growthMode "enabled"}]
                :collateralToken 0}
          asset-ctxs [{:markPx "100" :prevDayPx "90" :dayNtlVlm "1000" :openInterest "2" :funding "0.0001"}
                      {:markPx "200" :prevDayPx "100" :dayNtlVlm "500" :openInterest "6000" :funding "-0.0002"}]
          token-map {0 "USDC"}
          default-markets (markets/build-perp-markets meta asset-ctxs token-map)
          hyna-markets (markets/build-perp-markets (assoc meta :collateralToken 235)
                                                   asset-ctxs
                                                   (assoc token-map 235 "USDE")
                                                   :dex "hyna"
                                                   :perp-dex-index 1)]
      (is (= "BTC-USDC" (:symbol (first default-markets))))
      (is (= "perp:BTC" (:key (first default-markets))))
      (is (= 5 (:szDecimals (first default-markets))))
      (is (= 0 (:perp-dex-index (first default-markets))))
      (is (= 0 (:asset-id (first default-markets))))
      (is (= "100" (:markRaw (first default-markets))))
      (is (= "90" (:prevDayRaw (first default-markets))))
      (is (nil? (:margin-mode (first default-markets))))
      (is (nil? (:only-isolated? (first default-markets))))
      (is (false? (:growth-mode? (first default-markets))))
      (is (false? (:hip3-eligible? (first default-markets))))
      (is (= "ETH-USDE" (:symbol (second hyna-markets))))
      (is (= 4 (:szDecimals (second hyna-markets))))
      (is (= "hyna" (:dex (second hyna-markets))))
      (is (= :no-cross (:margin-mode (second hyna-markets))))
      (is (true? (:only-isolated? (second hyna-markets))))
      (is (true? (:growth-mode? (second hyna-markets))))
      (is (= 1 (:perp-dex-index (second hyna-markets))))
      (is (= 110001 (:asset-id (second hyna-markets))))
      (is (false? (:delisted? (second hyna-markets))))
      (is (true? (:hip3-eligible? (second hyna-markets)))))))

(deftest build-perp-markets-fallbacks-test
  (testing "build-perp-markets skips missing ctxs and falls back to meta-derived defaults"
    (let [meta {:universe [{:name "xyz:OIL" :maxLeverage 20 :szDecimals 3}
                           {:name "BTC" :maxLeverage 40 :szDecimals 5}]
                :collateralToken 99
                :perpDexIndex "2"}
          asset-ctxs [{:markPx "50" :prevDayPx "0" :dayNtlVlm "1500" :openInterest "10" :funding "0.001"}]
          [market] (markets/build-perp-markets meta asset-ctxs {})]
      (is (= 1 (count (markets/build-perp-markets meta asset-ctxs {}))))
      (is (= "perp:xyz:OIL" (:key market)))
      (is (= "OIL-USDC" (:symbol market)))
      (is (= "xyz" (:dex market)))
      (is (= 2 (:perp-dex-index market)))
      (is (= 120000 (:asset-id market)))
      (is (= 50 (:change24h market)))
      (is (nil? (:change24hPct market)))
      (is (nil? (:margin-mode market)))
      (is (nil? (:only-isolated? market))))))

(deftest build-spot-markets-test
  (testing "build-spot-markets maps base/quote and symbol"
    (let [spot-meta {:tokens [{:index 0 :name "USDC" :szDecimals 8}
                              {:index 1 :name "PURR" :szDecimals 0}
                              {:index 2 :name "HYPE" :szDecimals 2}
                              {:index 3 :name "USDT" :szDecimals 6}]
                     :universe [{:name "PURR/USDC" :index 0 :tokens [1 0]}
                                {:name "@107" :index 1 :tokens [2 0]}
                                {:name "USDT/USDC" :index 2 :tokens [3 0]}]}
          spot-ctxs [{:markPx "0.5" :prevDayPx "0.4" :dayNtlVlm "100"}
                     {:markPx "10" :prevDayPx "9" :dayNtlVlm "250"}
                     {:markPx "1.0" :prevDayPx "1.0" :dayNtlVlm "500"}]
          markets (markets/build-spot-markets spot-meta spot-ctxs)
          purr-market (first markets)
          hype-market (second markets)
          stable-market (nth markets 2)]
      (is (= "PURR/USDC" (:symbol purr-market)))
      (is (= "PURR" (:base purr-market)))
      (is (= "USDC" (:quote purr-market)))
      (is (false? (:stable-pair? purr-market)))
      (is (= 0 (:szDecimals purr-market)))
      (is (= 0 (:asset-id purr-market)))
      (is (= :spot (:market-type purr-market)))
      (is (= "0.5" (:markRaw purr-market)))
      (is (= "0.4" (:prevDayRaw purr-market)))
      (is (= "HYPE/USDC" (:symbol hype-market)))
      (is (= "HYPE" (:base hype-market)))
      (is (= "USDC" (:quote hype-market)))
      (is (= 2 (:szDecimals hype-market)))
      (is (false? (:stable-pair? hype-market)))
      (is (= 1 (:asset-id hype-market)))
      (is (= "USDT/USDC" (:symbol stable-market)))
      (is (true? (:stable-pair? stable-market))))))

(deftest build-spot-markets-token-id-lookup-test
  (let [spot-meta {:tokens [{:index 0 :tokenId 100 :name "USDC" :szDecimals 6}
                            {:index 1 :tokenId 101 :name "ABC" :szDecimals 2}]
                   :universe [{:name "@500" :index 0 :tokens [101 100]}]}
        spot-ctxs [{:markPx "1.5" :prevDayPx "1.0" :dayNtlVlm "10"}]
        market (first (markets/build-spot-markets spot-meta spot-ctxs))]
    (is (= "@500" (:coin market)))
    (is (= "ABC/USDC" (:symbol market)))
    (is (= "ABC" (:base market)))
    (is (= "USDC" (:quote market)))
    (is (= 2 (:szDecimals market)))))

(deftest classify-market-test
  (testing "classify-market assigns crypto/tradfi/hip3"
    (let [default (markets/classify-market {:market-type :perp :dex nil :openInterest 5000000})
          hyna (markets/classify-market {:market-type :perp :dex "hyna" :openInterest 1500000 :delisted? false})
          xyz (markets/classify-market {:market-type :perp :dex "xyz" :openInterest 1500000 :delisted? true})
          spot (markets/classify-market {:market-type :spot :dex nil})]
      (is (= :crypto (:category default)))
      (is (false? (:hip3? default)))
      (is (false? (:hip3-eligible? default)))
      (is (= :crypto (:category hyna)))
      (is (true? (:hip3? hyna)))
      (is (true? (:hip3-eligible? hyna)))
      (is (= :tradfi (:category xyz)))
      (is (true? (:hip3? xyz)))
      (is (false? (:hip3-eligible? xyz)))
      (is (= :spot (:category spot))))))

(deftest coin->market-key-spot-id-test
  (testing "spot ids prefixed with @ are treated as spot keys"
    (is (= "spot:@1" (markets/coin->market-key "@1")))
    (is (= "spot:PURR/USDC" (markets/coin->market-key "PURR/USDC")))
    (is (= "perp:BTC" (markets/coin->market-key "BTC")))))

(deftest resolve-market-by-coin-test
  (testing "resolve-market-by-coin handles perp, spot pair, spot id, numeric legacy ids, and spot base fallback"
    (let [market-by-key {"perp:BTC" {:key "perp:BTC" :coin "BTC"}
                         "spot:PURR/USDC" {:key "spot:PURR/USDC" :coin "PURR/USDC"}
                         "spot:@1" {:key "spot:@1" :coin "@1"}
                         "spot:MEOW/USDT" {:key "spot:MEOW/USDT"
                                           :coin "MEOW/USDT"
                                           :base "MEOW"
                                           :quote "USDT"
                                           :market-type :spot}
                         "spot:MEOW/USDC" {:key "spot:MEOW/USDC"
                                           :coin "MEOW/USDC"
                                           :base "MEOW"
                                           :quote "USDC"
                                           :market-type :spot}}]
      (is (= "perp:BTC" (:key (markets/resolve-market-by-coin market-by-key "BTC"))))
      (is (= "spot:PURR/USDC" (:key (markets/resolve-market-by-coin market-by-key "PURR/USDC"))))
      (is (= "spot:@1" (:key (markets/resolve-market-by-coin market-by-key "@1"))))
      (is (= "spot:@1" (:key (markets/resolve-market-by-coin market-by-key "1"))))
      (is (= "spot:MEOW/USDC" (:key (markets/resolve-market-by-coin market-by-key "MEOW"))))
      (is (nil? (markets/resolve-market-by-coin market-by-key "hyna:MEOW")))))
  (testing "resolve-market-by-coin rejects non-scalar coin inputs"
    (let [market-by-key {"perp:BTC" {:key "perp:BTC" :coin "BTC"}}]
      (is (nil? (markets/resolve-market-by-coin market-by-key {:coin "BTC"})))
      (is (nil? (markets/resolve-market-by-coin market-by-key ["BTC"])))
      (is (nil? (markets/resolve-market-by-coin market-by-key #js {:coin "BTC"}))))))

(deftest resolve-or-infer-market-by-coin-test
  (testing "resolve-or-infer-market-by-coin keeps lookup behavior for known markets"
    (let [market-by-key {"perp:BTC" {:key "perp:BTC"
                                     :coin "BTC"
                                     :market-type :perp}}]
      (is (= {:key "perp:BTC"
              :coin "BTC"
              :market-type :perp}
             (markets/resolve-or-infer-market-by-coin market-by-key "BTC")))))

  (testing "resolve-or-infer-market-by-coin infers a minimal namespaced perp market for active-market consumers"
    (is (= {:key "perp:hyna:MEOW"
            :coin "hyna:MEOW"
            :symbol "MEOW-USDC"
            :base "MEOW"
            :quote "USDC"
            :market-type :perp
            :dex "hyna"
            :category :crypto
            :hip3? true
            :hip3-eligible? false}
           (markets/resolve-or-infer-market-by-coin {} "hyna:MEOW")))))

(deftest market-matches-coin-test
  (testing "base-symbol matching still allows an unnamespaced active asset to match a namespaced market"
    (is (true? (markets/market-matches-coin? {:coin "xyz:BRENTOIL"
                                              :dex "xyz"
                                              :base "BRENTOIL"}
                                             "BRENTOIL"))))

  (testing "a namespaced active asset does not accept a stale market from another dex on base-symbol equality alone"
    (is (false? (markets/market-matches-coin? {:coin "hyna:GOLD"
                                               :dex "hyna"
                                               :base "GOLD"}
                                              "xyz:GOLD"))))

  (testing "a namespaced active asset still matches the same dex market"
    (is (true? (markets/market-matches-coin? {:coin "xyz:GOLD"
                                              :dex "xyz"
                                              :base "GOLD"}
                                             "xyz:GOLD")))))
