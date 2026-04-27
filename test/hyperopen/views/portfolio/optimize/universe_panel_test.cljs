(ns hyperopen.views.portfolio.optimize.universe-panel-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio-view :as portfolio-view]))

(defn- node-children
  [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- find-first-node
  [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- collect-strings
  [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- node-by-role
  [node role]
  (find-first-node node #(= role (get-in % [1 :data-role]))))

(defn- click-actions
  [node]
  (get-in node [1 :on :click]))

(defn- input-actions
  [node]
  (get-in node [1 :on :input]))

(deftest portfolio-optimizer-workspace-supports-manual-universe-builder-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio-ui {:optimizer {:universe-search-query "eth"}}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}]
                                         :constraints {:long-only? false}}}}
                    :asset-selector
                    {:markets [{:key "perp:BTC"
                                :market-type :perp
                                :coin "BTC"
                                :symbol "BTC-USDC"}
                               {:key "perp:ETH"
                                :market-type :perp
                                :coin "ETH"
                                :symbol "ETH-USDC"
                                :dex "hl"}
                               {:key "spot:PURR/USDC"
                                :market-type :spot
                                :coin "PURR/USDC"
                                :symbol "PURR/USDC"}]
                     :market-by-key {"perp:BTC" {:key "perp:BTC"
                                                 :market-type :perp
                                                 :coin "BTC"
                                                 :symbol "BTC-USDC"}
                                     "perp:ETH" {:key "perp:ETH"
                                                 :market-type :perp
                                                 :coin "ETH"
                                                 :symbol "ETH-USDC"
                                                 :dex "hl"}
                                     "spot:PURR/USDC" {:key "spot:PURR/USDC"
                                                       :market-type :spot
                                                       :coin "PURR/USDC"
                                                       :symbol "PURR/USDC"}}}})
        strings (set (collect-strings view-node))]
    (is (= "eth"
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-universe-search-input")
                   [1 :value])))
    (is (= [[:actions/set-portfolio-optimizer-universe-search-query
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-universe-search-input"))))
    (is (= [[:actions/add-portfolio-optimizer-universe-instrument "perp:ETH"]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-universe-add-perp:ETH"))))
    (is (= [[:actions/remove-portfolio-optimizer-universe-instrument "perp:BTC"]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-universe-remove-perp:BTC"))))
    (is (nil? (node-by-role view-node
                            "portfolio-optimizer-universe-add-perp:BTC")))
    (is (contains? strings "Manual Add"))
    (is (contains? strings "ETH-USDC"))
    (is (contains? strings "Requires history reload after adding new assets."))))

(deftest portfolio-optimizer-selected-universe-prefers-symbol-for-raw-spot-and-hip3-assets-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "spot:@107"
                                                     :market-type :spot
                                                     :coin "@107"
                                                     :symbol "UBTC/USDC"
                                                     :base "UBTC"
                                                     :quote "USDC"}
                                                    {:instrument-id "perp:xyz:@221"
                                                     :market-type :perp
                                                     :coin "@221"
                                                     :dex "xyz"
                                                     :hip3? true
                                                     :symbol "GOLD-USDC"
                                                     :base "GOLD"
                                                     :quote "USDC"}]
                                         :constraints {:long-only? false}}}}})
        strings (set (collect-strings view-node))]
    (is (contains? strings "UBTC/USDC"))
    (is (contains? strings "UBTC"))
    (is (contains? strings "GOLD-USDC"))
    (is (contains? strings "GOLD"))
    (is (not (contains? strings "@107")))
    (is (not (contains? strings "@221")))))

(deftest portfolio-optimizer-search-results-prefer-symbols-for-raw-spot-assets-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio-ui {:optimizer {:universe-search-query "hype"}}
                    :portfolio {:optimizer {:draft {:universe []
                                                     :constraints {:long-only? false}}}}
                    :asset-selector
                    {:markets [{:key "perp:HYPE"
                                :market-type :perp
                                :coin "HYPE"
                                :symbol "HYPE-USDC"
                                :name "Hyperliquid"}
                               {:key "spot:@107"
                                :market-type :spot
                                :coin "@107"
                                :symbol "HYPE/USDC"
                                :base "HYPE"
                                :quote "USDC"}
                               {:key "spot:@232"
                                :market-type :spot
                                :coin "@232"
                                :symbol "HYPE/USDH"
                                :base "HYPE"
                                :quote "USDH"}]
                     :market-by-key {"spot:@107" {:key "spot:@107"
                                                  :market-type :spot
                                                  :coin "@107"
                                                  :symbol "HYPE/USDC"
                                                  :base "HYPE"
                                                  :quote "USDC"}}}})
        strings (set (collect-strings view-node))]
    (is (contains? strings "HYPE-USDC"))
    (is (contains? strings "HYPE/USDC"))
    (is (contains? strings "HYPE/USDH"))
    (is (contains? strings "Hyperliquid"))
    (is (not (contains? strings "@107")))
    (is (not (contains? strings "@232")))))

(deftest portfolio-optimizer-workspace-blocks-run-when-retained-history-misses-assets-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}
                                                    {:instrument-id "perp:ETH"
                                                     :market-type :perp
                                                     :coin "ETH"}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? true}}
                                 :history-data {:candle-history-by-coin
                                                {"BTC" [{:time 1000 :close "100"}
                                                        {:time 2000 :close "110"}]}
                                                :funding-history-by-coin {}}
                                 :runtime {:as-of-ms 2500
                                           :stale-after-ms 5000}}}})
        run-button (node-by-role view-node "portfolio-optimizer-run-draft")
        strings (set (collect-strings view-node))]
    (is (= false (get-in run-button [1 :disabled])))
    (is (contains? strings "Run Optimization will refresh history for this changed universe."))
    (is (contains? strings "missing-candle-history"))))
