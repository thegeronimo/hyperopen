(ns hyperopen.views.portfolio.optimize.results-panel-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio.optimize.results-panel :as results-panel]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [click-actions collect-strings node-attr node-by-role solved-result]]))

(deftest results-panel-renders-v1-results-workspace-shell-test
  (let [draft {:objective {:kind :target-volatility}
               :metadata {:dirty? true}}
        view-node (results-panel/results-panel
                   {:result solved-result
                    :computed-at-ms 2600}
                   draft
                   {:stale? true
                    :frontier-overlay-mode :standalone})
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
    (is (some? (node-by-role view-node "portfolio-optimizer-target-exposure-table")))
    (is (some? (node-by-role view-node "portfolio-optimizer-result-warnings")))
    (is (some? (node-by-role view-node "portfolio-optimizer-diagnostics-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-rebalance-preview")))
    (is (contains? strings "Allocation"))
    (is (contains? strings "How much to trust this"))
    (is (contains? strings "low-invested-exposure"))
    (is (contains? strings "partially-blocked"))))

(deftest results-panel-renders-constrain-frontier-checkbox-above-chart-test
  (let [draft {:objective {:kind :minimum-variance}}
        result (assoc solved-result
                      :frontiers
                      {:unconstrained [{:id 0
                                        :expected-return 0.08
                                        :volatility 0.16
                                        :sharpe 0.5}
                                       {:id 1
                                        :expected-return 0.18
                                        :volatility 0.42
                                        :sharpe 0.43}
                                       {:id 2
                                        :expected-return 0.3
                                        :volatility 0.8
                                        :sharpe 0.375}]
                       :constrained (:frontier solved-result)}
                      :frontier-summaries
                      {:unconstrained {:source :display-sweep
                                       :constraint-mode :unconstrained
                                       :point-count 3}
                       :constrained {:source :display-sweep
                                     :constraint-mode :constrained
                                     :point-count 2}})
        default-view (results-panel/results-panel
                      {:result result
                       :computed-at-ms 2600}
                      draft
                      {:frontier-overlay-mode :standalone})
        constrained-view (results-panel/results-panel
                          {:result result
                           :computed-at-ms 2600}
                          draft
                          {:frontier-overlay-mode :standalone
                           :constrain-frontier? true})
        checkbox (node-by-role default-view
                               "portfolio-optimizer-constrain-frontier-checkbox")
        constrained-checkbox (node-by-role
                              constrained-view
                              "portfolio-optimizer-constrain-frontier-checkbox")]
    (is (some? checkbox))
    (is (= false (node-attr checkbox :checked)))
    (is (= true (node-attr constrained-checkbox :checked)))
    (is (= [[:actions/set-portfolio-optimizer-constrain-frontier
             :event.target/checked]]
           (get-in checkbox [1 :on :change])))
    (is (some #{"Constrain Frontier"} (collect-strings default-view)))
    (is (some #{"3 points"} (collect-strings default-view)))
    (is (some #{"2 points"} (collect-strings constrained-view)))))
