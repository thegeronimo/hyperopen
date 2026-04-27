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

(deftest portfolio-view-delegates-optimizer-new-route-to-setup-only-surface-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :wallet {:address "0x1111111111111111111111111111111111111111"}
                    :webdata2 {:clearinghouseState {:marginSummary {:accountValue "100"}}}})]
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-surface")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-workspace")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-scenario-detail-surface")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-scenario-tabs")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-provenance-strip")))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-header")))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-status-tag")))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-preset-row")))
    (is (= [[:actions/apply-portfolio-optimizer-setup-preset :conservative]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-setup-preset-conservative"))))
    (is (= [[:actions/apply-portfolio-optimizer-setup-preset :risk-adjusted]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-setup-preset-risk-adjusted"))))
    (is (= [[:actions/apply-portfolio-optimizer-setup-preset :use-my-views]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-setup-preset-use-my-views"))))
    (is (some? (node-by-role view-node "portfolio-optimizer-draft-state")))
    (is (= true
           (get-in (node-by-role view-node "portfolio-optimizer-run-draft")
                   [1 :disabled])))
    (is (= true
           (get-in (node-by-role view-node "portfolio-optimizer-save-scenario")
                   [1 :disabled])))
    (is (nil? (node-by-role view-node "portfolio-optimizer-load-history")))
    (is (some? (node-by-role view-node "portfolio-optimizer-universe-panel")))
    (is (= [[:actions/set-portfolio-optimizer-universe-from-current]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-universe-use-current"))))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-model-grid")))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-model-column")))
    (is (some? (node-by-role view-node "portfolio-optimizer-objective-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-return-model-panel")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-black-litterman-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-risk-model-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-constraints-panel")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-execution-assumptions-panel")))
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
    (is (= "0.25"
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
    (is (= [[:actions/set-portfolio-optimizer-risk-model-kind :diagonal-shrink]]
           (click-actions (node-by-role view-node "portfolio-optimizer-risk-model-diagonal-shrink"))))
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
    (is (some? (node-by-role view-node "portfolio-optimizer-advanced-overrides-shell")))
    (is (some? (node-by-role view-node "portfolio-optimizer-instrument-overrides-panel")))
    (let [strings (set (collect-strings view-node))]
      (is (contains? strings "Minimum Variance"))
      (is (contains? strings "Historical Mean"))
      (is (contains? strings "Diagonal Shrink"))
      (is (contains? strings "Draft clean"))
      (is (not (contains? strings "Load History")))
      (is (contains? strings "Max Asset Weight"))
      (is (contains? strings "Gross Leverage"))
      (is (contains? strings "Rebalance Tolerance"))
      (is (not (contains? strings "Execution Assumptions")))
      (is (not (contains? strings "Fallback Slippage")))
      (is (not (contains? strings "Manual Capital Base")))
      (is (not (contains? strings "Default Order: Market")))
      (is (not (contains? strings "Fee Mode: Taker"))))))

(deftest portfolio-optimizer-setup-route-shows-use-my-views-context-for-black-litterman-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}
                                                    {:instrument-id "perp:ETH"
                                                     :market-type :perp
                                                     :coin "ETH"}]
                                         :objective {:kind :max-sharpe}
                                         :return-model {:kind :black-litterman
                                                        :views []}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? false}}}}})
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-use-my-views-context")))
    (is (some? (node-by-role view-node "portfolio-optimizer-black-litterman-panel")))
    (is (= "true"
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-setup-preset-use-my-views")
                   [1 :aria-pressed])))
    (is (contains? strings "Use my views"))
    (is (contains? strings "Black-Litterman Views"))))

(deftest portfolio-optimizer-workspace-enables-run-for-draft-universe-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
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
    (is (nil? (node-by-role view-node "portfolio-optimizer-load-history")))
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

(deftest portfolio-optimizer-setup-route-shows-run-state-without-retained-result-surface-test
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
    (is (nil? (node-by-role view-node "portfolio-optimizer-results-surface")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-rebalance-preview")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-tracking-panel")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-current-summary")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-signed-exposure-table")))
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
    (is (nil? loading-button))
    (is (contains? (set (collect-strings loading-node))
                   "Loading optimizer history for the selected universe."))
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

