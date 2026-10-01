(ns hyperopen.runtime.effect-adapters.hyperevm
  "IO boundary for the HyperEVM effects: binds the reads in
   `hyperopen.hyperevm.effects` to the public HyperEVM RPC client, the
   rate-limited HyperCore info client, the clock and the logger, and the
   Transfer follow-ups (background receipt resolution, add to wallet) to
   the funding effect facade, dispatch and toasts."
  (:require [nexus.registry :as nxr]
            [hyperopen.api.default :as api]
            [hyperopen.funding.effects :as funding-effects]
            [hyperopen.hyperevm.effects :as hyperevm-effects]
            [hyperopen.hyperevm.infrastructure.rpc :as rpc]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.effect-adapters.order :as order-adapters]
            [hyperopen.telemetry :as telemetry]))

(defn fetch-hyperevm-balances-effect
  [_ store plan]
  (hyperevm-effects/fetch-hyperevm-balances!
   {:store store
    :plan plan
    :read-balances! rpc/read-balances!
    :rpc-deps {}
    :now-ms-fn platform/now-ms
    :log-fn telemetry/log!}))

(defn fetch-hyperevm-core-bridge-balance-effect
  [_ store index address]
  (hyperevm-effects/fetch-core-bridge-balance!
   {:store store
    :index index
    :address address
    :request-spot-clearinghouse-state! api/request-spot-clearinghouse-state!
    :now-ms-fn platform/now-ms
    :log-fn telemetry/log!}))

(defn fetch-hyperevm-core-account-status-effect
  [_ store owner]
  (hyperevm-effects/fetch-core-account-status!
   {:store store
    :owner owner
    :request-user-role! api/request-user-role!
    :log-fn telemetry/log!}))

(defn fetch-hyperevm-in-flight-receipt-effect
  [_ store owner tx-hash]
  (funding-effects/resolve-hyperevm-in-flight-receipt!
   {:store store
    :owner owner
    :tx-hash tx-hash
    :dispatch! nxr/dispatch
    :set-timeout-fn platform/set-timeout!
    :now-ms-fn platform/now-ms
    :show-toast! order-adapters/show-order-feedback-toast!}))

(defn wallet-watch-asset-effect
  [_ store request]
  (funding-effects/wallet-watch-asset!
   {:store store
    :request request
    :show-toast! order-adapters/show-order-feedback-toast!}))
