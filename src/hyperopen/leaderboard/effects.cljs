(ns hyperopen.leaderboard.effects
  (:require [hyperopen.api.promise-effects :as promise-effects]
            [hyperopen.leaderboard.cache :as leaderboard-cache]
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
  (dissoc (or opts {}) :skip-route-gate? :force-refresh?))

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
           load-leaderboard-cache-record!
           persist-leaderboard-cache-record!
           begin-leaderboard-load
           apply-leaderboard-cache-hydration
           apply-leaderboard-success
           apply-leaderboard-error
           known-excluded-addresses
           now-ms-fn
           opts]
    :or {known-excluded-addresses #{}
         now-ms-fn js/Date.now}}]
  (letfn [(->promise [result]
            (if (instance? js/Promise result)
              result
              (js/Promise.resolve result)))
          (warn-cache-error! [message error]
            (js/console.warn message error))
          (fresh-state? [state]
            (leaderboard-cache/fresh-leaderboard-snapshot?
             (get-in state [:leaderboard :loaded-at-ms])
             {:now-ms-fn now-ms-fn}))
          (current-state-record [state]
            {:saved-at-ms (get-in state [:leaderboard :loaded-at-ms])
             :rows (get-in state [:leaderboard :rows])
             :excluded-addresses (get-in state [:leaderboard :excluded-addresses])})
          (hydrate-record! [record]
            (when (fn? apply-leaderboard-cache-hydration)
              (swap! store apply-leaderboard-cache-hydration record)))
          (persist-cache! [payload]
            (if-not (fn? persist-leaderboard-cache-record!)
              (js/Promise.resolve payload)
              (-> (->promise (persist-leaderboard-cache-record! payload))
                  (.catch (fn [error]
                            (warn-cache-error! "Failed to persist leaderboard cache:" error)
                            false))
                  (.then (fn [_]
                           payload)))))
          (request-network! [request-opts*]
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
                (.then persist-cache!)
                (.catch (promise-effects/apply-error-and-reject
                         store
                         apply-leaderboard-error))))]
    (let [opts* (or opts {})
          route-gate-disabled? (true? (:skip-route-gate? opts*))
          force-refresh? (true? (:force-refresh? opts*))]
      (if-not (or route-gate-disabled?
                  (route-active? store))
        (js/Promise.resolve nil)
        (let [request-opts* (request-opts opts*)
              initial-state @store
              loading? (true? (get-in initial-state [:leaderboard :loading?]))
              current-loaded-at (get-in initial-state [:leaderboard :loaded-at-ms])]
          (cond
            force-refresh?
            (request-network! request-opts*)

            loading?
            (js/Promise.resolve {:source :in-flight})

            (fresh-state? initial-state)
            (do
              (hydrate-record! (current-state-record initial-state))
              (js/Promise.resolve {:source :memory}))

            (number? current-loaded-at)
            (request-network! request-opts*)

            :else
            (-> (->promise (when (fn? load-leaderboard-cache-record!)
                             (load-leaderboard-cache-record!)))
                (.catch (fn [error]
                          (warn-cache-error! "Failed to load leaderboard cache:" error)
                          nil))
                (.then (fn [cache-record]
                         (let [state* @store]
                           (cond
                             (fresh-state? state*)
                             (do
                               (hydrate-record! (current-state-record state*))
                               {:source :memory})

                             (and cache-record
                                  (leaderboard-cache/fresh-leaderboard-snapshot?
                                   (:saved-at-ms cache-record)
                                   {:now-ms-fn now-ms-fn})
                                  (>= (:saved-at-ms cache-record 0)
                                      (get-in state* [:leaderboard :loaded-at-ms] 0)))
                             (do
                               (hydrate-record! cache-record)
                               {:source :cache})

                             :else
                             (request-network! request-opts*))))))))))))