(deftest portfolio-optimizer-workspace-shows-optimization-progress-panel-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}]}
                                 :optimization-progress
                                 {:status :running
                                  :run-id "run-1"
                                  :scenario-id "draft-1"
                                  :started-at-ms 1000
                                  :active-step :fetch-returns
                                  :overall-percent 25
                                  :steps [{:id :fetch-returns
                                           :label "fetch returns matrix"
                                           :detail "1/2 requests"
                                           :status :running
                                           :percent 50}
                                          {:id :solve
                                           :label "QP solve"
                                           :detail "OSQP"
                                           :status :pending
                                           :percent 0}]
                                  :error nil}}}})
        run-button (node-by-role view-node "portfolio-optimizer-run-draft")
        strings (set (collect-strings view-node))]
    (is (= true (get-in run-button [1 :disabled])))
    (is (some? (node-by-role view-node "portfolio-optimizer-progress-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-progress-step-fetch-returns")))
    (is (contains? strings "Optimization In Progress"))
    (is (contains? strings "Computing"))
    (is (contains? strings "fetch returns matrix"))
    (is (contains? strings "QP solve"))))

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

(deftest portfolio-optimizer-workspace-allows-one-click-run-when-history-is-missing-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? true}}
                                 :history-data {:candle-history-by-coin {}
                                                :funding-history-by-coin {}}
                                 :runtime {:as-of-ms 2500}}}})
        run-button (node-by-role view-node "portfolio-optimizer-run-draft")]
    (is (= false (get-in run-button [1 :disabled])))
    (is (= [[:actions/run-portfolio-optimizer-from-draft]]
           (click-actions run-button)))
    (is (some? (node-by-role view-node
                             "portfolio-optimizer-readiness-warning")))
    (is (contains? (set (collect-strings view-node))
                   "missing-candle-history"))))

(deftest portfolio-view-delegates-optimizer-scenario-route-to-detail-surface-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/scn_01"}})
        detail-surface (node-by-role view-node "portfolio-optimizer-scenario-detail-surface")
        strings (set (collect-strings view-node))]
    (is (some? detail-surface))
    (is (= "scn_01" (get-in detail-surface [1 :data-scenario-id])))
    (is (some? (node-by-role view-node "portfolio-optimizer-provenance-strip")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-tabs")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-tab-recommendation")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-tab-rebalance")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-tab-tracking")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-tab-inputs")))
    (is (some? (node-by-role view-node "portfolio-optimizer-recommendation-tab")))
    (is (= [[:actions/set-portfolio-optimizer-results-tab :tracking]]
           (click-actions
            (node-by-role view-node "portfolio-optimizer-scenario-tab-tracking"))))
    (is (nil? (node-by-role view-node "portfolio-optimizer-setup-surface")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-workspace")))
    (is (contains? strings "Scenario scn_01"))
    (is (contains? strings "Recommendation"))
    (is (contains? strings "Rebalance preview"))
    (is (contains? strings "Tracking"))
    (is (contains? strings "Inputs"))))

(deftest portfolio-optimizer-scenario-detail-renders-header-kpis-and-provenance-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/scn_01"}
                    :portfolio {:optimizer
                                {:active-scenario {:loaded-id "scn_01"
                                                   :name "Capital Rotation"
                                                   :status :computed}
                                 :draft {:name "Capital Rotation"
                                         :metadata {:dirty? true}
                                         :universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}
                                                    {:instrument-id "perp:ETH"
                                                     :market-type :perp
                                                     :coin "ETH"}]
                                         :objective {:kind :max-sharpe}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:max-asset-weight 0.4
                                                       :gross-max 1.5}}
                                 :last-successful-run
                                 {:computed-at-ms 1714137600000
                                  :request-signature {:seed 1}
                                  :result {:status :solved
                                           :as-of-ms 1714137600000
                                           :instrument-ids ["perp:BTC" "perp:ETH"]
                                           :current-weights [0.1 0.2]
                                           :target-weights [0.35 0.15]
                                           :expected-return 0.14
                                           :volatility 0.32
                                           :performance {:shrunk-sharpe 0.44}
                                           :history-summary {:return-observations 12
                                                             :stale? false}
                                           :return-model :historical-mean
                                           :risk-model :diagonal-shrink
                                           :return-decomposition-by-instrument
                                           {"perp:BTC" {:return-component 0.1
                                                        :funding-component 0.02}
                                            "perp:ETH" {:return-component 0.08
                                                        :funding-component 0.01}}
                                           :diagnostics {:turnover 0.2}
                                           :rebalance-preview {:status :ready
                                                               :capital-usd 100000
                                                               :summary {:ready-count 2
                                                                         :blocked-count 0}
                                                               :rows []}}}}}})
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-header")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-status-tag")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-kpi-strip")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-kpi-expected-return")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-kpi-volatility")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-kpi-sharpe")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-kpi-turnover")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-kpi-rebalance")))
    (is (some? (node-by-role view-node "portfolio-optimizer-results-surface")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-rebalance-preview")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-tracking-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-stale-banner")))
    (is (= [[:actions/run-portfolio-optimizer-from-draft]]
           (click-actions
            (node-by-role view-node "portfolio-optimizer-scenario-rerun-stale"))))
    (is (= [[:actions/save-portfolio-optimizer-scenario-from-current]]
           (click-actions
            (node-by-role view-node "portfolio-optimizer-scenario-save"))))
    (is (= [[:actions/run-portfolio-optimizer-from-draft]]
           (click-actions
            (node-by-role view-node "portfolio-optimizer-scenario-rerun"))))
    (is (contains? strings "Capital Rotation"))
    (is (contains? strings "14.00%"))
    (is (contains? strings "32.00%"))
    (is (contains? strings "0.44"))
    (is (contains? strings "20.00%"))
    (is (contains? strings "12 returns"))
    (is (contains? strings "$100,000"))
    (is (contains? strings "Draft inputs changed after the last successful run. Rerun before using recommendation or rebalance output."))))

