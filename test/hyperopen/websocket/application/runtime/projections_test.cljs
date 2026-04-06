(ns hyperopen.websocket.application.runtime.projections-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.websocket.application.runtime.projections :as projections]
            [hyperopen.websocket.application.runtime-reducer :as reducer]))

(def test-config
  {:max-queue-size 3
   :watchdog-interval-ms 10000
   :health-tick-interval-ms 1000
   :transport-live-threshold-ms 10000
   :stale-threshold-ms {"l2Book" 5000
                        "trades" 10000}
   :stale-visible-ms 45000
   :stale-hidden-ms 180000
   :market-coalesce-window-ms 5})

(defn- projection-fingerprint
  [effects]
  (->> effects
       (filter #(= :fx/project-runtime-view (:fx/type %)))
       last
       :projection-fingerprint))

(deftest append-runtime-view-projection-emits-deterministic-fingerprint-test
  (let [state (reducer/initial-runtime-state test-config)
        connected-state (-> state
                            (assoc :status :connected
                                   :online? true
                                   :active-socket-id 7
                                   :queue [{:method "subscribe"}])
                            (assoc-in [:transport :connected-at-ms] 200)
                            (assoc-in [:transport :freshness] :live))
        runtime-view (projections/runtime-view-projection state)
        identical-a (projection-fingerprint (projections/append-runtime-view-projection state []))
        identical-b (projection-fingerprint (projections/append-runtime-view-projection state []))
        changed (projection-fingerprint (projections/append-runtime-view-projection connected-state []))]
    (testing "Runtime view projection exposes the reducer connection and stream slices"
      (is (= :disconnected (get-in runtime-view [:connection :status])))
      (is (= 0 (get-in runtime-view [:connection :queue-size])))
      (is (= (:health-projection-fingerprint state)
             (get-in runtime-view [:stream :health-fingerprint]))))
    (testing "Projection effects always include deterministic fingerprint payloads"
      (is (some? identical-a))
      (is (= identical-a identical-b)))
    (testing "Fingerprint changes when projected runtime state changes"
      (is (not= identical-a changed)))))
