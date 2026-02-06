(ns hyperopen.views.active-asset-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.active-asset-view :as view]))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings node)
    (seq? node) (mapcat collect-strings node)
    :else []))

(deftest active-asset-row-symbol-fallback-test
  (let [ctx-data {:coin "SOL"
                  :mark 87.0
                  :oracle 86.9
                  :change24h 1.2
                  :change24hPct 1.4
                  :volume24h 1000
                  :openInterest 100
                  :fundingRate 0.001}
        ;; Simulates malformed/partial market state missing display fields.
        market {:market-type :perp}
        view-node (view/active-asset-row ctx-data market {:visible-dropdown nil} {:asset-selector {:missing-icons #{}}})
        strings (set (collect-strings view-node))]
    (is (contains? strings "SOL"))))
