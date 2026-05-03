(ns hyperopen.order.effects.spot-refresh
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.telemetry :as telemetry]))

(def ^:private outcome-asset-id-base 100000000)

(defn outcome-order-mutation?
  [request]
  (let [orders (get-in request [:action :orders])]
    (boolean
     (some (fn [order]
             (let [asset-id (:a order)]
               (and (number? asset-id)
                    (not (js/isNaN asset-id))
                    (>= asset-id outcome-asset-id-base))))
           orders))))

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