(deftest portfolio-optimizer-inputs-tab-renders-read-only-audit-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/scn_inputs"}
                    :portfolio-ui {:optimizer {:results-tab :inputs}}
                    :portfolio {:optimizer
                                {:active-scenario {:loaded-id "scn_inputs"
                                                   :status :saved}
                                 :draft {:id "scn_inputs"
                                         :universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}]
                                         :objective {:kind :target-volatility}
                                         :return-model {:kind :black-litterman
                                                        :views [{:id "view-1"}]}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? true
                                                       :max-asset-weight 0.4
                                                       :gross-max 1.2
                                                       :max-turnover 0.5
                                                       :rebalance-tolerance 0.01
                                                       :dust-usdc 15}
                                         :execution-assumptions {:manual-capital-usdc 25000
                                                                 :fallback-slippage-bps 35
                                                                 :default-order-type :market
                                                                 :fee-mode :taker}}}}})
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-inputs-tab")))
    (is (some? (node-by-role view-node "portfolio-optimizer-inputs-audit-grid")))
    (is (some? (node-by-role view-node "portfolio-optimizer-inputs-universe")))
    (is (some? (node-by-role view-node "portfolio-optimizer-inputs-models")))
    (is (some? (node-by-role view-node "portfolio-optimizer-inputs-constraints")))
    (is (some? (node-by-role view-node "portfolio-optimizer-inputs-execution-assumptions")))
    (is (= [[:actions/duplicate-portfolio-optimizer-scenario "scn_inputs"]]
           (click-actions
            (node-by-role view-node "portfolio-optimizer-inputs-duplicate"))))
    (is (contains? strings "Read-only scenario input audit. Duplicate the scenario before editing inputs."))
    (is (contains? strings "perp:BTC"))
    (is (contains? strings "Black-Litterman views: 1"))
    (is (contains? strings "Manual capital: $25,000"))))

(deftest portfolio-optimizer-scenario-detail-does-not-render-stale-loaded-scenario-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/scn_new"}
                    :portfolio {:optimizer
                                {:active-scenario {:loaded-id "scn_old"
                                                   :status :executed}
                                 :draft {:name "Old scenario"
                                         :universe [{:instrument-id "perp:OLD"
                                                     :market-type :perp
                                                     :coin "OLD"}]}
                                 :last-successful-run {:result {:status :solved
                                                                :instrument-ids ["perp:OLD"]}}
                                 :tracking {:scenario-id "scn_old"
                                            :snapshots
                                            [{:scenario-id "scn_old"
                                              :rows [{:instrument-id "perp:OLD"
                                                      :current-weight 1
                                                      :target-weight 1
                                                      :weight-drift 0
                                                      :signed-notional-usdc 1000}]}]}}}})
        detail-surface (node-by-role view-node "portfolio-optimizer-scenario-detail-surface")
        strings (set (collect-strings view-node))]
    (is (= "scn_new" (get-in detail-surface [1 :data-scenario-id])))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-loading-state")))
    (is (contains? strings "Scenario scn_new"))
    (is (not (contains? strings "Old scenario")))
    (is (not (contains? strings "perp:OLD")))))

(deftest portfolio-optimizer-scenario-detail-hides-unsaved-run-while-route-load-pending-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/scn_loading"}
                    :portfolio {:optimizer
                                {:active-scenario {:loaded-id nil
                                                   :status :idle}
                                 :scenario-load-state {:status :loading
                                                       :scenario-id "scn_loading"}
                                 :draft {:name "Unsaved draft"
                                         :universe [{:instrument-id "perp:UNSAVED"
                                                     :market-type :perp
                                                     :coin "UNSAVED"}]}
                                 :last-successful-run {:result {:status :solved
                                                                :instrument-ids ["perp:UNSAVED"]}}}}})
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-loading-state")))
    (is (contains? strings "Scenario scn_loading"))
    (is (not (contains? strings "Unsaved draft")))
    (is (not (contains? strings "perp:UNSAVED")))))
