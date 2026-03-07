(ns hyperopen.websocket.user
  (:require [hyperopen.telemetry :as telemetry]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.user-runtime.handlers :as handlers]
            [hyperopen.websocket.user-runtime.subscriptions :as subscriptions]))

(defn sync-perp-dex-clearinghouse-subscriptions!
  [address dex-names]
  (subscriptions/sync-perp-dex-clearinghouse-subscriptions! address dex-names))

(defn subscribe-user!
  [address]
  (subscriptions/subscribe-user! address))

(defn unsubscribe-user!
  [address]
  (subscriptions/unsubscribe-user! address))

(defn create-user-handler
  [subscribe-fn unsubscribe-fn]
  (subscriptions/create-user-handler subscribe-fn unsubscribe-fn))

(defn init!
  [store]
  (ws-client/register-handler! "openOrders" (handlers/open-orders-handler store))
  (ws-client/register-handler! "userFills" (handlers/user-fills-handler store))
  (ws-client/register-handler! "userFundings" (handlers/user-fundings-handler store))
  (ws-client/register-handler! "userNonFundingLedgerUpdates" (handlers/user-ledger-handler store))
  (ws-client/register-handler! "clearinghouseState" (handlers/clearinghouse-state-handler store))
  (telemetry/log! "User websocket handlers initialized"))
