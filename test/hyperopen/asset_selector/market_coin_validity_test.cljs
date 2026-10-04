(ns hyperopen.asset-selector.market-coin-validity-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.markets :as markets]))

(deftest bare-spot-token-market-test
  (let [market-by-key {"perp:HYPE" {:key "perp:HYPE" :market-type :perp :coin "HYPE"}
                       "spot:@336" {:key "spot:@336" :market-type :spot :coin "@336"
                                    :base "KHYPE" :quote "USDC"}
                       "spot:@250" {:key "spot:@250" :market-type :spot :coin "@250"
                                    :base "KHYPE" :quote "USDH"}
                       "spot:HYPE/USDC" {:key "spot:HYPE/USDC" :market-type :spot
                                         :coin "HYPE/USDC" :base "HYPE" :quote "USDC"}}]
    (testing "a bare spot-only token name maps to its USDC spot pair"
      (is (= "@336" (:coin (markets/bare-spot-token-market market-by-key "KHYPE")))))
    (testing "coins that are already market coins are left alone"
      (is (nil? (markets/bare-spot-token-market market-by-key "HYPE")))
      (is (nil? (markets/bare-spot-token-market market-by-key "@336")))
      (is (nil? (markets/bare-spot-token-market market-by-key "HYPE/USDC"))))
    (testing "unknown tokens and a perp-only catalog resolve nothing"
      (is (nil? (markets/bare-spot-token-market market-by-key "NOPE")))
      (is (nil? (markets/bare-spot-token-market {"perp:HYPE" {:key "perp:HYPE" :coin "HYPE"}}
                                                "KHYPE"))))))

(deftest unknown-market-coin-test
  (let [market-by-key {"perp:BTC" {:key "perp:BTC" :market-type :perp :coin "BTC"}
                       "perp:xyz:TSLA" {:key "perp:xyz:TSLA" :market-type :perp
                                        :coin "xyz:TSLA" :dex "xyz"}
                       "spot:@336" {:key "spot:@336" :market-type :spot :coin "@336"
                                    :base "KHYPE" :quote "USDC"}}]
    (testing "coins the catalog resolves are known"
      (is (false? (markets/unknown-market-coin? market-by-key "BTC")))
      (is (false? (markets/unknown-market-coin? market-by-key "xyz:TSLA")))
      (is (false? (markets/unknown-market-coin? market-by-key "@336")))
      (is (false? (markets/unknown-market-coin? market-by-key "KHYPE"))))
    (testing "unresolvable coins are unknown"
      (is (true? (markets/unknown-market-coin? market-by-key "FOOBAR")))
      (is (true? (markets/unknown-market-coin? market-by-key "xyz:FOOBAR")))
      (is (true? (markets/unknown-market-coin? market-by-key "@99999"))))
    (testing "a dex absent from the catalog, or an empty catalog, condemns nothing"
      (is (false? (markets/unknown-market-coin? market-by-key "abc:FOO")))
      (is (false? (markets/unknown-market-coin? {} "FOOBAR")))
      (is (false? (markets/unknown-market-coin? market-by-key nil))))))
