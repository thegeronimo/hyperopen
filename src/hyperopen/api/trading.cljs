(ns hyperopen.api.trading
  (:require [hyperopen.api.trading.agent-actions :as agent-actions]
            [hyperopen.api.trading.cancel-request :as cancel-request]
            [hyperopen.api.trading.debug-exchange-simulator :as debug-exchange-simulator]
            [hyperopen.api.trading.http :as http]
            [hyperopen.api.trading.user-actions :as user-actions]))

(def exchange-url http/exchange-url)
(def info-url http/info-url)

(defn set-debug-exchange-simulator!
  [simulator]
  (debug-exchange-simulator/install! simulator))

(defn clear-debug-exchange-simulator!
  []
  (debug-exchange-simulator/clear!))

(defn debug-exchange-simulator-snapshot
  []
  (debug-exchange-simulator/snapshot))

(defn- safe-private-key->agent-address
  ([private-key]
   (agent-actions/safe-private-key->agent-address private-key))
  ([crypto private-key]
   (agent-actions/safe-private-key->agent-address crypto private-key)))

(defn- next-nonce
  [cursor]
  (http/next-nonce cursor))

(defn- parse-json!
  [resp]
  (http/parse-json! resp))

(defn- nonce-error-response?
  [resp]
  (http/nonce-error-response? resp))

(defn- parse-chain-id-int
  [value]
  (user-actions/parse-chain-id-int value))

(defn- resolve-user-signing-context
  [store]
  (user-actions/resolve-user-signing-context store))

(defn- post-signed-action!
  ([action nonce signature]
   (http/post-signed-action! action nonce signature))
  ([action nonce signature options]
   (http/post-signed-action! action nonce signature options)))

(defn- should-invalidate-missing-api-wallet-session!
  [owner-address session]
  (agent-actions/should-invalidate-missing-api-wallet-session! owner-address session))

(defn- sign-and-post-agent-action!
  ([store owner-address action]
   (agent-actions/sign-and-post-agent-action! store owner-address action))
  ([store owner-address action raw-options]
   (agent-actions/sign-and-post-agent-action! store owner-address action raw-options)))

(defn enable-trading-recovery-error?
  [value]
  (http/enable-trading-recovery-error? value))

(defn resolve-cancel-order-oid
  [order]
  (cancel-request/resolve-cancel-order-oid order))

(defn build-cancel-order-request
  [state order]
  (cancel-request/build-cancel-order-request state order))

(defn build-cancel-orders-request
  [state orders]
  (cancel-request/build-cancel-orders-request state orders))

(defn build-cancel-twap-request
  [state twap]
  (cancel-request/build-cancel-twap-request state twap))

(defn submit-order!
  [store address action]
  (agent-actions/submit-order! store address action))

(defn cancel-order!
  [store address action]
  (agent-actions/cancel-order! store address action))

(defn submit-vault-transfer!
  [store address action]
  (agent-actions/submit-vault-transfer! store address action))

(defn schedule-cancel!
  ([store address cancel-at-ms]
   (agent-actions/schedule-cancel! store address cancel-at-ms)))

(defn approve-agent!
  [store address action]
  (user-actions/approve-agent! store address action))

(defn submit-usd-class-transfer!
  [store address action]
  (user-actions/submit-usd-class-transfer! store address action))

(defn submit-send-asset!
  [store address action]
  (user-actions/submit-send-asset! store address action))

(defn submit-c-deposit!
  [store address action]
  (user-actions/submit-c-deposit! store address action))

(defn submit-c-withdraw!
  [store address action]
  (user-actions/submit-c-withdraw! store address action))

(defn submit-token-delegate!
  [store address action]
  (user-actions/submit-token-delegate! store address action))

(defn submit-withdraw3!
  [store address action]
  (user-actions/submit-withdraw3! store address action))
