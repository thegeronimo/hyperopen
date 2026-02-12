(ns hyperopen.websocket.health-projection)

(defn websocket-health-fingerprint
  [health]
  {:transport/state (get-in health [:transport :state])
   :transport/freshness (get-in health [:transport :freshness])
   :groups/orders_oms (get-in health [:groups :orders_oms :worst-status])
   :groups/market_data (get-in health [:groups :market_data :worst-status])
   :groups/account (get-in health [:groups :account :worst-status])
   :gap/orders_oms (boolean (get-in health [:groups :orders_oms :gap-detected?]))
   :gap/market_data (boolean (get-in health [:groups :market_data :gap-detected?]))
   :gap/account (boolean (get-in health [:groups :account :gap-detected?]))})

(defn append-diagnostics-event
  [state event at-ms details timeline-limit]
  (let [entry (cond-> {:event event
                       :at-ms at-ms}
                (map? details) (assoc :details details))
        timeline (conj (vec (get-in state [:websocket-ui :diagnostics-timeline] [])) entry)
        max-start (max 0 (- (count timeline) timeline-limit))
        bounded (subvec timeline max-start)]
    (assoc-in state [:websocket-ui :diagnostics-timeline] bounded)))

(defn stream-age-ms
  [generated-at-ms last-payload-at-ms]
  (when (and (number? generated-at-ms)
             (number? last-payload-at-ms))
    (max 0 (- generated-at-ms last-payload-at-ms))))

(defn delayed-market-stream-severe?
  [health severe-threshold-ms]
  (let [generated-at-ms (:generated-at-ms health)]
    (boolean
     (some (fn [[_ stream]]
             (let [group (:group stream)
                   status (:status stream)
                   stale-threshold-ms (:stale-threshold-ms stream)
                   age-ms (stream-age-ms generated-at-ms (:last-payload-at-ms stream))]
               (and (= :market_data group)
                    (= :delayed status)
                    (number? stale-threshold-ms)
                    (number? age-ms)
                    (> age-ms severe-threshold-ms))))
           (get health :streams {})))))

(defn auto-recover-eligible?
  [state health {:keys [enabled? severe-threshold-ms]}]
  (let [transport-state (get-in health [:transport :state])
        transport-freshness (get-in health [:transport :freshness])
        generated-at-ms (or (:generated-at-ms health) 0)
        cooldown-until-ms (get-in state [:websocket-ui :auto-recover-cooldown-until-ms])]
    (and enabled?
         (= :connected transport-state)
         (= :live transport-freshness)
         (not (contains? #{:connecting :reconnecting} transport-state))
         (not (true? (get-in state [:websocket-ui :reset-in-progress?])))
         (or (not (number? cooldown-until-ms))
             (<= cooldown-until-ms generated-at-ms))
         (delayed-market-stream-severe? health severe-threshold-ms))))

(defn gap-detected-transition?
  [prior-fingerprint fingerprint]
  (and (not (some true? (vals (select-keys prior-fingerprint [:gap/orders_oms
                                                              :gap/market_data
                                                              :gap/account]))))
       (some true? (vals (select-keys fingerprint [:gap/orders_oms
                                                   :gap/market_data
                                                   :gap/account])))))
