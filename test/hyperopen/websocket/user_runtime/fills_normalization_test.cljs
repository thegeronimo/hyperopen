(ns hyperopen.websocket.user-runtime.fills-normalization-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.websocket.user-runtime.fills :as fill-runtime]))

(deftest normalized-fill-row-preserves-alias-precedence-and-guard-contract-test
  (let [normalized-fill-row @#'fill-runtime/normalized-fill-row]
    (is (= {:coin "BTC"
            :display-coin "BTC"
            :id "9001"
            :side :buy
            :size 1.5
            :qty 1.5
            :symbol "BTC"
            :price 43000
            :orderType "market"
            :ts 1700000000000
            :slippagePct 0.35}
           (normalized-fill-row {:coin " btc "
                                 :symbol "ETH"
                                 :asset "SOL"
                                 :side "B"
                                 :dir "sell"
                                 :sz "-1.5"
                                 :size "9"
                                 :filledSz "8"
                                 :filled "7"
                                 :px "43000"
                                 :price "1"
                                 :fillPx "2"
                                 :avgPx "3"
                                 :time "1700000000000"
                                 :timestamp "4"
                                 :ts "5"
                                 :t "6"
                                 :tid 9001
                                 :fill-id "fallback-fill-id"
                                 :fillId "fallbackFillId"
                                 :id "fallback-id"
                                 :orderType " MARKET "
                                 :slippagePct "0.35"
                                 :slippage "0.5"})))
    (is (= {:coin "XYZ:GOLD"
            :display-coin "GOLD"
            :id "XYZ:GOLD-sell-1700000000100-2-0"
            :side :sell
            :size 2
            :qty 2
            :symbol "GOLD"
            :price 0
            :orderType "limit"
            :ts 1700000000100
            :slippagePct -0.1}
           (normalized-fill-row {:symbol "xyz:GOLD"
                                 :direction "open short"
                                 :filled "2"
                                 :avgPx "0"
                                 :timestamp "1700000000100"
                                 :slippage-pct "-0.1"}
                                {"perp:xyz:GOLD" {:coin "xyz:GOLD"
                                                  :base "GOLD"
                                                  :market-type :perp}})))
    (is (nil? (normalized-fill-row {:coin " "
                                    :side "B"
                                    :sz "1"
                                    :px "1"})))
    (is (nil? (normalized-fill-row {:coin "BTC"
                                    :side "HOLD"
                                    :sz "1"
                                    :px "1"})))
    (is (nil? (normalized-fill-row {:coin "BTC"
                                    :side "B"
                                    :sz "not-a-number"
                                    :px "1"})))
    (is (nil? (normalized-fill-row {:coin "BTC"
                                    :side "B"
                                    :sz "0"
                                    :px "1"})))
    (is (nil? (normalized-fill-row {:coin "BTC"
                                    :side "B"
                                    :sz "1"
                                    :px "Infinity"})))))
