(ns hyperopen.websocket.application.runtime.projections
  (:require [hyperopen.websocket.domain.model :as model]))

(defn- connection-projection
  [state]
  {:status (:status state)
   :attempt (:attempt state)
   :next-retry-at-ms (:next-retry-at-ms state)
   :last-close (:last-close state)
   :last-activity-at-ms (:last-activity-at-ms state)
   :now-ms (:now-ms state)
   :online? (:online? state)
   :transport/state (:status state)
   :transport/last-recv-at-ms (get-in state [:transport :last-recv-at-ms])
   :transport/connected-at-ms (get-in state [:transport :connected-at-ms])
   :transport/expected-traffic? (boolean (get-in state [:transport :expected-traffic?]))
   :transport/freshness (get-in state [:transport :freshness])
   :queue-size (count (:queue state))
   :ws nil})

(defn- stream-projection
  [state]
  {:metrics (:metrics state)
   :tier-depth (:tier-depth state)
   :market-coalesce (:market-coalesce state)
   :now-ms (:now-ms state)
   :health-fingerprint (:health-projection-fingerprint state)
   :streams (:streams state)
   :transport {:state (:status state)
               :online? (:online? state)
               :last-recv-at-ms (get-in state [:transport :last-recv-at-ms])
               :connected-at-ms (get-in state [:transport :connected-at-ms])
               :expected-traffic? (boolean (get-in state [:transport :expected-traffic?]))
               :freshness (get-in state [:transport :freshness])
               :attempt (:attempt state)
               :last-close (:last-close state)}})

(defn runtime-view-projection
  [state]
  (let [connection (connection-projection state)
        active-socket-id (:active-socket-id state)
        stream (stream-projection state)]
    {:connection connection
     :active-socket-id active-socket-id
     :stream stream}))

(defn- connection-projection-fingerprint
  [connection active-socket-id]
  {:connection connection
   :active-socket-id active-socket-id})

(defn- stream-projection-fingerprint
  [stream]
  stream)

(defn runtime-view-projection-fingerprint
  [{:keys [connection active-socket-id stream]}]
  {:connection (connection-projection-fingerprint connection active-socket-id)
   :stream (stream-projection-fingerprint stream)})

(defn append-runtime-view-projection
  [state effects]
  (let [runtime-view (runtime-view-projection state)]
    (conj effects
          (model/make-runtime-effect :fx/project-runtime-view
                                     {:runtime-view runtime-view
                                      :projection-fingerprint
                                      (runtime-view-projection-fingerprint runtime-view)}))))
