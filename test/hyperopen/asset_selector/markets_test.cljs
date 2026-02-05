(ns hyperopen.asset-selector.markets-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.markets :as markets]))

(deftest build-perp-markets-test
  (testing "build-perp-markets builds symbols and dex correctly"
    (let [meta {:universe [{:name "BTC" :maxLeverage 40}
                           {:name "hyna:ETH" :maxLeverage 25}]
                :collateralToken 0}
          asset-ctxs [{:markPx "100" :prevDayPx "90" :dayNtlVlm "1000" :openInterest "2" :funding "0.0001"}
                      {:markPx "200" :prevDayPx "100" :dayNtlVlm "500" :openInterest "1" :funding "-0.0002"}]
          token-map {0 "USDC"}
          default-markets (markets/build-perp-markets meta asset-ctxs token-map)
          hyna-markets (markets/build-perp-markets (assoc meta :collateralToken 235)
                                                   asset-ctxs
                                                   (assoc token-map 235 "USDE")
                                                   :dex "hyna")]
      (is (= "BTC-USDC" (:symbol (first default-markets))))
      (is (= "perp:BTC" (:key (first default-markets))))
      (is (= "ETH-USDE" (:symbol (second hyna-markets))))
      (is (= "hyna" (:dex (second hyna-markets)))))))

(deftest build-spot-markets-test
  (testing "build-spot-markets maps base/quote and symbol"
    (let [spot-meta {:universe [{:name "PURR/USDC" :index 0}]}
          spot-ctxs [{:markPx "0.5" :prevDayPx "0.4" :dayNtlVlm "100"}]
          markets (markets/build-spot-markets spot-meta spot-ctxs)
          market (first markets)]
      (is (= "PURR/USDC" (:symbol market)))
      (is (= "PURR" (:base market)))
      (is (= "USDC" (:quote market)))
      (is (= :spot (:market-type market))))))

(deftest classify-market-test
  (testing "classify-market assigns crypto/tradfi/hip3"
    (let [default (markets/classify-market {:market-type :perp :dex nil})
          hyna (markets/classify-market {:market-type :perp :dex "hyna"})
          xyz (markets/classify-market {:market-type :perp :dex "xyz"})
          spot (markets/classify-market {:market-type :spot :dex nil})]
      (is (= :crypto (:category default)))
      (is (false? (:hip3? default)))
      (is (= :crypto (:category hyna)))
      (is (true? (:hip3? hyna)))
      (is (= :tradfi (:category xyz)))
      (is (true? (:hip3? xyz)))
      (is (= :spot (:category spot))))))
