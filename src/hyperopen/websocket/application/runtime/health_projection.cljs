(ns hyperopen.websocket.application.runtime.health-projection
  (:require [hyperopen.websocket.domain.model :as model]
            [hyperopen.websocket.health :as health]))

(def ^:private default-health-projection-interval-ms
  1000)

(def ^:private forced-health-refresh-msg-types
  #{:cmd/init-connection
    :cmd/disconnect
    :cmd/force-reconnect
    :cmd/send-message
    :evt/socket-open
    :evt/socket-close
    :evt/lifecycle-online
    :evt/lifecycle-hidden
    :evt/lifecycle-visible
    :evt/lifecycle-offline
    :evt/timer-retry-fired
    :evt/timer-health-tick})

(def ^:private default-group-rollup
  {:market_data {:worst-status :idle :gap-detected? false}
   :orders_oms {:worst-status :idle :gap-detected? false}
   :account {:worst-status :idle :gap-detected? false}})

(def ^:private health-fingerprint-time-bucket-ms
  1000)

(defn- health-fingerprint-time-bucket
  [at-ms]
  (when (number? at-ms)
    (quot at-ms health-fingerprint-time-bucket-ms)))

(defn- health-projection-interval-ms
  [state]
  (max 1
       (or (get-in state [:config :health-projection-interval-ms])
           (get-in state [:config :health-tick-interval-ms])
           default-health-projection-interval-ms)))

(defn- health-refresh-due?
  [state msg-type]
  (let [now-ms (:now-ms state)
        last-refresh-at-ms (:health-projection-last-refresh-at-ms state)]
    (or (contains? forced-health-refresh-msg-types msg-type)
        (and (number? now-ms)
             (or (nil? last-refresh-at-ms)
                 (>= (- now-ms last-refresh-at-ms)
                     (health-projection-interval-ms state)))))))

(defn- rollup-stream-groups
  [streams]
  (reduce (fn [acc [_ {:keys [group topic status seq-gap-detected?]}]]
            (let [group* (or group (model/topic->group topic) :account)
                  current (get-in acc [group* :worst-status] :idle)]
              (-> acc
                  (assoc-in [group* :worst-status] (health/worst-status current status))
                  (update-in [group* :gap-detected?]
                             #(or (boolean %)
                                  (boolean seq-gap-detected?))))))
          default-group-rollup
          (or streams {})))

(defn- projection-health-fingerprint
  [state]
  (let [groups (rollup-stream-groups (:streams state))]
    {:clock/second (health-fingerprint-time-bucket (:now-ms state))
     :transport/state (:status state)
     :transport/freshness (get-in state [:transport :freshness])
     :groups/orders_oms (get-in groups [:orders_oms :worst-status])
     :groups/market_data (get-in groups [:market_data :worst-status])
     :groups/account (get-in groups [:account :worst-status])
     :gap/orders_oms (boolean (get-in groups [:orders_oms :gap-detected?]))
     :gap/market_data (boolean (get-in groups [:market_data :gap-detected?]))
     :gap/account (boolean (get-in groups [:account :gap-detected?]))}))

(defn- refresh-health-hysteresis
  [state]
  (let [now-ms (:now-ms state)]
    (if (number? now-ms)
      (let [transport-candidate (health/derive-transport-freshness
                                 {:state (:status state)
                                  :online? (:online? state)
                                  :expected-traffic? (boolean (get-in state [:transport :expected-traffic?]))
                                  :last-recv-at-ms (get-in state [:transport :last-recv-at-ms])
                                  :connected-at-ms (get-in state [:transport :connected-at-ms])
                                  :transport-live-threshold-ms (get-in state [:config :transport-live-threshold-ms])
                                  :now-ms now-ms})
            transport* (health/advance-transport-freshness
                        (:config state)
                        (:transport state)
                        transport-candidate)
            streams* (into {}
                           (map (fn [[sub-key stream]]
                                  (let [candidate-status (health/derive-stream-status now-ms stream)]
                                    [sub-key
                                     (health/advance-stream-status
                                      (:config state)
                                      stream
                                      candidate-status)])))
                           (:streams state))]
        (assoc state
               :transport transport*
               :streams streams*))
      state)))

(defn maybe-refresh-health-hysteresis
  [state msg-type force-health-refresh?]
  (if (or force-health-refresh?
          (health-refresh-due? state msg-type))
    (let [state* (refresh-health-hysteresis state)
          now-ms (:now-ms state*)
          fingerprint (projection-health-fingerprint state*)]
      (cond-> (assoc state* :health-projection-fingerprint fingerprint)
        (number? now-ms)
        (assoc :health-projection-last-refresh-at-ms now-ms)))
    state))
