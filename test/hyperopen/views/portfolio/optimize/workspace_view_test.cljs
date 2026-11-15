(ns hyperopen.views.portfolio.optimize.workspace-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.actions.common :as action-common]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [change-actions click-actions collect-strings input-actions node-by-role]]))

(defn- ready-workspace-state
  [return-model]
  {:router {:path "/portfolio/optimize/new"}
   :portfolio {:optimizer
                {:draft {:id "draft-current"
                        :universe [{:instrument-id "perp:BTC"
                                    :market-type :perp
                                    :coin "BTC"}
                                   {:instrument-id "perp:ETH"
                                    :market-type :perp
                                    :coin "ETH"}]
                        :objective {:kind :max-sharpe}
                        :return-model return-model
                        :risk-model {:kind :sample-covariance}
                        :constraints {:long-only? true
                                      :max-asset-weight 1.0}
                        :metadata {:dirty? false}}
                :history-data {:candle-history-by-coin
                               {"BTC" [{:time 1000 :close "100"}
                                       {:time 2000 :close "110"}
                                       {:time 3000 :close "108"}
                                       {:time 4000 :close "116"}]
                                "ETH" [{:time 1000 :close "50"}
                                       {:time 2000 :close "54"}
                                       {:time 3000 :close "49"}
                                       {:time 4000 :close "55"}]}
                               :funding-history-by-coin {}}
                :market-cap-by-coin {}
                :runtime {:as-of-ms 2500
                          :stale-after-ms 60000}
                :run-state {:status :succeeded
                            :run-id "run-1"
                            :completed-at-ms 2600}}}
   :webdata2 {:clearinghouseState
              {:marginSummary {:accountValue "1000"}
               :assetPositions []}}})

(defn- request-signature-for-state
  [state]
  (let [{:keys [request runnable?]} (setup-readiness/build-readiness state)]
    (is runnable?)
    (action-common/build-request-signature request)))

(defn- solved-run-for-state
  [state]
  (fixtures/sample-last-successful-run
   {:computed-at-ms 2600
    :request-signature (request-signature-for-state state)
    :result {:status :solved
             :instrument-ids ["perp:BTC"]}}))

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
             [:event.target/checked]]]
           (change-actions
            (node-by-role view-node
                          "portfolio-optimizer-instrument-allowlist-input")))
        "Placeholders interpolate only in vector form; a bare keyword reaches the action unresolved.")
    (is (= [[:actions/set-portfolio-optimizer-instrument-filter
             :blocklist
             "perp:BTC"
             [:event.target/checked]]]
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
             [:event.target/checked]]]
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
    (is (nil? view-weights-link)
        "Dirty or in-flight drafts must not expose stale weights as the current result.")
    (is (nil? results-link)
        "The setup rail should not navigate to stale retained results for a dirty draft.")
    (is (nil? (node-by-role view-node "portfolio-optimizer-results-surface")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-rebalance-preview")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-tracking-panel")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-current-summary")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-signed-exposure-table")))
    (is (contains? strings "Draft has unsaved changes"))
    (is (contains? strings "Optimizing…"))
    (is (contains? strings "Running"))
    (is (contains? strings "Retaining last successful result while rerunning."))
    (is (contains? strings "2 assets"))))

(deftest portfolio-optimizer-workspace-links-current-clean-result-test
  (let [state (ready-workspace-state {:kind :historical-mean})
        view-node (portfolio-view/portfolio-view
                   (assoc-in state
                             [:portfolio :optimizer :last-successful-run]
                             (solved-run-for-state state)))
        results-link (node-by-role view-node "portfolio-optimizer-results-link")]
    (is (nil? (node-by-role view-node "portfolio-optimizer-view-weights"))
        "The new setup route should auto-navigate after a successful run instead of exposing a View weights button.")
    (is (= "button" (get-in results-link [1 :type])))
    (is (= [[:actions/navigate "/portfolio/optimize/draft"]]
           (click-actions results-link)))))

