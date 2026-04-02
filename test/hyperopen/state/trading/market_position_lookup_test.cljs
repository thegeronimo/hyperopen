(ns hyperopen.state.trading.market-position-lookup-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.state.trading :as trading]))

(deftest position-for-market-matches-exact-and-case-normalized-coins-test
  (testing "exact coin matches are returned from the active clearinghouse"
    (let [state {:active-asset "BTC"
                 :active-market {:coin "BTC"}
                 :webdata2 {:clearinghouseState {:assetPositions [{:position {:coin "BTC"
                                                                               :szi "1"}}]}}}
          position (trading/position-for-market state "BTC" (:active-market state))]
      (is (= "BTC" (:coin position)))
      (is (= "1" (:szi position)))))

  (testing "case-only differences still resolve to the same live position"
    (let [state {:active-asset "BTC"
                 :active-market {:coin "BTC"}
                 :webdata2 {:clearinghouseState {:assetPositions [{:position {:coin "btc"
                                                                               :szi "2"}}]}}}
          position (trading/position-for-market state "BTC" (:active-market state))]
      (is (= "btc" (:coin position)))
      (is (= "2" (:szi position))))))

(deftest position-for-market-matches-namespaced-and-base-symbol-coins-test
  (testing "namespaced markets can match a base-symbol position coin"
    (let [state {:active-asset "xyz:GOLD"
                 :active-market {:coin "xyz:GOLD"
                                 :dex "xyz"
                                 :market-type :perp}
                 :perp-dex-clearinghouse {"xyz" {:assetPositions [{:position {:coin "GOLD"
                                                                               :szi "3"}}]}}}
          position (trading/position-for-market state "xyz:GOLD" (:active-market state))]
      (is (= "GOLD" (:coin position)))
      (is (= "3" (:szi position)))))

  (testing "the compatibility wrapper resolves the market from selector state"
    (let [state {:active-asset "xyz:GOLD"
                 :active-market nil
                 :asset-selector {:market-by-key {"perp:xyz:GOLD" {:coin "xyz:GOLD"
                                                                    :dex "xyz"
                                                                    :market-type :perp}}}
                 :perp-dex-clearinghouse {"xyz" {:assetPositions [{:position {:coin "GOLD"
                                                                               :szi "4"}}]}}
                 :webdata2 {:clearinghouseState {:assetPositions [{:position {:coin "OTHER"
                                                                               :szi "1"}}]}}}
          position (trading/position-for-active-asset state)]
      (is (= "GOLD" (:coin position)))
      (is (= "4" (:szi position)))))

  (testing "the compatibility wrapper infers a namespaced market when selector metadata is still empty"
    (let [state {:active-asset "xyz:BRENTOIL"
                 :active-market nil
                 :asset-selector {:market-by-key {}}
                 :perp-dex-clearinghouse {"xyz" {:assetPositions [{:position {:coin "xyz:BRENTOIL"
                                                                               :szi "1.31"}}]}}
                 :webdata2 {:clearinghouseState {:assetPositions [{:position {:coin "OTHER"
                                                                               :szi "1"}}]}}}
          position (trading/position-for-active-asset state)]
      (is (= "xyz:BRENTOIL" (:coin position)))
      (is (= "1.31" (:szi position)))))

  (testing "the compatibility wrapper keeps a projected namespaced market when the active asset is the base symbol"
    (let [state {:active-asset "BRENTOIL"
                 :active-market {:coin "xyz:BRENTOIL"
                                 :dex "xyz"
                                 :market-type :perp}
                 :asset-selector {:market-by-key {}}
                 :perp-dex-clearinghouse {"xyz" {:assetPositions [{:position {:coin "BRENTOIL"
                                                                               :szi "1.31"}}]}}
                 :webdata2 {:clearinghouseState {:assetPositions [{:position {:coin "OTHER"
                                                                               :szi "1"}}]}}}
          position (trading/position-for-active-asset state)]
      (is (= "BRENTOIL" (:coin position)))
      (is (= "1.31" (:szi position)))))

  (testing "named-dex lookup tolerates case-only dex key differences and wrapped clearinghouse payloads"
    (let [state {:active-asset "BRENTOIL"
                 :active-market {:coin "xyz:BRENTOIL"
                                 :dex "xyz"
                                 :market-type :perp}
                 :perp-dex-clearinghouse {"XYZ" {:clearinghouseState {:assetPositions [{:position {:coin "BRENTOIL"
                                                                                                    :szi "1.31"}}]}}}}
          position (trading/position-for-active-asset state)]
      (is (= "BRENTOIL" (:coin position)))
      (is (= "1.31" (:szi position)))))

  (testing "current-position-summary uses the same normalized named-dex clearinghouse lookup"
    (let [state {:active-asset "BRENTOIL"
                 :active-market {:coin "xyz:BRENTOIL"
                                 :dex "xyz"
                                 :market-type :perp}
                 :perp-dex-clearinghouse {"XYZ" {:clearinghouseState {:assetPositions [{:position {:coin "BRENTOIL"
                                                                                                    :szi "1.31"
                                                                                                    :liquidationPx "95.5"}}]}}}}
          summary (trading/current-position-summary state)]
      (is (= "BRENTOIL" (:coin summary)))
      (is (= 1.31 (:size summary)))
      (is (= 1.31 (:abs-size summary)))
      (is (= :long (:direction summary)))
      (is (= 95.5 (:liquidation-price summary))))))
