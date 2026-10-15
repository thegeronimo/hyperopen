(ns hyperopen.order.effects.spot-refresh
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.telemetry :as telemetry]))

(def ^:private spot-asset-id-base 10000)

(def ^:private builder-deployed-perp-asset-id-base 100000)

(def ^:private outcome-asset-id-base 100000000)

(defn- spot-balance-asset-id?
  "Spot pairs (10000 + spotMeta index) and outcome markets (>= 100000000) both
  settle against the spot clearinghouse. Base-dex perps (< 10000) and
  builder-deployed perps (100000 + dex*10000 + idx) do not."
  [asset-id]
  (and (number? asset-id)
       (not (js/isNaN asset-id))
       (or (and (>= asset-id spot-asset-id-base)
                (< asset-id builder-deployed-perp-asset-id-base))
           (>= asset-id outcome-asset-id-base))))

(defn spot-balance-order-mutation?
  "True when an order or cancel request touches a market whose fills and holds
  live in the spot clearinghouse. There is no spot balance stream, so these
  mutations must refetch `spotClearinghouseState` to stay current."
  [request]
  (let [{:keys [orders cancels]} (:action request)]
    (boolean
     (some (comp spot-balance-asset-id? :a)
           (concat orders cancels)))))

(defn order-mutation-refresh-opts
  [request]
  {:refresh-spot? (spot-balance-order-mutation? request)})

(defn- active-wallet-address?
  [state address]
  (= (account-context/normalize-address address)
     (account-context/normalize-address (get-in state [:wallet :address]))))

(defn refresh-spot-clearinghouse-snapshot!
  [store address opts]
  (-> (api/request-spot-clearinghouse-state! address opts)
      (.then (fn [data]
               (swap! store
                      (fn [state]
                        (if (active-wallet-address? state address)
                          (api-projections/apply-spot-balances-success state data)
                          state)))))
      (.catch (fn [err]
                (swap! store
                       (fn [state]
                         (if (active-wallet-address? state address)
                           (api-projections/apply-spot-balances-error state err)
                           state)))
                (telemetry/log! "Error refreshing spot clearinghouse state after order mutation:" err)))))
