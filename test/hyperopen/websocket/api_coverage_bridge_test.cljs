(ns hyperopen.websocket.api-coverage-bridge-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api-test]
            [hyperopen.api.compat-test]
            [hyperopen.api.default-test]
            [hyperopen.api.errors-test]
            [hyperopen.api.fetch-compat-test]
            [hyperopen.api.instance-test]
            [hyperopen.api.market-loader-test]
            [hyperopen.api.projections-test]
            [hyperopen.api.promise-effects-test]
            [hyperopen.api.runtime-test]
            [hyperopen.api.service-test]))

(deftest api-coverage-bridge-loads-targeted-api-suites-test
  (is true))
