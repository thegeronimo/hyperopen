(ns test-runner
  (:require [hyperopen.test-support.node-env]
            [hyperopen.test-runner-support :as runner-support]
            [hyperopen.trading-crypto.module]
            [test-runner-generated :as generated-runner]))

(defn run-all-tests
  "Run all generated test namespaces with the provided cljs.test env."
  ([]
   (run-all-tests
    (runner-support/process-exit-reporting-env "\n=== Test Results ===")))
  ([env]
   (generated-runner/run-generated-tests env)))

(defn -main
  "Entry point for test runner"
  []
  (println "\n=== Running Hyperopen Tests ===")
  (run-all-tests))
