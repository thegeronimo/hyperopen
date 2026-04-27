(ns hyperopen.views.portfolio.optimize.setup-v4-layout-test
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

(deftest setup-new-route-uses-v4-grid-instead-of-old-left-rail-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :wallet {:address "0x1111111111111111111111111111111111111111"}})
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-route-surface")))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-header")))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-preset-row")))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-surface")))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-control-rail")))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-summary-pane")))
    (is (some? (node-by-role view-node "portfolio-optimizer-assumptions-rail")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-trust-freshness-panel")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-left-rail")))
    (is (contains? strings "Optimizer · portfolio / optimize / new"))
    (is (contains? strings "Start with"))
    (is (contains? strings "From holdings"))
    (is (contains? strings "Custom"))
    (is (contains? strings "What this scenario will solve for"))
    (is (contains? strings "Why this preset is safe"))
    (is (not (contains? strings "Execution Assumptions")))))

(deftest setup-v4-layout-preserves-optimizer-control-actions-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? true
                                                       :max-asset-weight 0.25
                                                       :gross-max 2}}}}})
        run-button (node-by-role view-node "portfolio-optimizer-run-draft")]
    (is (= false (get-in run-button [1 :disabled])))
    (is (= [[:actions/run-portfolio-optimizer-from-draft]]
           (click-actions run-button)))
    (is (= [[:actions/set-portfolio-optimizer-universe-from-current]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-universe-use-current"))))
    (is (= [[:actions/set-portfolio-optimizer-universe-search-query
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-universe-search-input"))))
    (is (= [[:actions/set-portfolio-optimizer-objective-kind :max-sharpe]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-objective-max-sharpe"))))
    (is (= [[:actions/set-portfolio-optimizer-return-model-kind :black-litterman]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-return-model-black-litterman"))))
    (is (= [[:actions/set-portfolio-optimizer-risk-model-kind :sample-covariance]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-risk-model-sample-covariance"))))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :gross-max
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-gross-max-input"))))))

(deftest setup-v4-constraints-explain-each-control-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:constraints {:long-only? true
                                                       :max-asset-weight 0.25
                                                       :gross-max 3
                                                       :net-max 1.5
                                                       :dust-usdc 50
                                                       :max-turnover 1
                                                       :rebalance-tolerance 0.03}}}}})
        strings (set (collect-strings view-node))
        max-weight (node-by-role
                    view-node
                    "portfolio-optimizer-constraint-max-asset-weight-input")
        max-weight-tooltip (node-by-role
                            view-node
                            "portfolio-optimizer-constraint-max-asset-weight-input-tooltip")
        long-only (node-by-role
                   view-node
                   "portfolio-optimizer-constraint-long-only-input")
        long-only-tooltip (node-by-role
                           view-node
                           "portfolio-optimizer-constraint-long-only-tooltip")]
    (is (= "portfolio-optimizer-constraint-max-asset-weight-input-tooltip"
           (get-in max-weight [1 :aria-describedby])))
    (is (= "tooltip" (get-in max-weight-tooltip [1 :role])))
    (is (= "portfolio-optimizer-constraint-long-only-tooltip"
           (get-in long-only [1 :aria-describedby])))
    (is (= "tooltip" (get-in long-only-tooltip [1 :role])))
    (is (contains? strings
                   "Maximum target portfolio weight any single asset can receive. 0.25 means no asset can exceed 25%."))
    (is (contains? strings
                   "Maximum total absolute exposure across all legs. 3 means long exposure plus short exposure can total up to 300% of capital."))
    (is (contains? strings
                   "Small rebalance trades below this USDC notional are ignored so the output avoids noisy dust orders."))
    (is (contains? strings
                   "Minimum target-vs-current weight difference before a rebalance row is considered actionable. 0.03 means 3 percentage points."))))
