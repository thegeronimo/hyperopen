(ns hyperopen.vaults.effects.user-equities
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.api.promise-effects :as promise-effects]))

(defn requested-account-active?
  [store requested-address]
  (= (account-context/normalize-address requested-address)
     (account-context/effective-account-address @store)))

(defn api-fetch-user-vault-equities!
  [allow-route?
   route-scoped-request-opts
   {:keys [store
           address
           request-user-vault-equities!
           begin-user-vault-equities-load
           apply-user-vault-equities-success
           apply-user-vault-equities-error
           opts]}]
  (if (and (allow-route? store opts false)
           (requested-account-active? store address))
    (let [requested-address (account-context/normalize-address address)
          request-opts* (route-scoped-request-opts store opts false)]
      (swap! store begin-user-vault-equities-load requested-address)
      (let [request-id (get-in @store [:vaults :user-equities-request-id])]
        (-> (request-user-vault-equities! requested-address request-opts*)
            (.then (fn [rows]
                     (when (requested-account-active? store requested-address)
                       (if (some? request-id)
                         (swap! store apply-user-vault-equities-success
                                requested-address request-id rows)
                         (swap! store apply-user-vault-equities-success
                                requested-address rows)))
                     rows))
            (.catch (fn [err]
                      (when (requested-account-active? store requested-address)
                        (if (some? request-id)
                          (swap! store apply-user-vault-equities-error
                                 requested-address request-id err)
                          (swap! store apply-user-vault-equities-error
                                 requested-address err)))
                      (promise-effects/reject-error err))))))
    (js/Promise.resolve nil)))
