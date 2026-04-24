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

(defn- click-actions
  [node]
  (get-in node [1 :on :click]))

(defn- input-actions
  [node]
  (get-in node [1 :on :input]))

(defn- change-actions
  [node]
  (get-in node [1 :on :change]))

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
    (is (some? (node-by-role view-node "portfolio-optimizer-draft-state")))
    (is (= true
           (get-in (node-by-role view-node "portfolio-optimizer-run-draft")
                   [1 :disabled])))
    (is (= true
           (get-in (node-by-role view-node "portfolio-optimizer-load-history")
                   [1 :disabled])))
    (is (some? (node-by-role view-node "portfolio-optimizer-universe-panel")))
    (is (= [[:actions/set-portfolio-optimizer-universe-from-current]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-universe-use-current"))))
    (is (some? (node-by-role view-node "portfolio-optimizer-objective-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-return-model-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-risk-model-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-constraints-panel")))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :long-only?
             :event.target/checked]]
           (change-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-long-only-input"))))
    (is (= false
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-constraint-long-only-input")
                   [1 :checked])))
    (is (= "0.35"
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-constraint-max-asset-weight-input")
                   [1 :value])))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :max-asset-weight
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-max-asset-weight-input"))))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :gross-max
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-gross-max-input"))))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :net-min
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-net-min-input"))))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :net-max
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-net-max-input"))))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :dust-usdc
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-dust-usdc-input"))))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :max-turnover
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-max-turnover-input"))))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :rebalance-tolerance
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-rebalance-tolerance-input"))))
    (is (= [[:actions/set-portfolio-optimizer-objective-kind :max-sharpe]]
           (click-actions (node-by-role view-node "portfolio-optimizer-objective-max-sharpe"))))
    (is (= [[:actions/set-portfolio-optimizer-return-model-kind :black-litterman]]
           (click-actions (node-by-role view-node "portfolio-optimizer-return-model-black-litterman"))))
    (is (= [[:actions/set-portfolio-optimizer-risk-model-kind :sample-covariance]]
           (click-actions (node-by-role view-node "portfolio-optimizer-risk-model-sample-covariance"))))
    (is (= [[:actions/set-portfolio-optimizer-objective-parameter
             :target-return
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-objective-target-return-input"))))
    (is (= [[:actions/set-portfolio-optimizer-objective-parameter
             :target-volatility
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-objective-target-volatility-input"))))
    (is (= [[:actions/set-portfolio-optimizer-execution-assumption
             :fallback-slippage-bps
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-execution-fallback-slippage-bps-input"))))
    (is (= [[:actions/set-portfolio-optimizer-execution-assumption
             :default-order-type
             [:event.target/value]]]
           (change-actions
            (node-by-role view-node
                          "portfolio-optimizer-execution-default-order-type-input"))))
    (is (= [[:actions/set-portfolio-optimizer-execution-assumption
             :fee-mode
             [:event.target/value]]]
           (change-actions
            (node-by-role view-node
                          "portfolio-optimizer-execution-fee-mode-input"))))
    (is (some? (node-by-role view-node "portfolio-optimizer-instrument-overrides-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-signed-exposure-table")))
    (let [strings (set (collect-strings view-node))]
      (is (contains? strings "Minimum Variance"))
      (is (contains? strings "Historical Mean"))
      (is (contains? strings "Ledoit-Wolf"))
      (is (contains? strings "Draft clean"))
      (is (contains? strings "Load History"))
      (is (contains? strings "Max Asset Weight"))
      (is (contains? strings "Gross Leverage"))
      (is (contains? strings "Rebalance Tolerance"))
      (is (contains? strings "Execution Assumptions"))
      (is (contains? strings "Fallback Slippage")))))

(deftest portfolio-optimizer-workspace-enables-run-for-draft-universe-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :ledoit-wolf}
                                         :constraints {:long-only? true}}
                                 :history-data {:candle-history-by-coin
                                                {"BTC" [{:time 1000 :close "100"}
                                                        {:time 2000 :close "110"}]}
                                                :funding-history-by-coin {}}
                                 :runtime {:as-of-ms 2500}}}})
        run-button (node-by-role view-node "portfolio-optimizer-run-draft")]
    (is (= false (get-in run-button [1 :disabled])))
    (is (= [[:actions/run-portfolio-optimizer-from-draft]]
           (click-actions run-button)))
    (is (= [[:actions/load-portfolio-optimizer-history-from-draft]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-load-history"))))
    (is (= [[:actions/set-portfolio-optimizer-instrument-filter
             :allowlist
             "perp:BTC"
             :event.target/checked]]
           (change-actions
            (node-by-role view-node
                          "portfolio-optimizer-instrument-allowlist-input"))))
    (is (= [[:actions/set-portfolio-optimizer-instrument-filter
             :blocklist
             "perp:BTC"
             :event.target/checked]]
           (change-actions
            (node-by-role view-node
                          "portfolio-optimizer-instrument-blocklist-input"))))
    (is (= [[:actions/set-portfolio-optimizer-asset-override
             :max-weight
             "perp:BTC"
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-instrument-max-weight-input"))))
    (is (= [[:actions/set-portfolio-optimizer-asset-override
             :held-lock?
             "perp:BTC"
             :event.target/checked]]
           (change-actions
            (node-by-role view-node
                          "portfolio-optimizer-instrument-held-lock-input"))))
    (is (= [[:actions/set-portfolio-optimizer-asset-override
             :perp-max-weight
             "perp:BTC"
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-instrument-perp-max-weight-input"))))))

(deftest portfolio-optimizer-workspace-shows-run-state-and-retained-result-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}]
                                         :metadata {:dirty? true}}
                                 :history-data {:candle-history-by-coin
                                                {"BTC" [{:time 1000 :close "100"}
                                                        {:time 2000 :close "110"}]}
                                                :funding-history-by-coin {}}
                                 :runtime {:as-of-ms 2500}
                                 :run-state {:status :running
                                             :run-id "run-1"
                                             :started-at-ms 2400}
                                 :last-successful-run {:result {:status :solved
                                                                :instrument-ids ["perp:BTC"
                                                                                 "spot:PURR"]}
                                                       :computed-at-ms 2000}}}})
        run-button (node-by-role view-node "portfolio-optimizer-run-draft")
        strings (set (collect-strings view-node))]
    (is (= true (get-in run-button [1 :disabled])))
    (is (some? (node-by-role view-node "portfolio-optimizer-run-status-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-last-successful-run")))
    (is (contains? strings "Draft has unsaved changes"))
    (is (contains? strings "Running Optimization"))
    (is (contains? strings "Running"))
    (is (contains? strings "Retaining last successful result while rerunning."))
    (is (contains? strings "2 assets"))))

(deftest portfolio-optimizer-workspace-shows-history-load-state-test
  (let [loading-node (portfolio-view/portfolio-view
                      {:router {:path "/portfolio/optimize/new"}
                       :portfolio {:optimizer
                                   {:draft {:universe [{:instrument-id "perp:BTC"
                                                        :market-type :perp
                                                        :coin "BTC"}]}
                                    :history-load-state {:status :loading
                                                         :started-at-ms 123}}}})
        failed-node (portfolio-view/portfolio-view
                     {:router {:path "/portfolio/optimize/new"}
                      :portfolio {:optimizer
                                  {:draft {:universe [{:instrument-id "perp:BTC"
                                                       :market-type :perp
                                                       :coin "BTC"}]}
                                   :history-load-state {:status :failed
                                                        :error {:message "history unavailable"}}}}})
        loading-button (node-by-role loading-node "portfolio-optimizer-load-history")]
    (is (= true (get-in loading-button [1 :disabled])))
    (is (contains? (set (collect-strings loading-node))
                   "Loading History"))
    (is (contains? (set (collect-strings failed-node))
                   "history unavailable"))))

(deftest portfolio-optimizer-workspace-shows-failed-run-status-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:run-state {:status :failed
                                             :completed-at-ms 2600
                                             :error {:code :solver-failed
                                                     :message "solver blew up"}}}}})
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-run-status-panel")))
    (is (contains? strings "Failed"))
    (is (contains? strings "solver-failed"))
    (is (contains? strings "solver blew up"))))

(deftest portfolio-optimizer-workspace-renders-results-diagnostics-and-rebalance-test
  (let [result {:status :solved
                :instrument-ids ["perp:BTC" "spot:PURR"]
                :current-weights [0.2 0.1]
                :target-weights [0.35 -0.02]
                :expected-return 0.18
                :volatility 0.42
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
                              :binding-constraints [{:instrument-id "perp:BTC"
                                                     :constraint :upper-bound}]}
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
                                            :reason :spot-read-only
                                            :side :sell
                                            :delta-notional-usd -1200}]}}
        view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:objective {:kind :target-volatility}}
                                 :last-successful-run {:result result
                                                       :computed-at-ms 2600}}}})
        frontier-point (node-by-role view-node "portfolio-optimizer-frontier-point-1")
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-results-surface")))
    (is (some? (node-by-role view-node "portfolio-optimizer-frontier-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-target-exposure-table")))
    (is (some? (node-by-role view-node "portfolio-optimizer-return-decomposition")))
    (is (some? (node-by-role view-node "portfolio-optimizer-diagnostics-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-rebalance-preview")))
    (is (= "true"
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-target-exposure-row-0")
                   [1 :data-binding])))
    (is (= [[:actions/set-portfolio-optimizer-objective-kind :target-volatility]
            [:actions/set-portfolio-optimizer-objective-parameter
             :target-volatility
             0.42]]
           (click-actions frontier-point)))
    (is (contains? strings "Target Exposure"))
    (is (contains? strings "Efficient Frontier"))
    (is (contains? strings "Click a point to set Target Volatility and rerun."))
    (is (contains? strings "Funding Decomposition"))
    (is (contains? strings "Binding Constraints"))
    (is (contains? strings "partially-blocked"))
    (is (contains? strings "spot-read-only"))
    (is (contains? strings "perp:BTC"))))

