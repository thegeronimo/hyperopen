(ns test-runner
  (:require [hyperopen.test-runner-support :as runner-support]
            [hyperopen.trading-crypto.module]
            [test-runner-generated :as generated-runner]))

(defn run-all-tests
  "Run all test namespaces and return the results"
  []
  (generated-runner/run-generated-tests))

(defn -main
  "Entry point for test runner"
  []
  (println "\n=== Running Hyperopen Tests ===")
  (let [results (run-all-tests)]
    (println "\n=== Test Results ===")
    (runner-support/apply-process-exit! results)))

;; Run tests when this namespace loads in test environment
(-main)
