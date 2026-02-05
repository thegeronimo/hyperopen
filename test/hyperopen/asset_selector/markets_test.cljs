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
      (is (= "100" (:markRaw (first default-markets))))
      (is (= "90" (:prevDayRaw (first default-markets))))
      (is (= "ETH-USDE" (:symbol (second hyna-markets))))
      (is (= "hyna" (:dex (second hyna-markets)))))))

(deftest build-spot-markets-test
  (testing "build-spot-markets maps base/quote and symbol"
    (let [spot-meta {:tokens [{:index 0 :name "USDC"}
                              {:index 1 :name "PURR"}
                              {:index 2 :name "HYPE"}]
                     :universe [{:name "PURR/USDC" :index 0 :tokens [1 0]}
                                {:name "@107" :index 1 :tokens [2 0]}]}
          spot-ctxs [{:markPx "0.5" :prevDayPx "0.4" :dayNtlVlm "100"}
                     {:markPx "10" :prevDayPx "9" :dayNtlVlm "250"}]
          markets (markets/build-spot-markets spot-meta spot-ctxs)
          purr-market (first markets)
          hype-market (second markets)]
      (is (= "PURR/USDC" (:symbol purr-market)))
      (is (= "PURR" (:base purr-market)))
      (is (= "USDC" (:quote purr-market)))
      (is (= :spot (:market-type purr-market)))
      (is (= "0.5" (:markRaw purr-market)))
      (is (= "0.4" (:prevDayRaw purr-market)))
      (is (= "HYPE/USDC" (:symbol hype-market)))
      (is (= "HYPE" (:base hype-market)))
      (is (= "USDC" (:quote hype-market))))))

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
