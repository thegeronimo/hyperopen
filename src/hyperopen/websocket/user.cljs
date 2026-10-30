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
  "Registers the account stream handlers. Optional raw evidence taps
  (`:on-raw-user-fills`, `:on-raw-open-orders`) are called as
  `(f store msg received-at-ms)` with every raw message on their channel."
  ([store]
   (init! store {}))
  ([store {:keys [on-raw-user-fills on-raw-open-orders]}]
   (ws-client/register-handler! "openOrders"
                                (handlers/open-orders-handler store {:on-raw-message on-raw-open-orders}))
   (ws-client/register-handler! "twapStates" (handlers/twap-states-handler store))
   (ws-client/register-handler! "userFills"
                                (handlers/user-fills-handler store {:on-raw-message on-raw-user-fills}))
   (ws-client/register-handler! "userFundings" (handlers/user-fundings-handler store))
   (ws-client/register-handler! "userTwapHistory" (handlers/user-twap-history-handler store))
   (ws-client/register-handler! "userTwapSliceFills" (handlers/user-twap-slice-fills-handler store))
   (ws-client/register-handler! "userNonFundingLedgerUpdates" (handlers/user-ledger-handler store))
   (ws-client/register-handler! "clearinghouseState" (handlers/clearinghouse-state-handler store))
   (telemetry/log! "User websocket handlers initialized")))
