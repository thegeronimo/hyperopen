(ns hyperopen.domain.market.instrument-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.market.instrument :as instrument]))

(deftest base-and-quote-symbol-parsing-test
  (is (= "ETH" (instrument/base-symbol-from-value "ETH/USDC")))
  (is (= "USDC" (instrument/quote-symbol-from-value "ETH/USDC")))
  (is (= "BTC" (instrument/base-symbol-from-value "BTC-USDT")))
  (is (= "USDT" (instrument/quote-symbol-from-value "BTC-USDT")))
  (is (= "GOLD" (instrument/base-symbol-from-value "hyna:GOLD")))
  (is (nil? (instrument/quote-symbol-from-value "hyna:GOLD")))
  (is (= "BTC" (instrument/base-symbol-from-value "BTC")))
  (is (nil? (instrument/quote-symbol-from-value "BTC"))))

(deftest symbol-resolution-precedence-test
  (testing "market metadata has highest precedence when present"
    (let [market {:base "SOL" :quote "USDC" :coin "SOL-PERP"}]
      (is (= "SOL" (instrument/resolve-base-symbol "BTC" market)))
      (is (= "USDC" (instrument/resolve-quote-symbol "BTC" market)))))

  (testing "fallback defaults are used when symbols are unavailable"
    (is (= "Asset" (instrument/resolve-base-symbol nil nil)))
    (is (= "USDC" (instrument/resolve-quote-symbol nil nil)))))

(deftest spot-hip3-market-type-inference-test
  (testing "spot markets are inferred by market-type or slash instrument"
    (is (true? (instrument/spot-instrument? "ETH/USDC" {})))
    (is (true? (instrument/spot-instrument? "ETH" {:market-type :spot})))
    (is (= :spot (instrument/infer-market-type "ETH/USDC" {}))))

  (testing "canonical market-type takes precedence over slash heuristics"
    (is (false? (instrument/spot-instrument? "ETH/USDC" {:market-type :perp})))
    (is (= :perp (instrument/infer-market-type "ETH/USDC" {:market-type :perp}))))

  (testing "hip3 markets are inferred by dex or namespace style instruments"
    (is (true? (instrument/hip3-instrument? "hyna:GOLD" {})))
    (is (true? (instrument/hip3-instrument? "BTC" {:dex "dex-a"})))
    (is (false? (instrument/hip3-instrument? "ETH/USDC" {}))))

  (testing "perp market is the default inference"
    (is (= :perp (instrument/infer-market-type "BTC" {})))))

(deftest market-identity-output-test
  (let [spot-identity (instrument/market-identity "ETH/USDC" {})
        hip3-identity (instrument/market-identity "hyna:GOLD" {})
        perp-identity (instrument/market-identity "BTC" {:quote "USDC"})]
    (is (= "ETH" (:base-symbol spot-identity)))
    (is (= "USDC" (:quote-symbol spot-identity)))
    (is (true? (:spot? spot-identity)))
    (is (true? (:read-only? spot-identity)))

    (is (= "GOLD" (:base-symbol hip3-identity)))
    (is (true? (:hip3? hip3-identity)))
    (is (false? (:read-only? hip3-identity)))

    (is (= "BTC" (:base-symbol perp-identity)))
    (is (= "USDC" (:quote-symbol perp-identity)))
    (is (false? (:read-only? perp-identity)))))
