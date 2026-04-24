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
                                         :risk-model {:kind :ledoit-wolf}
                                         :constraints {:long-only? true}}
                                 :history-data {:candle-history-by-coin
                                                {"BTC" [{:time 1000 :close "100"}
                                                        {:time 2000 :close "110"}]}
                                                :funding-history-by-coin {}}
                                 :runtime {:as-of-ms 2500
                                           :stale-after-ms 5000}}}})
        run-button (node-by-role view-node "portfolio-optimizer-run-draft")
        strings (set (collect-strings view-node))]
    (is (= true (get-in run-button [1 :disabled])))
    (is (contains? strings "Reload history before running this changed universe."))
    (is (contains? strings "missing-candle-history"))))
