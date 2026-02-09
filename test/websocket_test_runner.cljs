(ns websocket-test-runner
  (:require [cljs.test :refer-macros [run-tests]]
            [hyperopen.wallet.address-watcher-test]
            [hyperopen.websocket.acl.hyperliquid-test]
            [hyperopen.websocket.application.runtime-test]
            [hyperopen.websocket.application.runtime-reducer-test]
            [hyperopen.websocket.client-test]
            [hyperopen.websocket.health-test]
            [hyperopen.websocket.domain.policy-test]
            [hyperopen.websocket.orderbook-policy-test]
            [hyperopen.websocket.trades-policy-test]
            [hyperopen.websocket.user-test]))

(defn run-websocket-tests
  []
  (run-tests 'hyperopen.websocket.acl.hyperliquid-test
             'hyperopen.websocket.application.runtime-test
             'hyperopen.websocket.application.runtime-reducer-test
             'hyperopen.websocket.health-test
             'hyperopen.websocket.domain.policy-test
             'hyperopen.websocket.client-test
             'hyperopen.websocket.user-test
             'hyperopen.wallet.address-watcher-test
             'hyperopen.websocket.orderbook-policy-test
             'hyperopen.websocket.trades-policy-test))

(defn -main
  []
  (println "\n=== Running Hyperopen WebSocket Tests ===")
  (let [results (run-websocket-tests)]
    (println "\n=== WebSocket Test Results ===")
    results))
