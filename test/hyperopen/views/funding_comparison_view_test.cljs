(ns hyperopen.views.funding-comparison-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.funding-comparison-view :as view]))

(defn- node-children [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- find-first-node [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(def sample-state
  {:funding-comparison-ui {:query ""
                           :timeframe :8hour
                           :sort {:column :coin
                                  :direction :asc}}
   :funding-comparison {:predicted-fundings
                        [["BTC"
                          [["HlPerp" {:fundingRate "0.0000125" :fundingIntervalHours 1}]
                           ["BinPerp" {:fundingRate "0.0001" :fundingIntervalHours 8}]
                           ["BybitPerp" {:fundingRate "0.0001" :fundingIntervalHours 8}]]]]
                        :error nil
                        :loaded-at-ms 1700000000000}
   :asset-selector {:favorites #{"perp:BTC"}
                    :market-by-key {"perp:BTC" {:coin "BTC"
                                                 :openInterest 2500000}}}})

(deftest funding-comparison-view-renders-shell-controls-and-table-test
  (let [view-node (view/funding-comparison-view sample-state)
        root (find-first-node view-node #(= "funding-comparison-root" (get-in % [1 :data-parity-id])))
        search-input (find-first-node view-node #(= "funding-comparison-search" (get-in % [1 :id])))
        table-node (find-first-node view-node #(= "funding-comparison-table" (get-in % [1 :data-role])))
        row-node (find-first-node view-node #(= "funding-comparison-row" (get-in % [1 :data-role])))
        all-text (set (collect-strings view-node))]
    (is (some? root))
    (is (some? search-input))
    (is (some? table-node))
    (is (some? row-node))
    (is (contains? all-text "Funding Comparison"))
    (is (contains? all-text "Hyperliquid OI"))
    (is (contains? all-text "Binance-HL Arb"))
    (is (contains? all-text "Bybit-HL Arb"))))

(deftest funding-comparison-view-renders-coin-link-and-timeframe-actions-test
  (let [view-node (view/funding-comparison-view sample-state)
        coin-link (find-first-node view-node
                                   (fn [node]
                                     (and (= :a (first node))
                                          (= "/trade/BTC" (get-in node [1 :href])))))
        day-button (find-first-node view-node
                                    (fn [node]
                                      (and (= :button (first node))
                                           (contains? (set (collect-strings node)) "Day"))))]
    (is (some? coin-link))
    (is (= [[:actions/set-funding-comparison-timeframe :day]]
           (get-in day-button [1 :on :click])))))

(deftest funding-comparison-view-shows-error-banner-when-error-present-test
  (let [view-node (view/funding-comparison-view
                   (assoc-in sample-state [:funding-comparison :error] "Network issue"))
        error-node (find-first-node view-node #(= "funding-comparison-error" (get-in % [1 :data-role])))
        text (set (collect-strings error-node))]
    (is (some? error-node))
    (is (contains? text "Network issue"))))
