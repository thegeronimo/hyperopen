(ns hyperopen.views.portfolio.optimize.scenario-detail-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [click-actions collect-strings node-by-role]]))

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
                                 (fixtures/sample-last-successful-run
                                  {:computed-at-ms 1714137600000
                                   :request-signature {:seed 1}
                                   :result {:as-of-ms 1714137600000
                                            :instrument-ids ["perp:BTC" "perp:ETH"]
                                            :current-weights [0.1 0.2]
                                            :target-weights [0.35 0.15]
                                            :target-weights-by-instrument {"perp:BTC" 0.35 "perp:ETH" 0.15}
                                            :current-weights-by-instrument {"perp:BTC" 0.1 "perp:ETH" 0.2}
                                            :expected-return 0.14
                                            :volatility 0.32
                                            :performance {:shrunk-sharpe 0.44}
                                            :history-summary {:return-observations 12 :stale? false}
                                            :return-model :historical-mean
                                            :risk-model :diagonal-shrink
                                            :return-decomposition-by-instrument
                                            {"perp:BTC" {:return-component 0.1 :funding-component 0.02}
                                             "perp:ETH" {:return-component 0.08 :funding-component 0.01}}
                                            :diagnostics {:turnover 0.2}
                                            :rebalance-preview {:status :ready
                                                                :capital-usd 100000
                                                                :summary {:ready-count 2 :blocked-count 0}
                                                                :rows []}}})}}})
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
    (is (contains? strings "data as of "))
    (is (contains? strings "gross ≤ 1.5 · cap 40.00%"))
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
                                 :last-successful-run (fixtures/sample-last-successful-run
                                                       {:result {:instrument-ids ["perp:OLD"]}})
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
                                 :last-successful-run (fixtures/sample-last-successful-run
                                                       {:result {:instrument-ids ["perp:UNSAVED"]}})}}})
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-loading-state")))
    (is (contains? strings "Scenario scn_loading"))
    (is (not (contains? strings "Unsaved draft")))
    (is (not (contains? strings "perp:UNSAVED")))))
