(ns hyperopen.views.account-info.tabs.open-orders.projection-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.tabs.open-orders :as open-orders-tab]))

(deftest normalized-open-orders-prefers-live-source-and-includes-dex-snapshots-test
  (let [live-orders [{:order {:coin "BTC" :oid 1 :side "B" :sz "1.0" :limitPx "100" :timestamp 1000}}]
        snapshot-orders [{:order {:coin "ETH" :oid 2 :side "A" :sz "2.0" :limitPx "200" :timestamp 900}}]
        snapshot-by-dex {:dex-a [{:order {:coin "SOL" :oid 3 :side "B" :sz "3.0" :limitPx "50" :timestamp 800}}]}
        with-live (projections/normalized-open-orders live-orders snapshot-orders snapshot-by-dex)
        without-live (projections/normalized-open-orders nil snapshot-orders snapshot-by-dex)]
    (is (= #{"1" "3"} (set (map :oid with-live))))
    (is (= #{"BTC" "SOL"} (set (map :coin with-live))))
    (is (= #{"2" "3"} (set (map :oid without-live))))
    (is (= #{"ETH" "SOL"} (set (map :coin without-live))))))

(deftest format-tp-sl-treats-reduce-only-take-profit-orders-as-position-tpsl-test
  (is (= "TP/SL"
         (open-orders-tab/format-tp-sl {:is-position-tpsl false
                                        :reduce-only true
                                        :type "Take Profit Market"})))
  (is (= "-- / --"
         (open-orders-tab/format-tp-sl {:is-position-tpsl false
                                        :reduce-only false
                                        :type "Take Profit Market"}))))
