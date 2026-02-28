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
                                :market-by-key {"perp:BTC" market}}}
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
    (is (= 333 (get-in next-state [:asset-selector :markets 0 :volume24h])))))

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
                                :market-by-key {"spot:PURR/USDC" spot-market}}}
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
                                  :market-by-key {}}}]
      (is (= state
             (market-live-projection/apply-active-asset-ctx-update
              state
              "BTC"
              {:markPx "1"}))))))
