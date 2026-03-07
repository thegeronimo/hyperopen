(ns hyperopen.account.surface-service
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.account.surface-policy :as surface-policy]
            [hyperopen.platform :as platform]
            [hyperopen.websocket.migration-flags :as migration-flags]))

(def ^:private default-startup-stream-backfill-delay-ms
  450)

(defn- current-address
  [state resolve-current-address]
  ((or resolve-current-address
       account-context/effective-account-address)
   state))

(defn- active-address?
  [store address resolve-current-address]
  (= address
     (current-address @store resolve-current-address)))

(defn- call-when-fn!
  [f & args]
  (when (fn? f)
    (apply f args)))

(defn schedule-stream-backed-fallback!
  [{:keys [store
           address
           topic
           opts
           fetch-fn
           startup-stream-backfill-delay-ms
           set-timeout-fn
           resolve-current-address]
    :or {set-timeout-fn platform/set-timeout!}}]
  (let [delay-ms (max 0 (or startup-stream-backfill-delay-ms
                            default-startup-stream-backfill-delay-ms))]
    (when (and (some? store)
               (seq address)
               (string? topic)
               (fn? fetch-fn))
      (when-not (surface-policy/topic-usable-for-address? @store topic address)
        (set-timeout-fn
         (fn []
           (when (active-address? store address resolve-current-address)
             (when-not (surface-policy/topic-usable-for-address? @store topic address)
               (fetch-fn store address (or opts {})))))
         delay-ms)))))

(defn stage-b-account-bootstrap!
  [{:keys [store
           address
           dexs
           per-dex-stagger-ms
           sync-perp-dex-clearinghouse-subscriptions!
           fetch-frontend-open-orders!
           fetch-clearinghouse-state!
           set-timeout-fn
           resolve-current-address]
    :or {set-timeout-fn platform/set-timeout!}}]
  (let [dex-names (surface-policy/normalize-dex-names dexs)
        stagger-ms (max 0 (or per-dex-stagger-ms 0))]
    (call-when-fn! sync-perp-dex-clearinghouse-subscriptions! address dex-names)
    (doseq [[idx dex] (map-indexed vector dex-names)]
      (set-timeout-fn
       (fn []
         (when (active-address? store address resolve-current-address)
           (let [state @store
                 ws-first-enabled? (migration-flags/startup-bootstrap-ws-first-enabled? state)]
             (when (and (fn? fetch-frontend-open-orders!)
                        (or (not ws-first-enabled?)
                            (not (surface-policy/topic-usable-for-address? state
                                                                          "openOrders"
                                                                          address))))
               (fetch-frontend-open-orders! store address {:dex dex
                                                           :priority :low}))
             (when (and (fn? fetch-clearinghouse-state!)
                        (or (not ws-first-enabled?)
                            (not (surface-policy/topic-usable-for-address-and-dex?
                                  state
                                  "clearinghouseState"
                                  address
                                  dex))))
               (fetch-clearinghouse-state! store address dex {:priority :low})))))
       (* stagger-ms (inc idx))))))

(defn bootstrap-account-surfaces!
  [{:keys [store
           address
           fetch-frontend-open-orders!
           fetch-user-fills!
           fetch-spot-clearinghouse-state!
           fetch-user-abstraction!
           fetch-portfolio!
           fetch-user-fees!
           fetch-and-merge-funding-history!
           ensure-perp-dexs!
           startup-stream-backfill-delay-ms
           startup-funding-request-opts
           resolve-current-address
           log-fn]
    :or {log-fn (fn [& _] nil)}
    :as deps}]
  (when address
    (let [state @store
          ws-first-enabled? (migration-flags/startup-bootstrap-ws-first-enabled? state)
          run-stage-b! (or (:stage-b-account-bootstrap! deps)
                           (fn [stage-b-address dex-names]
                             (stage-b-account-bootstrap!
                              (assoc deps
                                     :address stage-b-address
                                     :dexs dex-names))))]
      (if ws-first-enabled?
        (do
          (schedule-stream-backed-fallback!
           {:store store
            :address address
            :topic "openOrders"
            :fetch-fn fetch-frontend-open-orders!
            :opts {:priority :high}
            :startup-stream-backfill-delay-ms startup-stream-backfill-delay-ms
            :resolve-current-address resolve-current-address})
          (schedule-stream-backed-fallback!
           {:store store
            :address address
            :topic "userFills"
            :fetch-fn fetch-user-fills!
            :opts {:priority :high}
            :startup-stream-backfill-delay-ms startup-stream-backfill-delay-ms
            :resolve-current-address resolve-current-address}))
        (do
          (call-when-fn! fetch-frontend-open-orders! store address {:priority :high})
          (call-when-fn! fetch-user-fills! store address {:priority :high})))
      (call-when-fn! fetch-spot-clearinghouse-state! store address {:priority :high})
      (call-when-fn! fetch-user-abstraction! store address {:priority :high})
      (call-when-fn! fetch-portfolio! store address {:priority :high})
      (call-when-fn! fetch-user-fees! store address {:priority :high})
      (if ws-first-enabled?
        (schedule-stream-backed-fallback!
         {:store store
          :address address
          :topic "userFundings"
          :fetch-fn fetch-and-merge-funding-history!
          :opts startup-funding-request-opts
          :startup-stream-backfill-delay-ms startup-stream-backfill-delay-ms
          :resolve-current-address resolve-current-address})
        (call-when-fn! fetch-and-merge-funding-history! store address startup-funding-request-opts))
      (when (fn? ensure-perp-dexs!)
        (-> (ensure-perp-dexs! store {:priority :low})
            (.then (fn [dexs]
                     (when (active-address? store address resolve-current-address)
                       (run-stage-b! address
                                     (surface-policy/normalize-dex-names dexs)))))
            (.catch (fn [err]
                      (log-fn "Error bootstrapping per-dex account data:" err))))))))

