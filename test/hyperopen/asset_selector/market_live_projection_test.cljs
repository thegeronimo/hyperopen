(ns hyperopen.asset-selector.market-live-projection-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.market-live-projection :as market-live-projection]))

(deftest apply-active-asset-ctx-update-patches-perp-market-columns-test
  (let [market {:key "perp:BTC"
                :coin "BTC"
                :symbol "BTC-USDC"
                :market-type :perp
                :mark 99
                :markRaw "99"
                :change24h 1
                :change24hPct 1
                :volume24h 100
                :fundingRate 0
                :openInterest 0}
        state {:asset-selector {:markets [market]
                                :market-by-key {"perp:BTC" market}
                                :market-index-by-key {"perp:BTC" 0}}}
        next-state (market-live-projection/apply-active-asset-ctx-update
                    state
                    "BTC"
                    {:markPx "101.5"
                     :prevDayPx "95"
                     :funding "0.0002"
                     :dayNtlVlm "333"
                     :openInterest "444"})]
    (is (= 101.5 (get-in next-state [:asset-selector :market-by-key "perp:BTC" :mark])))
    (is (= 6.5 (get-in next-state [:asset-selector :market-by-key "perp:BTC" :change24h])))
    (is (< (js/Math.abs (- 6.842105263157895
                           (get-in next-state [:asset-selector :market-by-key "perp:BTC" :change24hPct])))
           1.0e-12))
    (is (= 0.0002 (get-in next-state [:asset-selector :market-by-key "perp:BTC" :fundingRate])))
    (is (= 45066 (get-in next-state [:asset-selector :market-by-key "perp:BTC" :openInterest])))
    (is (= 333 (get-in next-state [:asset-selector :markets 0 :volume24h])))
    (is (= {"perp:BTC" 0} (get-in next-state [:asset-selector :market-index-by-key])))))

(deftest apply-active-asset-ctx-update-patches-only-targeted-selector-index-test
  (let [btc-market {:key "perp:BTC"
                    :coin "BTC"
                    :market-type :perp
                    :mark 100}
        eth-market {:key "perp:ETH"
                    :coin "ETH"
                    :market-type :perp
                    :mark 200}
        state {:asset-selector {:markets [btc-market eth-market]
                                :market-by-key {"perp:BTC" btc-market
                                                "perp:ETH" eth-market}
                                :market-index-by-key {"perp:BTC" 0
                                                      "perp:ETH" 1}}}
        next-state (market-live-projection/apply-active-asset-ctx-update
                    state
                    "BTC"
                    {:markPx "101"
                     :prevDayPx "100"})]
    (is (= 101 (get-in next-state [:asset-selector :markets 0 :mark])))
    (is (identical? eth-market (get-in next-state [:asset-selector :markets 1])))
    (is (not (identical? btc-market (get-in next-state [:asset-selector :markets 0]))))))

(deftest apply-active-asset-ctx-update-keeps-spot-funding-and-open-interest-empty-test
  (let [spot-market {:key "spot:PURR/USDC"
                     :coin "PURR/USDC"
                     :symbol "PURR/USDC"
                     :market-type :spot
                     :mark 0.5
                     :markRaw "0.5"
                     :fundingRate nil
                     :openInterest nil}
        state {:asset-selector {:markets [spot-market]
                                :market-by-key {"spot:PURR/USDC" spot-market}
                                :market-index-by-key {"spot:PURR/USDC" 0}}}
        next-state (market-live-projection/apply-active-asset-ctx-update
                    state
                    "PURR/USDC"
                    {:markPx "0.75"
                     :prevDayPx "0.5"
                     :funding "0.0004"
                     :openInterest "100"
                     :dayNtlVlm "88"})]
    (is (= 0.75 (get-in next-state [:asset-selector :market-by-key "spot:PURR/USDC" :mark])))
    (is (= 50 (get-in next-state [:asset-selector :market-by-key "spot:PURR/USDC" :change24hPct])))
    (is (nil? (get-in next-state [:asset-selector :market-by-key "spot:PURR/USDC" :fundingRate])))
    (is (nil? (get-in next-state [:asset-selector :market-by-key "spot:PURR/USDC" :openInterest]))))

  (testing "unknown coins are a no-op"
    (let [state {:asset-selector {:markets []
                                  :market-by-key {}
                                  :market-index-by-key {}}}]
      (is (= state
             (market-live-projection/apply-active-asset-ctx-update
              state
              "BTC"
              {:markPx "1"}))))))

