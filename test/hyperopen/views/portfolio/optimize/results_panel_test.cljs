(ns hyperopen.views.portfolio.optimize.results-panel-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.views.portfolio.optimize.results-panel :as results-panel]))

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

(defn- drag-start-actions
  [node]
  (get-in node [1 :on :drag-start]))

(defn- drag-enter-actions
  [node]
  (get-in node [1 :on :drag-enter]))

(def solved-result
  (fixtures/sample-solved-result
   {:instrument-ids ["perp:BTC" "spot:PURR"]
    :current-weights [0.2 0.1]
    :target-weights [0.35 -0.02]
    :target-weights-by-instrument {"perp:BTC" 0.35
                                   "spot:PURR" -0.02}
    :current-weights-by-instrument {"perp:BTC" 0.2
                                    "spot:PURR" 0.1}
    :expected-return 0.18
    :volatility 0.42
    :performance {:in-sample-sharpe 0.43
                  :shrunk-sharpe 0.215}
    :history-summary {:return-observations 2
                      :stale? false}
    :frontier [{:id 0
                :expected-return 0.12
                :volatility 0.24
                :sharpe 0.5}
               {:id 1
                :expected-return 0.18
                :volatility 0.42
                :sharpe 0.43}]
    :return-decomposition-by-instrument
    {"perp:BTC" {:return-component 0.12
                 :funding-component 0.04
                 :funding-source :market-funding-history}
     "spot:PURR" {:return-component 0.08
                  :funding-component 0
                  :funding-source :missing}}
    :diagnostics {:gross-exposure 0.37
                  :net-exposure 0.33
                  :effective-n 2.2
                  :turnover 0.135
                  :covariance-conditioning {:status :watch
                                            :condition-number 12000
                                            :min-eigenvalue 0.001}
                  :weight-sensitivity-by-instrument
                  {"perp:BTC" {:base-expected-return 0.18
                               :down-expected-return 0.17
                               :up-expected-return 0.19}}
                  :binding-constraints [{:instrument-id "perp:BTC"
                                         :constraint :upper-bound}]}
    :warnings [{:code :low-invested-exposure
                :message "Minimum variance selected a near-cash signed portfolio."}]
    :rebalance-preview {:status :partially-blocked
                        :capital-usd 10000
                        :summary {:ready-count 1
                                  :blocked-count 1
                                  :gross-trade-notional-usd 2700
                                  :estimated-fees-usd 1.2
                                  :estimated-slippage-usd 2.4}
                        :rows [{:instrument-id "perp:BTC"
                                :status :ready
                                :side :buy
                                :delta-notional-usd 1500}
                               {:instrument-id "spot:PURR"
                                :status :blocked
                                :reason :spot-submit-unsupported
                                :side :sell
                                :delta-notional-usd -1200}]}}))

(deftest results-panel-renders-v1-results-workspace-contract-test
  (let [draft {:objective {:kind :target-volatility}
               :metadata {:dirty? true}}
        view-node (results-panel/results-panel
                   {:result solved-result
                    :computed-at-ms 2600}
                   draft
                   {:stale? true})
        frontier-point (node-by-role view-node "portfolio-optimizer-frontier-point-1")
        frontier-point-actions [[:actions/set-portfolio-optimizer-objective-kind :target-volatility]
                                [:actions/set-portfolio-optimizer-objective-parameter
                                 :target-volatility
                                 0.42]]
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-results-surface")))
    (is (some? (node-by-role view-node "portfolio-optimizer-stale-result-banner")))
   (is (= [[:actions/run-portfolio-optimizer-from-draft]]
           (click-actions
            (node-by-role view-node "portfolio-optimizer-rerun-stale-result"))))
    (is (some? (node-by-role view-node "portfolio-optimizer-results-grid")))
    (is (some? (node-by-role view-node "portfolio-optimizer-results-left-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-results-center-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-results-right-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-trust-caution-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-frontier-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-frontier-svg")))
    (is (some? (node-by-role view-node "portfolio-optimizer-frontier-path")))
    (is (some? (node-by-role view-node "portfolio-optimizer-frontier-target-marker")))
    (is (some? (node-by-role view-node "portfolio-optimizer-frontier-legend")))
    (is (some? (node-by-role view-node "portfolio-optimizer-target-exposure-table")))
    (is (some? (node-by-role view-node "portfolio-optimizer-target-exposure-asset-BTC")))
    (is (some? (node-by-role view-node "portfolio-optimizer-target-exposure-group-BTC")))
    (is (some? (node-by-role view-node "portfolio-optimizer-target-exposure-asset-PURR")))
    (is (some? (node-by-role view-node "portfolio-optimizer-target-exposure-group-PURR")))
    (is (some? (node-by-role view-node "portfolio-optimizer-result-warnings")))
    (is (some? (node-by-role view-node "portfolio-optimizer-diagnostics-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-rebalance-preview")))
    (is (= "true"
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-target-exposure-row-0")
                   [1 :data-binding])))
    (is (= "short"
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-target-exposure-row-1")
                   [1 :data-target-sign])))
    (is (= frontier-point-actions
           (click-actions frontier-point)))
    (is (= true
           (get-in frontier-point [1 :draggable])))
    (is (= frontier-point-actions
           (drag-start-actions frontier-point)))
    (is (= frontier-point-actions
           (drag-enter-actions frontier-point)))
    (is (contains? strings "Allocation"))
    (is (contains? strings "By asset · click to expand legs"))
    (is (contains? strings "How much to trust this"))
    (is (contains? strings "Weight Stability"))
    (is (contains? strings "Effective N · 2 of 2"))
    (is (contains? strings "Recommended target"))
    (is (contains? strings "watch"))
    (is (not (contains? strings "Funding Decomposition")))
    (is (contains? strings "low-invested-exposure"))
    (is (contains? strings "partially-blocked"))
    (is (contains? strings "spot-submit-unsupported"))
    (is (contains? strings "perp:BTC"))))