(deftest portfolio-optimizer-workspace-renders-infeasible-result-and-highlights-controls-test
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
                                         :constraints {:long-only? true
                                                       :max-asset-weight 0.4}}
                                 :run-state {:status :infeasible
                                             :completed-at-ms 3000
                                             :result {:status :infeasible
                                                      :reason :constraint-presolve
                                                      :details
                                                      {:violations
                                                       [{:code :sum-upper-below-target
                                                         :sum-upper 0.8
                                                         :target-net 1}]}}}}}})
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-infeasible-banner")))
    (is (contains? strings "Infeasible Optimization"))
    (is (contains? strings "sum-upper-below-target"))
    (is (contains? strings "Max Asset Weight"))
    (is (= "true"
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-constraint-max-asset-weight-input")
                   [1 :data-infeasible])))
    (is (= "true"
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-constraint-max-asset-weight-input")
                   [1 :aria-invalid])))))

(deftest portfolio-optimizer-workspace-blocks-run-when-history-is-missing-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :ledoit-wolf}
                                         :constraints {:long-only? true}}
                                 :history-data {:candle-history-by-coin {}
                                                :funding-history-by-coin {}}
                                 :runtime {:as-of-ms 2500}}}})
        run-button (node-by-role view-node "portfolio-optimizer-run-draft")]
    (is (= true (get-in run-button [1 :disabled])))
    (is (some? (node-by-role view-node
                             "portfolio-optimizer-readiness-warning")))
    (is (contains? (set (collect-strings view-node))
                   "missing-candle-history"))))

(deftest portfolio-view-delegates-optimizer-scenario-route-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/scn_01"}})
        workspace (node-by-role view-node "portfolio-optimizer-workspace")]
    (is (= "scn_01" (get-in workspace [1 :data-scenario-id])))
    (is (contains? (set (collect-strings view-node))
                   "Scenario scn_01"))))
