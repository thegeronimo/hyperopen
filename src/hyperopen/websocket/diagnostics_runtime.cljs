(ns hyperopen.websocket.diagnostics-runtime)

(defn- reset-group-match?
  [stream group]
  (case group
    :market_data (= :market_data (:group stream))
    :orders_oms (= :orders_oms (:group stream))
    :all true
    false))

(defn- reset-target-descriptors
  [health group]
  (->> (get health :streams {})
       vals
       (filter (fn [stream]
                 (and (:subscribed? stream)
                      (map? (:descriptor stream))
                      (reset-group-match? stream group))))
       (map :descriptor)
       distinct
       (sort-by pr-str)
       vec))

(defn- reset-event
  [group source]
  (if (= :auto-recover source)
    :auto-recover-market
    (case group
      :market_data :reset-market
      :orders_oms :reset-oms
      :all :reset-all
      :reset-unknown)))

(defn ws-reset-subscriptions!
  [{:keys [store
           group
           source
           get-health-snapshot
           effective-now-ms
           reset-subscriptions-cooldown-ms
           send-message!
           append-diagnostics-event!]}]
  (let [state @store
        health (get-health-snapshot)
        transport-state (get-in health [:transport :state])
        generated-at-ms (or (:generated-at-ms health) 0)
        now-ms (effective-now-ms generated-at-ms)
        in-progress? (boolean (get-in state [:websocket-ui :reset-in-progress?]))
        cooldown-until-ms (get-in state [:websocket-ui :reset-cooldown-until-ms])
        cooldown-active? (and (number? cooldown-until-ms)
                              (> cooldown-until-ms now-ms))
        blocked? (or in-progress?
                     cooldown-active?
                     (contains? #{:connecting :reconnecting} transport-state))
        group-key (if (= group :all) :all group)
        descriptors (reset-target-descriptors health group)]
    (when (and (not blocked?)
               (seq descriptors))
      (swap! store assoc-in [:websocket-ui :reset-in-progress?] true)
      (try
        (doseq [descriptor descriptors]
          (send-message! {:method "unsubscribe"
                          :subscription descriptor}))
        (doseq [descriptor descriptors]
          (send-message! {:method "subscribe"
                          :subscription descriptor}))
        (finally
          (swap! store assoc-in [:websocket-ui :reset-in-progress?] false)))
      (swap! store
             (fn [state*]
               (-> state*
                   (assoc-in [:websocket-ui :reset-cooldown-until-ms]
                             (+ now-ms reset-subscriptions-cooldown-ms))
                   (update-in [:websocket-ui :reset-counts group-key] (fnil inc 0)))))
      (append-diagnostics-event! store
                                 (reset-event group source)
                                 now-ms
                                 {:count (count descriptors)
                                  :source source}))))
