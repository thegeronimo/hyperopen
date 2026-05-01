(ns hyperopen.views.portfolio.optimize.workspace-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [change-actions click-actions collect-strings input-actions node-by-role]]))

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
                                 :last-successful-run (fixtures/sample-last-successful-run
                                                       {:computed-at-ms 2000
                                                        :result {:instrument-ids ["perp:BTC" "spot:PURR"]}})}}})
        run-button (node-by-role view-node "portfolio-optimizer-run-draft")
        view-weights-link (node-by-role view-node "portfolio-optimizer-view-weights")
        results-link (node-by-role view-node "portfolio-optimizer-results-link")
        strings (set (collect-strings view-node))]
    (is (= true (get-in run-button [1 :disabled])))
    (is (some? (node-by-role view-node "portfolio-optimizer-run-status-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-last-successful-run")))
    (is (= "button" (get-in view-weights-link [1 :type])))
    (is (= [[:actions/navigate "/portfolio/optimize/draft"]]
           (click-actions view-weights-link)))
    (is (= "button" (get-in results-link [1 :type])))
    (is (= [[:actions/navigate "/portfolio/optimize/draft"]] (click-actions results-link)))
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
