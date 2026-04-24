(ns hyperopen.views.portfolio.optimize.view-test
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

(deftest portfolio-view-delegates-optimizer-index-route-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize"}})]
    (is (some? (node-by-role view-node "portfolio-optimizer-index")))
    (is (nil? (node-by-role view-node "portfolio-account-table")))
    (is (contains? (set (collect-strings view-node))
                   "Optimization Scenarios"))))

(deftest portfolio-view-delegates-optimizer-new-workspace-route-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :wallet {:address "0x1111111111111111111111111111111111111111"}
                    :webdata2 {:clearinghouseState {:marginSummary {:accountValue "100"}}}})]
    (is (some? (node-by-role view-node "portfolio-optimizer-workspace")))
    (is (some? (node-by-role view-node "portfolio-optimizer-left-rail")))
    (is (some? (node-by-role view-node "portfolio-optimizer-objective-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-return-model-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-risk-model-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-constraints-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-signed-exposure-table")))
    (let [strings (set (collect-strings view-node))]
      (is (contains? strings "Minimum Variance"))
      (is (contains? strings "Historical Mean"))
      (is (contains? strings "Ledoit-Wolf"))
      (is (contains? strings "Max Asset Weight"))
      (is (contains? strings "Gross Leverage"))
      (is (contains? strings "Rebalance Tolerance")))))

(deftest portfolio-view-delegates-optimizer-scenario-route-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/scn_01"}})
        workspace (node-by-role view-node "portfolio-optimizer-workspace")]
    (is (= "scn_01" (get-in workspace [1 :data-scenario-id])))
    (is (contains? (set (collect-strings view-node))
                   "Scenario scn_01"))))
