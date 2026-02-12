(ns hyperopen.asset-selector.markets-cache-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.asset-selector.markets-cache :as markets-cache]))

(deftest build-asset-selector-markets-cache-sorts-and-normalizes-test
  (let [markets [{:key "perp:ETH"
                  :coin "ETH"
                  :symbol "ETH-USDC"
                  :base "ETH"
                  :quote "USDC"
                  :market-type :perp
                  :volume24h 1000
                  :mark 1900.1}
                 {:key "spot:PURR/USDC"
                  :coin "PURR/USDC"
                  :symbol "PURR/USDC"
                  :base "PURR"
                  :quote "USDC"
                  :market-type :spot
                  :volume24h 2000
                  :mark 0.21}]
        state {:asset-selector {:sort-by :volume
                                :sort-direction :desc}}
        cached (markets-cache/build-asset-selector-markets-cache markets state)]
    (is (= ["PURR/USDC" "ETH-USDC"] (mapv :symbol cached)))
    (is (= [:spot :perp] (mapv :market-type cached)))
    (is (= [0 1] (mapv :cache-order cached)))
    (is (nil? (:mark (first cached))))))

(deftest restore-asset-selector-markets-cache-state-hydrates-when-empty-test
  (let [cached-markets [{:key "perp:ETH"
                         :coin "ETH"
                         :symbol "ETH-USDC"
                         :base "ETH"
                         :market-type :perp}
                        {:key "spot:PURR/USDC"
                         :coin "PURR/USDC"
                         :symbol "PURR/USDC"
                         :base "PURR"
                         :market-type :spot}]
        state {:active-asset "ETH"
               :active-market nil
               :asset-selector {:markets []
                                :market-by-key {}
                                :phase :bootstrap}}
        result (markets-cache/restore-asset-selector-markets-cache-state
                state
                cached-markets
                markets/resolve-market-by-coin)]
    (is (= cached-markets (get-in result [:asset-selector :markets])))
    (is (= "ETH" (get-in result [:active-market :coin])))
    (is (= true (get-in result [:asset-selector :cache-hydrated?])))))

(deftest restore-asset-selector-markets-cache-state-keeps-existing-markets-test
  (let [existing-market {:key "perp:BTC"
                         :coin "BTC"
                         :symbol "BTC-USDC"
                         :base "BTC"
                         :market-type :perp}
        state {:asset-selector {:markets [existing-market]
                                :market-by-key {"perp:BTC" existing-market}
                                :phase :full}}
        result (markets-cache/restore-asset-selector-markets-cache-state
                state
                [{:key "perp:ETH" :coin "ETH" :symbol "ETH-USDC" :base "ETH"}]
                markets/resolve-market-by-coin)]
    (is (= state result))))
