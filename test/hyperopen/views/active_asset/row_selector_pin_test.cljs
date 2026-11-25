(ns hyperopen.views.active-asset.row-selector-pin-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.active-asset.row :as row]
            [hyperopen.views.active-asset.test-support :as support]))

(deftest active-asset-row-pins-market-selector-while-statistics-scroll-test
  (let [ctx-data {:coin "SOL"
                  :mark 87.0
                  :oracle 86.9
                  :change24h 1.2
                  :change24hPct 1.4
                  :volume24h 1000
                  :openInterest 100
                  :fundingRate 0.001}
        market {:coin "SOL"
                :symbol "SOL"
                :base "SOL"
                :market-type :perp}
        view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} {:asset-selector {:missing-icons #{}}})
        statistics-scroll (support/find-node-by-role view-node "active-asset-statistics-scroll")
        selector-cell (support/find-node-by-role statistics-scroll "active-asset-selector-cell")
        cell-classes (set (get-in selector-cell [1 :class]))]
    (is (some? selector-cell))
    (is (contains? cell-classes "sticky"))
    (is (contains? cell-classes "left-0"))
    (is (contains? cell-classes "bg-base-200"))
    (is (contains? cell-classes "asset-strip-pinned-cell"))))

(deftest statistics-scroll-shows-focus-ring-only-for-keyboard-focus-test
  (let [view-node (row/active-asset-row {:coin "SOL" :mark 87.0 :oracle 86.9 :change24h 1.2 :change24hPct 1.4
                                         :volume24h 1000 :openInterest 100 :fundingRate 0.001}
                                        {:coin "SOL" :symbol "SOL" :base "SOL" :market-type :perp}
                                        {:visible-dropdown nil}
                                        {:asset-selector {:missing-icons #{}}})
        classes (set (get-in (support/find-node-by-role view-node "active-asset-statistics-scroll") [1 :class]))]
    (is (contains? classes "focus:outline-none"))
    (is (contains? classes "focus-visible:ring-1"))
    (is (empty? (filter #(re-find #"^focus:ring" %) classes)))))
