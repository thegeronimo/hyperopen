(ns hyperopen.websocket.health-runtime-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.websocket.health-projection :as health-projection]
            [hyperopen.websocket.health-runtime :as health-runtime]))

(defn- connected-health
  [generated-at-ms transport-freshness]
  {:generated-at-ms generated-at-ms
   :transport {:state :connected
               :freshness transport-freshness}
   :groups {:orders_oms {:worst-status :idle}
            :market_data {:worst-status :live}
            :account {:worst-status :idle}}
   :streams {}})

(deftest effective-now-ms-normalizes-epoch-and-wall-time-values-test
  (is (= 500 (health-runtime/effective-now-ms 500)))
  (is (= 9999999999999
         (health-runtime/effective-now-ms 9999999999999))))

(deftest sync-websocket-health-refreshes-when-time-bucket-advances-test
  (let [store (atom {:websocket {:health {}}
                     :websocket-ui {:reset-in-progress? false
                                    :diagnostics-timeline []}})
        projection-state (atom {:fingerprint nil})
        dispatches (atom [])
        diagnostics (atom [])
        snapshots (atom [(connected-health 1000 :live)
                         (connected-health 2000 :live)
                         (connected-health 3000 :delayed)])
        next-snapshot (fn []
                        (let [snapshot (first @snapshots)]
                          (swap! snapshots #(vec (rest %)))
                          snapshot))
        sync! (fn []
                (health-runtime/sync-websocket-health!
                 {:store store
                  :get-health-snapshot next-snapshot
                  :websocket-health-fingerprint health-projection/websocket-health-fingerprint
                  :projection-state projection-state
                  :auto-recover-enabled-fn (constantly false)
                  :auto-recover-severe-threshold-ms 30000
                  :auto-recover-cooldown-ms 300000
                  :dispatch! (fn [_ _ effects]
                               (swap! dispatches conj effects))
                  :append-diagnostics-event! (fn [& args]
                                               (swap! diagnostics conj args))
                  :queue-microtask-fn (fn [f] (f))}))]
    (sync!)
    (is (= 1000 (get-in @store [:websocket :health :generated-at-ms])))
    (is (= 1 (:writes @projection-state)))
    (sync!)
    (is (= 2000 (get-in @store [:websocket :health :generated-at-ms])))
    (is (= 2 (:writes @projection-state)))
    (sync!)
    (is (= 3000 (get-in @store [:websocket :health :generated-at-ms])))
    (is (= 3 (:writes @projection-state)))
    (is (empty? @dispatches))
    (is (empty? @diagnostics))))

(deftest sync-websocket-health-skips-snapshot-when-projected-fingerprint-unchanged-test
  (let [store (atom {:websocket {:health {}}
                     :websocket-ui {:reset-in-progress? false
                                    :diagnostics-timeline []}})
        baseline-health (connected-health 1000 :live)
        baseline-fingerprint (health-projection/websocket-health-fingerprint baseline-health)
        projection-state (atom {:fingerprint baseline-fingerprint})
        health-reads (atom 0)]
    (health-runtime/sync-websocket-health!
     {:store store
      :projected-fingerprint baseline-fingerprint
      :get-health-snapshot (fn []
                             (swap! health-reads inc)
                             baseline-health)
      :websocket-health-fingerprint health-projection/websocket-health-fingerprint
      :projection-state projection-state
      :auto-recover-enabled-fn (constantly false)
      :auto-recover-severe-threshold-ms 30000
      :auto-recover-cooldown-ms 300000
      :dispatch! (fn [& _] nil)
      :append-diagnostics-event! (fn [& _] nil)
      :queue-microtask-fn (fn [f] (f))})
    (is (= 0 @health-reads))
    (is (= {:fingerprint baseline-fingerprint} @projection-state))
    (is (= nil (:writes @projection-state)))
    (is (= {} (get-in @store [:websocket :health])))))

(deftest sync-websocket-health-applies-auto-recover-cooldown-and-dispatch-once-test
  (let [generated-at-ms 1700000000000
        health {:generated-at-ms generated-at-ms
                :transport {:state :connected
                            :freshness :live}
                :groups {:orders_oms {:worst-status :idle}
                         :market_data {:worst-status :delayed}
                         :account {:worst-status :idle}}
                :streams {["l2Book" "BTC" nil nil nil]
                          {:group :market_data
                           :topic "l2Book"
                           :status :delayed
                           :last-payload-at-ms (- generated-at-ms 45000)
                           :stale-threshold-ms 5000}}}
        store (atom {:websocket {:health {}}
                     :websocket-ui {:reset-in-progress? false
                                    :auto-recover-cooldown-until-ms nil
                                    :auto-recover-count 0
                                    :diagnostics-timeline []}})
        projection-state (atom {:fingerprint nil})
        dispatches (atom [])
        sync! (fn []
                (health-runtime/sync-websocket-health!
                 {:store store
                  :get-health-snapshot (constantly health)
                  :websocket-health-fingerprint health-projection/websocket-health-fingerprint
                  :projection-state projection-state
                  :auto-recover-enabled-fn (constantly true)
                  :auto-recover-severe-threshold-ms 30000
                  :auto-recover-cooldown-ms 300000
                  :dispatch! (fn [_ _ effects]
                               (swap! dispatches conj effects))
                  :append-diagnostics-event! (fn [& _] nil)
                  :queue-microtask-fn (fn [f] (f))}))]
    (sync!)
    (sync!)
    (is (= [[:actions/ws-diagnostics-reset-market-subscriptions :auto-recover]]
           (first @dispatches)))
    (is (= 1 (count @dispatches)))
    (is (= 1 (get-in @store [:websocket-ui :auto-recover-count])))
    (is (= (+ generated-at-ms 300000)
           (get-in @store [:websocket-ui :auto-recover-cooldown-until-ms])))
    (is (= 1 (:writes @projection-state)))))

(deftest sync-websocket-health-updates-projection-state-when-provided-test
  (let [store (atom {:websocket {:health {}}
                     :websocket-ui {:reset-in-progress? false
                                    :diagnostics-timeline []}})
        projection-state (atom {:fingerprint nil
                                :writes 0})
        health (connected-health 1000 :live)]
    (health-runtime/sync-websocket-health!
     {:store store
      :projection-state projection-state
      :get-health-snapshot (constantly health)
      :websocket-health-fingerprint health-projection/websocket-health-fingerprint
      :auto-recover-enabled-fn (constantly false)
      :auto-recover-severe-threshold-ms 30000
      :auto-recover-cooldown-ms 300000
      :dispatch! (fn [& _] nil)
      :append-diagnostics-event! (fn [& _] nil)
      :queue-microtask-fn (fn [f] (f))})
    (is (= 1000 (get-in @store [:websocket :health :generated-at-ms])))
    (is (some? (:fingerprint @projection-state)))
    (is (= 1 (:writes @projection-state)))))
