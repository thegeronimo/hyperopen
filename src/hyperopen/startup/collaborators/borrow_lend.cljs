(ns hyperopen.startup.collaborators.borrow-lend
  "Account bootstrap fetch of HyperCore lending state. Every account surface
  that totals the account (trade Account Equity, portfolio) needs the lending
  value, because supplied funds leave the spot balance."
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.borrow-lend.effects :as borrow-lend-effects]))

(defn fetch-borrow-lend-state!
  "Loads pool reserves (for oracle prices) and the account's positions. A
  response that arrives after the shown account changed is dropped, and a
  different account's positions are cleared first so its value never shows
  under the new one."
  ([api-ops store address]
   (fetch-borrow-lend-state! api-ops store address {}))
  ([{:keys [request-borrow-lend-reserve-states! request-borrow-lend-user-state!]} store address opts]
   (let [address* (account-context/normalize-address address)]
     (if-not (and address* (fn? request-borrow-lend-user-state!))
       (js/Promise.resolve nil)
       (do
         (when (not= address* (get-in @store [:borrow-lend :user-address]))
           (swap! store #(-> %
                             (assoc-in [:borrow-lend :user-state] nil)
                             (assoc-in [:borrow-lend :user-address] nil)
                             (assoc-in [:borrow-lend :total-supplied-usd] 0)
                             (assoc-in [:borrow-lend :total-borrowed-usd] 0))))
         (js/Promise.all
          #js [(-> (borrow-lend-effects/api-fetch-borrow-lend-reserves!
                    {:store store
                     :opts opts
                     :request-borrow-lend-reserve-states! request-borrow-lend-reserve-states!
                     :begin-borrow-lend-reserves-load api-projections/begin-borrow-lend-reserves-load
                     :apply-borrow-lend-reserves-success api-projections/apply-borrow-lend-reserves-success
                     :apply-borrow-lend-reserves-error api-projections/apply-borrow-lend-reserves-error})
                   (.catch (fn [_] nil)))
               (-> (borrow-lend-effects/api-fetch-borrow-lend-user-state!
                    {:store store
                     :address address
                     :opts opts
                     :request-borrow-lend-user-state! request-borrow-lend-user-state!
                     :begin-borrow-lend-user-state-load api-projections/begin-borrow-lend-user-state-load
                     :apply-borrow-lend-user-state-success api-projections/apply-borrow-lend-user-state-success
                     :apply-borrow-lend-user-state-error api-projections/apply-borrow-lend-user-state-error})
                   (.catch (fn [_] nil)))]))))))
