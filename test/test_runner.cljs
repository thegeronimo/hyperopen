(ns test-runner
  (:require [cljs.test :refer-macros [run-tests]]
            [hyperopen.api-test]
            [hyperopen.websocket.acl.hyperliquid-test]
            [hyperopen.websocket.application.runtime-test]
            [hyperopen.websocket.application.runtime-reducer-test]
            [hyperopen.websocket.diagnostics-actions-test]
            [hyperopen.websocket.diagnostics-payload-test]
            [hyperopen.websocket.diagnostics-runtime-test]
            [hyperopen.websocket.domain.policy-test]
            [hyperopen.websocket.health-projection-test]
            [hyperopen.asset-selector.markets-test]
            [hyperopen.asset-selector.markets-cache-test]
            [hyperopen.core-bootstrap-test]
            [hyperopen.utils.data-normalization-test]
            [hyperopen.utils.formatting-test]
            [hyperopen.utils.hl-signing-test]
            [hyperopen.utils.interval-test]
            [hyperopen.orderbook.price-aggregation-test]
            [hyperopen.wallet.address-watcher-test]
            [hyperopen.websocket.client-test]
            [hyperopen.websocket.orderbook-policy-test]
            [hyperopen.websocket.trades-policy-test]
            [hyperopen.views.active-asset-view-test]
            [hyperopen.views.app-shell-spacing-test]
            [hyperopen.views.asset-selector-view-test]
            [hyperopen.views.footer-view-test]
            [hyperopen.views.header-view-test]
            [hyperopen.views.trading-chart.core-test]
            [hyperopen.views.trading-chart.timeframe-dropdown-test]
            [hyperopen.views.trading-chart.utils.chart-options-test]
            [hyperopen.views.trading-chart.utils.data-processing-test]
            [hyperopen.views.trading-chart.utils.indicators-test]
            [hyperopen.views.l2-orderbook-view-test]
            [hyperopen.views.typography-scale-test]
            [hyperopen.views.trade.order-form-view-test]
            [hyperopen.startup.init-test]
            [hyperopen.state.trading-test]))

(defn run-all-tests
  "Run all test namespaces and return the results"
  []
  (run-tests 'hyperopen.api-test
             'hyperopen.websocket.acl.hyperliquid-test
             'hyperopen.websocket.application.runtime-test
             'hyperopen.websocket.application.runtime-reducer-test
             'hyperopen.websocket.diagnostics-actions-test
             'hyperopen.websocket.diagnostics-payload-test
             'hyperopen.websocket.diagnostics-runtime-test
             'hyperopen.websocket.domain.policy-test
             'hyperopen.websocket.health-projection-test
             'hyperopen.utils.data-normalization-test
             'hyperopen.utils.formatting-test
             'hyperopen.utils.hl-signing-test
             'hyperopen.utils.interval-test
             'hyperopen.asset-selector.markets-test
             'hyperopen.asset-selector.markets-cache-test
             'hyperopen.core-bootstrap-test
             'hyperopen.orderbook.price-aggregation-test
             'hyperopen.wallet.address-watcher-test
             'hyperopen.websocket.client-test
             'hyperopen.websocket.orderbook-policy-test
             'hyperopen.websocket.trades-policy-test
             'hyperopen.views.active-asset-view-test
             'hyperopen.views.app-shell-spacing-test
             'hyperopen.views.asset-selector-view-test
             'hyperopen.views.footer-view-test
             'hyperopen.views.header-view-test
             'hyperopen.views.trading-chart.core-test
             'hyperopen.views.trading-chart.timeframe-dropdown-test
             'hyperopen.views.trading-chart.utils.chart-options-test
             'hyperopen.views.trading-chart.utils.data-processing-test
             'hyperopen.views.trading-chart.utils.indicators-test
             'hyperopen.views.l2-orderbook-view-test
             'hyperopen.views.typography-scale-test
             'hyperopen.views.trade.order-form-view-test
             'hyperopen.startup.init-test
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