(deftest portfolio-optimizer-workspace-exposes-rebalance-path-after-clean-run-test
  (let [state (ready-workspace-state {:kind :historical-mean})
        view-node (portfolio-view/portfolio-view
                   (assoc-in state
                             [:portfolio :optimizer :last-successful-run]
                             (solved-run-for-state state)))
        rail-rebalance (node-by-role view-node "portfolio-optimizer-rebalance-link")
        action-rebalance (node-by-role view-node "portfolio-optimizer-view-rebalance")
        expected [[:actions/navigate "/portfolio/optimize/draft"]
                  [:actions/open-portfolio-optimizer-execution]]]
    (is (some? rail-rebalance)
        "The setup status rail must offer a direct path to stage the rebalance.")
    (is (some? action-rebalance)
        "The setup action bar must offer a direct path to stage the rebalance.")
    (is (= expected (click-actions rail-rebalance)))
    (is (= expected (click-actions action-rebalance)))))

(deftest portfolio-optimizer-workspace-links-current-generated-draft-result-test
  (let [state (ready-workspace-state {:kind :black-litterman
                                      :views [{:kind :absolute
                                               :instrument-id "perp:BTC"
                                               :return 0.2
                                               :confidence 0.75
                                               :weights {"perp:BTC" 1}}]})
        state* (assoc-in state
                         [:portfolio :optimizer :active-scenario :loaded-id]
                         "draft-current")
        view-node (portfolio-view/portfolio-view
                   (assoc-in state*
                             [:portfolio :optimizer :last-successful-run]
                             (solved-run-for-state state*)))
        results-link (node-by-role view-node "portfolio-optimizer-results-link")]
    (is (nil? (node-by-role view-node "portfolio-optimizer-view-weights"))
        "The setup action bar should not require a second click after optimization succeeds.")
    (is (= [[:actions/navigate "/portfolio/optimize/draft"]]
           (click-actions results-link)))))

(deftest portfolio-optimizer-workspace-keeps-completed-run-link-after-snapshot-drift-test
  (let [state (ready-workspace-state {:kind :historical-mean})
        solved-run (solved-run-for-state state)
        state* (-> state
                   (assoc-in [:portfolio :optimizer :active-scenario :loaded-id]
                             "draft-current")
                   (assoc-in [:portfolio :optimizer :run-state :request-signature]
                             (:request-signature solved-run))
                   (assoc-in [:portfolio :optimizer :last-successful-run]
                             solved-run)
                   (assoc-in [:webdata2 :clearinghouseState :marginSummary :accountValue]
                             "2000"))
        view-node (portfolio-view/portfolio-view state*)
        results-link (node-by-role view-node "portfolio-optimizer-results-link")]
    (is (nil? (node-by-role view-node "portfolio-optimizer-view-weights")))
    (is (= [[:actions/navigate "/portfolio/optimize/draft"]]
           (click-actions results-link)))))

(deftest portfolio-optimizer-workspace-hides-clean-mismatched-result-test
  (let [black-litterman-state
        (ready-workspace-state
         {:kind :black-litterman
          :views [{:kind :absolute
                   :instrument-id "perp:BTC"
                   :return 0.2
                   :confidence 0.75
                   :weights {"perp:BTC" 1}}]})
        historical-state
        (assoc-in black-litterman-state
                  [:portfolio :optimizer :draft :return-model]
                  {:kind :historical-mean})
        view-node (portfolio-view/portfolio-view
                   (assoc-in black-litterman-state
                             [:portfolio :optimizer :last-successful-run]
                             (solved-run-for-state historical-state)))]
    (is (nil? (node-by-role view-node "portfolio-optimizer-view-weights"))
        "A clean Black-Litterman draft must not expose weights from a historical-mean run.")
    (is (nil? (node-by-role view-node "portfolio-optimizer-results-link"))
        "The setup rail should not navigate to mismatched retained results.")
    (is (nil? (node-by-role view-node "portfolio-optimizer-results-surface")))))

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
                   "Loading optimizer history for the selected assets."))
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
        strings (set (collect-strings view-node))
        bar-fill (fn [step-role]
                   (let [step-node (node-by-role view-node step-role)]
                     {:classes (set (remove nil? (get-in step-node [3 2 1 :class])))
                      :width (get-in step-node [3 2 1 :style :width])}))
        running-fill (bar-fill "portfolio-optimizer-progress-step-fetch-returns")
        pending-fill (bar-fill "portfolio-optimizer-progress-step-solve")]
    (is (= true (get-in run-button [1 :disabled])))
    (is (some? (node-by-role view-node "portfolio-optimizer-progress-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-progress-step-fetch-returns")))
    (is (contains? strings "Optimization In Progress"))
    (is (contains? strings "Computing"))
    (is (contains? strings "fetch returns matrix"))
    (is (contains? strings "QP solve"))
    (is (contains? (:classes running-fill) "animate-pulse"))
    (is (contains? (:classes running-fill) "transition-[width]"))
    (is (= "50%" (:width running-fill)))
    (is (not (contains? (:classes pending-fill) "animate-pulse")))
    (is (= "0%" (:width pending-fill)))))

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

