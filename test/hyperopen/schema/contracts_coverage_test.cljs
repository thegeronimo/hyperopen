(ns hyperopen.schema.contracts-coverage-test
  (:require [clojure.set :as set]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.registry.runtime :as runtime-registry]
            [hyperopen.schema.contracts :as contracts]))

(def ^:private high-risk-action-ids
  #{:actions/navigate
    :actions/select-asset
    :actions/select-chart-timeframe
    :actions/submit-order
    :actions/cancel-order
    :actions/load-user-data
    :actions/connect-wallet
    :actions/disconnect-wallet
    :actions/enable-agent-trading
    :actions/set-agent-storage-mode
    :actions/toggle-ws-diagnostics
    :actions/close-ws-diagnostics
    :actions/toggle-ws-diagnostics-sensitive
    :actions/ws-diagnostics-reconnect-now
    :actions/ws-diagnostics-copy
    :actions/ws-diagnostics-reset-market-subscriptions
    :actions/ws-diagnostics-reset-orders-subscriptions
    :actions/ws-diagnostics-reset-all-subscriptions
    :actions/select-account-info-tab
    :actions/set-funding-history-filters
    :actions/toggle-funding-history-filter-open
    :actions/toggle-funding-history-filter-coin
    :actions/reset-funding-history-filter-draft
    :actions/apply-funding-history-filters
    :actions/view-all-funding-history
    :actions/export-funding-history-csv
    :actions/set-funding-history-page-size
    :actions/set-funding-history-page
    :actions/next-funding-history-page
    :actions/prev-funding-history-page
    :actions/set-funding-history-page-input
    :actions/apply-funding-history-page-input
    :actions/handle-funding-history-page-input-keydown
    :actions/set-trade-history-page-size
    :actions/set-trade-history-page
    :actions/next-trade-history-page
    :actions/prev-trade-history-page
    :actions/set-trade-history-page-input
    :actions/apply-trade-history-page-input
    :actions/handle-trade-history-page-input-keydown
    :actions/set-order-history-page-size
    :actions/set-order-history-page
    :actions/next-order-history-page
    :actions/prev-order-history-page
    :actions/set-order-history-page-input
    :actions/apply-order-history-page-input
    :actions/handle-order-history-page-input-keydown
    :actions/refresh-order-history})

(deftest contracted-action-ids-match-runtime-registered-action-ids-test
  (let [registered (runtime-registry/registered-action-ids)
        contracted (contracts/contracted-action-ids)]
    (is (= registered contracted)
        (str "Action contract drift detected. "
             "missing=" (pr-str (set/difference registered contracted))
             " extra=" (pr-str (set/difference contracted registered))))))

(deftest contracted-effect-ids-match-runtime-registered-effect-ids-test
  (let [registered (runtime-registry/registered-effect-ids)
        contracted (contracts/contracted-effect-ids)]
    (is (= registered contracted)
        (str "Effect contract drift detected. "
             "missing=" (pr-str (set/difference registered contracted))
             " extra=" (pr-str (set/difference contracted registered))))))

(deftest high-risk-actions-do-not-use-any-args-fallback-test
  (let [fallback-ids (contracts/action-ids-using-any-args)]
    (is (empty? (set/intersection high-risk-action-ids fallback-ids))
        (str "High-risk actions must not use ::any-args fallback. "
             "offenders=" (pr-str (set/intersection high-risk-action-ids fallback-ids))))))

(deftest no-registered-action-uses-any-args-fallback-test
  (let [fallback-ids (contracts/action-ids-using-any-args)]
    (is (empty? fallback-ids)
        (str "Phase 2 requires no ::any-args action contracts. "
             "offenders=" (pr-str fallback-ids)))))
