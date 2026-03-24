(ns hyperopen.leaderboard.effects
  (:require [hyperopen.api.promise-effects :as promise-effects]
            [hyperopen.leaderboard.actions :as leaderboard-actions]))

(def ^:private excluded-vault-names
  #{"Liquidator"
    "Hyperliquidity Provider (HLP)"})

(def ^:private known-system-addresses
  #{"0xffffffffffffffffffffffffffffffffffffffff"})

(defn- route-active?
  [store]
  (leaderboard-actions/leaderboard-route?
   (get-in @store [:router :path] "")))

(defn- request-opts
  [opts]
  (dissoc (or opts {}) :skip-route-gate?))

(defn- excluded-addresses
  [vault-rows known-excluded-addresses]
  (let [vault-addresses (->> (or vault-rows [])
                             (filter (fn [row]
                                       (or (= :child (get-in row [:relationship :type]))
                                           (contains? excluded-vault-names (:name row)))))
                             (keep :vault-address)
                             (into #{}))]
    (into (into known-system-addresses vault-addresses)
          (keep identity)
          known-excluded-addresses)))

(defn api-fetch-leaderboard!
  [{:keys [store
           request-leaderboard!
           request-vault-index!
           begin-leaderboard-load
           apply-leaderboard-success
           apply-leaderboard-error
           known-excluded-addresses
           opts]
    :or {known-excluded-addresses #{}}}]
  (let [opts* (or opts {})
        route-gate-disabled? (true? (:skip-route-gate? opts*))]
    (if (or route-gate-disabled?
            (route-active? store))
      (let [request-opts* (request-opts opts*)]
        (swap! store begin-leaderboard-load)
        (-> (js/Promise.all
             #js [(request-leaderboard! request-opts*)
                  (request-vault-index! request-opts*)])
            (.then (fn [results]
                     (let [[leaderboard-rows vault-rows] (array-seq results)]
                       {:rows leaderboard-rows
                        :excluded-addresses (excluded-addresses vault-rows
                                                                known-excluded-addresses)})))
            (.then (promise-effects/apply-success-and-return
                    store
                    apply-leaderboard-success))
            (.catch (promise-effects/apply-error-and-reject
                     store
                     apply-leaderboard-error))))
      (js/Promise.resolve nil))))
