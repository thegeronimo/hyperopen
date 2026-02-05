(ns hyperopen.views.asset-selector-view-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.asset-selector-view :as view]))

(def sample-markets
  [{:key "perp:BTC"
    :symbol "BTC-USDC"
    :coin "BTC"
    :base "BTC"
    :market-type :perp
    :category :crypto
    :hip3? false
    :mark 1
    :volume24h 10
    :change24hPct 1}
   {:key "perp:xyz:GOLD"
    :symbol "GOLD-USDC"
    :coin "xyz:GOLD"
    :base "GOLD"
    :market-type :perp
    :category :tradfi
    :hip3? true
    :mark 2
    :volume24h 20
    :change24hPct 2}
   {:key "spot:PURR/USDC"
    :symbol "PURR/USDC"
    :coin "PURR/USDC"
    :base "PURR"
    :market-type :spot
    :category :spot
    :hip3? false
    :mark 0.5
    :volume24h 5
    :change24hPct -1}])

(deftest filter-and-sort-assets-test
  (testing "strict search filters by prefix"
    (let [results (view/filter-and-sort-assets sample-markets "bt" :name :asc #{} false true :all)]
      (is (= 1 (count results)))
      (is (= "BTC-USDC" (:symbol (first results))))))

  (testing "favorites-only filter"
    (let [results (view/filter-and-sort-assets sample-markets "" :name :asc #{"perp:BTC"} true false :all)]
      (is (= 1 (count results)))
      (is (= "perp:BTC" (:key (first results))))))

  (testing "tab filter for spot"
    (let [results (view/filter-and-sort-assets sample-markets "" :name :asc #{} false false :spot)]
      (is (= 1 (count results)))
      (is (= :spot (:market-type (first results)))))))
