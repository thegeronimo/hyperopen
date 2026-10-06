(ns hyperopen.startup.collaborators.vault-equities
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.api.promise-effects :as promise-effects]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.vaults.infrastructure.user-equity-poller :as user-equity-poller]))

(defn- requested-address-current?
  [store requested-address]
  (let [requested-address* (account-context/normalize-address requested-address)
        active-address* (account-context/normalize-address
                         (account-context/effective-account-address @store))]
    (and requested-address*
         active-address*
         (= requested-address* active-address*))))

(defn fetch-user-vault-equities!
  ([api-ops store address]
   (fetch-user-vault-equities! api-ops store address {}))
  ([{:keys [request-user-vault-equities!]} store address opts]
   (if-let [requested-address (account-context/normalize-address address)]
     (if (requested-address-current? store requested-address)
       (do
         (swap! store api-projections/begin-user-vault-equities-load requested-address)
         (let [request-id (get-in @store [:vaults :user-equities-request-id])]
           (-> (request-user-vault-equities! requested-address opts)
               (.then (fn [rows]
                        (when (requested-address-current? store requested-address)
                          (if (some? request-id)
                            (swap! store api-projections/apply-user-vault-equities-success
                                   requested-address request-id rows)
                            (swap! store api-projections/apply-user-vault-equities-success
                                   requested-address rows)))
                        rows))
               (.catch (fn [err]
                         (when (requested-address-current? store requested-address)
                           (if (some? request-id)
                             (swap! store api-projections/apply-user-vault-equities-error
                                    requested-address request-id err)
                             (swap! store api-projections/apply-user-vault-equities-error
                                    requested-address err)))
                         (promise-effects/reject-error err))))))
       (js/Promise.resolve nil))
     (js/Promise.resolve nil))))

(defn collaborators
  [api-ops]
  {:fetch-user-vault-equities! (fn
                                 ([store address]
                                  (fetch-user-vault-equities! api-ops store address))
                                 ([store address opts]
                                  (fetch-user-vault-equities! api-ops store address opts)))
   :start-user-vault-equity-poller! (fn [store address]
                                      (user-equity-poller/install-user-equity-poller!
                                       {:store store
                                        :address address
                                        :fetch-user-vault-equities!
                                        (fn
                                          ([store* address*]
                                           (fetch-user-vault-equities! api-ops store* address*))
                                          ([store* address* opts]
                                           (fetch-user-vault-equities! api-ops store* address* opts)))}))
   :stop-user-vault-equity-poller! user-equity-poller/stop-user-equity-poller!})