(deftest portfolio-optimizer-workspace-explains-net-min-capacity-presolve-test
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
                                         :constraints {:long-only? false
                                                       :max-asset-weight 1.0
                                                       :gross-max 100
                                                       :net-min 5
                                                       :net-max 50}}
                                 :run-state {:status :infeasible
                                             :completed-at-ms 3000
                                             :result {:status :infeasible
                                                      :reason :constraint-presolve
                                                      :details
                                                      {:violations
                                                       [{:code :sum-upper-below-net-min
                                                         :sum-upper 2
                                                         :net-min 5}]}}}}}})
        strings (collect-strings view-node)
        contains-text? (fn [text]
                         (some #(str/includes? % text) strings))
        max-asset-input (node-by-role view-node
                                      "portfolio-optimizer-constraint-max-asset-weight-input")
        net-min-input (node-by-role view-node
                                    "portfolio-optimizer-constraint-net-min-input")
        net-max-input (node-by-role view-node
                                    "portfolio-optimizer-constraint-net-max-input")]
    (is (some? (node-by-role view-node "portfolio-optimizer-infeasible-banner")))
    (is (contains-text?
         "Maximum possible net exposure is 2, below the minimum of 5."))
    (is (contains-text?
         "Lower Net Exposure Min, add eligible long assets, or raise Max Asset Weight."))
    (is (contains-text? "sum-upper-below-net-min"))
    (is (= "true" (get-in max-asset-input [1 :data-infeasible])))
    (is (= "true" (get-in max-asset-input [1 :aria-invalid])))
    (is (= "true" (get-in net-min-input [1 :data-infeasible])))
    (is (= "true" (get-in net-min-input [1 :aria-invalid])))
    (is (nil? (get-in net-max-input [1 :data-infeasible])))
    (is (nil? (get-in net-max-input [1 :aria-invalid])))))

(deftest portfolio-optimizer-workspace-renders-solver-rejection-diagnostics-test
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
                                         :constraints {:long-only? true}}
                                 :run-state {:status :infeasible
                                             :completed-at-ms 3000
                                             :result {:status :infeasible
                                                      :reason :solver-returned-invalid-solution
                                                      :message "The solver reported a solution, but it violated optimizer constraints."
                                                      :details
                                                      {:violations
                                                       [{:code :solver-result-equality-violation
                                                         :message "net-exposure expected 1.0000 but solver returned 0.0000."}
                                                        {:code :solver-result-turnover-violation
                                                         :message "turnover limit 2.0000 but solver returned 31.3133."}
                                                        {:code :solver-result-equality-violation
                                                         :message "net-exposure expected 1.0000 but solver returned 0.0000."}
                                                        {:code :solver-result-turnover-violation
                                                         :message "turnover limit 2.0000 but solver returned 31.3133."}]}}}}}})
        strings (collect-strings view-node)
        string-set (set strings)
        string-count (fn [value]
                       (count (filter #{value} strings)))]
    (is (some? (node-by-role view-node "portfolio-optimizer-infeasible-banner")))
    (is (contains? string-set
                   "The solver reported a solution, but it violated optimizer constraints."))
    (is (contains? string-set
                   "net-exposure expected 1.0000 but solver returned 0.0000."))
    (is (contains? string-set
                   "turnover limit 2.0000 but solver returned 31.3133."))
    (is (= 1 (string-count "solver-result-equality-violation")))
    (is (= 1 (string-count "solver-result-turnover-violation")))))

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
