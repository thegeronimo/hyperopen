(ns test-runner
  (:require [cljs.test :refer-macros [run-tests]]
            [hyperopen.asset-selector.markets-test]
            [hyperopen.utils.data-normalization-test]
            [hyperopen.utils.formatting-test]
            [hyperopen.views.asset-selector-view-test]
            [hyperopen.views.l2-orderbook-view-test]
            [hyperopen.state.trading-test]))

(defn run-all-tests
  "Run all test namespaces and return the results"
  []
  (run-tests 'hyperopen.utils.data-normalization-test
             'hyperopen.utils.formatting-test
             'hyperopen.asset-selector.markets-test
             'hyperopen.views.asset-selector-view-test
             'hyperopen.views.l2-orderbook-view-test
             'hyperopen.state.trading-test))

(defn -main
  "Entry point for test runner"
  []
  (println "\n=== Running Hyperopen Tests ===")
  (let [results (run-all-tests)]
    (println "\n=== Test Results ===")
    results))

;; Run tests when this namespace loads in test environment
(-main)
