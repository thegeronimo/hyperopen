(ns hyperopen.websocket.api-coverage-bridge-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api-test]
            [hyperopen.api.compat-test]
            [hyperopen.api.default-test]
            [hyperopen.api.errors-test]
            [hyperopen.api.fetch-compat-test]
            [hyperopen.api.instance-test]
            [hyperopen.api.market-loader-test]
            [hyperopen.api.projections.api-wallets-test]
            [hyperopen.api.projections.asset-selector-test]
            [hyperopen.api.projections.facade-contract-test]
            [hyperopen.api.projections.funding-test]
            [hyperopen.api.projections.leaderboard-test]
            [hyperopen.api.projections.market-test]
            [hyperopen.api.projections.orders-test]
            [hyperopen.api.projections.portfolio-test]
            [hyperopen.api.projections.staking-test]
            [hyperopen.api.projections.user-abstraction-test]
            [hyperopen.api.projections.user-fees-test]
            [hyperopen.api.projections.vaults-test]
            [hyperopen.api.promise-effects-test]
            [hyperopen.api.runtime-test]
            [hyperopen.api.service-test]
            [hyperopen.api.trading.debug-exchange-simulator-test]))

(deftest api-coverage-bridge-loads-targeted-api-suites-test
  (is true))