(defn- run-post-event-refresh!
  [{:keys [store
           address
           ensure-perp-dexs!
           sync-perp-dex-clearinghouse-subscriptions!
           refresh-open-orders!
           refresh-default-clearinghouse!
           refresh-spot-clearinghouse!
           refresh-perp-dex-clearinghouse!
           resolve-current-address
           refresh-spot?
           gate-perp-dex-by-stream?
           skip-perp-dex-when-subscribed-and-ready?
           perp-dex-snapshot-ready?
           log-fn
           error-prefix]
    :or {log-fn (fn [& _] nil)
         error-prefix "Error refreshing per-dex account surfaces:"}}]
  (when address
    (let [state @store
          ws-first-enabled? (migration-flags/order-fill-ws-first-enabled? state)
          open-orders-live? (and ws-first-enabled?
                                 (surface-policy/topic-usable-for-address? state
                                                                           "openOrders"
                                                                           address))
          webdata2-live? (and ws-first-enabled?
                              (surface-policy/topic-usable-for-address? state
                                                                        "webData2"
                                                                        address))]
      (when-not open-orders-live?
        (call-when-fn! refresh-open-orders! store address nil {:priority :high}))
      (when refresh-spot?
        (call-when-fn! refresh-spot-clearinghouse! store address {:priority :high}))
      (when-not webdata2-live?
        (call-when-fn! refresh-default-clearinghouse! store address {:priority :high}))
      (when (fn? ensure-perp-dexs!)
        (-> (ensure-perp-dexs! store {:priority :low})
            (.then (fn [dex-names]
                     (when (active-address? store address resolve-current-address)
                       (let [dex-names* (surface-policy/normalize-dex-names dex-names)]
                         (call-when-fn! sync-perp-dex-clearinghouse-subscriptions!
                                        address
                                        dex-names*)
                         (doseq [dex dex-names*]
                           (let [state* @store
                                 perp-dex-stream-usable?
                                 (and ws-first-enabled?
                                      gate-perp-dex-by-stream?
                                      (surface-policy/topic-usable-for-address-and-dex?
                                       state*
                                       "clearinghouseState"
                                       address
                                       dex))
                                 perp-dex-stream-subscribed?
                                 (and ws-first-enabled?
                                      gate-perp-dex-by-stream?
                                      (surface-policy/topic-subscribed-for-address-and-dex?
                                       state*
                                       "clearinghouseState"
                                       address
                                       dex))
                                 perp-dex-snapshot-ready?
                                 (when (fn? perp-dex-snapshot-ready?)
                                   (perp-dex-snapshot-ready? state* dex))
                                 skip-perp-dex-rest-refresh?
                                 (or perp-dex-stream-usable?
                                     (and skip-perp-dex-when-subscribed-and-ready?
                                          perp-dex-stream-subscribed?
                                          perp-dex-snapshot-ready?))]
                             (when-not open-orders-live?
                               (call-when-fn! refresh-open-orders!
                                              store
                                              address
                                              dex
                                              {:priority :low}))
                             (when (or (not gate-perp-dex-by-stream?)
                                       (not skip-perp-dex-rest-refresh?))
                               (call-when-fn! refresh-perp-dex-clearinghouse!
                                              store
                                              address
                                              dex
                                              {:priority :low}))))))))
            (.catch (fn [err]
                      (log-fn error-prefix err))))))))

(defn refresh-after-user-fill!
  [{:keys [store] :as deps}]
  (run-post-event-refresh!
   (assoc deps
          :refresh-spot? (surface-policy/spot-refresh-surface-active? @store)
          :gate-perp-dex-by-stream? true
          :skip-perp-dex-when-subscribed-and-ready? true
          :perp-dex-snapshot-ready? (fn [state dex]
                                      (some? (get-in state [:perp-dex-clearinghouse dex])))
          :error-prefix "Error refreshing per-dex account surfaces after user fill:")))

(defn refresh-after-order-mutation!
  [deps]
  (run-post-event-refresh!
   (assoc deps
          :refresh-spot? false
          :gate-perp-dex-by-stream? false
          :skip-perp-dex-when-subscribed-and-ready? false
          :error-prefix "Error refreshing per-dex account surfaces after order mutation:")))