(deftest apply-active-asset-ctx-update-refreshes-outcome-open-interest-from-active-context-test
  (let [outcome-market {:key "outcome:0"
                        :coin "#0"
                        :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                        :market-type :outcome
                        :mark 0.58
                        :markRaw "0.58"
                        :openInterest 537233
                        :fundingRate nil
                        :outcome-sides [{:coin "#0"
                                         :side-index 0}
                                        {:coin "#1"
                                         :side-index 1}]}
        state {:asset-selector {:markets [outcome-market]
                                :market-by-key {"outcome:0" outcome-market}
                                :market-index-by-key {"outcome:0" 0}}}
        next-state (market-live-projection/apply-active-asset-ctx-update
                    state
                    "#0"
                    {:markPx "0.6"
                     :prevDayPx "0.5"
                     :openInterest "276502"
                     :dayNtlVlm "1200"})]
    (is (= 0.6 (get-in next-state [:asset-selector :market-by-key "outcome:0" :mark])))
    (is (= 0.6 (get-in next-state [:asset-selector :market-by-key "outcome:0" :outcome-sides 0 :mark])))
    (is (= 276502 (get-in next-state [:asset-selector :market-by-key "outcome:0" :openInterest])))
    (is (nil? (get-in next-state [:asset-selector :market-by-key "outcome:0" :fundingRate])))))

(deftest apply-active-asset-ctx-update-patches-outcome-side-without-clobbering-primary-side-test
  (let [outcome-market {:key "outcome:0"
                        :coin "#0"
                        :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                        :market-type :outcome
                        :mark 0.58
                        :markRaw "0.58"
                        :openInterest 537233
                        :fundingRate nil
                        :outcome-sides [{:coin "#0"
                                         :side-index 0
                                         :mark 0.58
                                         :markRaw "0.58"}
                                        {:coin "#1"
                                         :side-index 1
                                         :mark 0.42
                                         :markRaw "0.42"}]}
        state {:asset-selector {:markets [outcome-market]
                                :market-by-key {"outcome:0" outcome-market}
                                :market-index-by-key {"outcome:0" 0}}}
        next-state (market-live-projection/apply-active-asset-ctx-update
                    state
                    "#1"
                    {:markPx "0.46790"
                     :prevDayPx "0.45"
                     :openInterest "276502"
                     :dayNtlVlm "1200"})]
    (is (= 0.58 (get-in next-state [:asset-selector :market-by-key "outcome:0" :mark])))
    (is (= "0.58" (get-in next-state [:asset-selector :market-by-key "outcome:0" :markRaw])))
    (is (= 0.46790 (get-in next-state [:asset-selector :market-by-key "outcome:0" :outcome-sides 1 :mark])))
    (is (= "0.46790" (get-in next-state [:asset-selector :market-by-key "outcome:0" :outcome-sides 1 :markRaw])))
    (is (= 0.46790 (get-in next-state [:asset-selector :markets 0 :outcome-sides 1 :mark])))))

(deftest apply-active-asset-ctx-update-hydrates-outcome-open-interest-from-circulating-supply-test
  (let [outcome-market {:key "outcome:0"
                        :coin "#0"
                        :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                        :market-type :outcome
                        :mark 0.58
                        :outcome-sides [{:coin "#0"
                                         :side-index 0}
                                        {:coin "#1"
                                         :side-index 1}]}
        state {:asset-selector {:markets [outcome-market]
                                :market-by-key {"outcome:0" outcome-market}
                                :market-index-by-key {"outcome:0" 0}}}
        next-state (market-live-projection/apply-active-asset-ctx-update
                    state
                    "#0"
                    {:markPx "0.63796"
                     :prevDayPx "0.55"
                     :circulatingSupply "567696.0"
                     :dayNtlVlm "1403439.9898999992"})]
    (is (= 0.63796 (get-in next-state [:asset-selector :market-by-key "outcome:0" :mark])))
    (is (= 567696.0 (get-in next-state [:asset-selector :market-by-key "outcome:0" :openInterest])))
    (is (= 567696.0 (get-in next-state [:asset-selector :markets 0 :openInterest])))))
