(ns hyperopen.portfolio.optimizer.query-state-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.query-state :as query-state]))

(deftest parse-optimizer-query-normalizes-route-params-test
  (is (= {:list-filter :partially-executed
          :list-sort :name-asc
          :workspace-panel :rebalance
          :results-tab :frontier
          :diagnostics-tab :constraints}
         (query-state/parse-optimizer-query
          "?ofilter=partial&osort=name-asc&oview=rebalance&otab=frontier&odiag=constraints"))))

(deftest apply-optimizer-query-state-merges-only-recognized-view-state-test
  (is (= {:portfolio-ui {:optimizer {:list-filter :executed
                                      :list-sort :updated-asc
                                      :workspace-panel :tracking
                                      :results-tab :diagnostics
                                      :diagnostics-tab :sensitivity
                                      :draft {:name "keep"}}}}
         (query-state/apply-optimizer-query-state
          {:portfolio-ui {:optimizer {:list-filter :active
                                      :list-sort :updated-desc
                                      :workspace-panel :setup
                                      :results-tab :allocation
                                      :diagnostics-tab :conditioning
                                      :draft {:name "keep"}}}}
          {:list-filter :executed
           :list-sort :updated-asc
           :workspace-panel :tracking
           :results-tab :diagnostics
           :diagnostics-tab :sensitivity}))))

(deftest optimizer-query-params-serializes-deterministic-shareable-snapshot-test
  (is (= [["ofilter" "saved"]
          ["osort" "name-desc"]
          ["oview" "results"]
          ["otab" "rebalance"]
          ["odiag" "data"]]
         (query-state/optimizer-query-params
          {:portfolio-ui {:optimizer {:list-filter :saved
                                      :list-sort :name-desc
                                      :workspace-panel :results
                                      :results-tab :rebalance
                                      :diagnostics-tab :data}}}))))

(deftest optimizer-query-state-defaults-invalid-values-test
  (is (= {:list-filter :active
          :list-sort :updated-desc
          :workspace-panel :setup
          :results-tab :allocation
          :diagnostics-tab :conditioning}
         (query-state/optimizer-query-state
          {:portfolio-ui {:optimizer {:list-filter :wat
                                      :list-sort :wat
                                      :workspace-panel :wat
                                      :results-tab :wat
                                      :diagnostics-tab :wat}}}))))
