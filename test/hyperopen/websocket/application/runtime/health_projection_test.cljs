(ns hyperopen.websocket.application.runtime.health-projection-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.websocket.application.runtime.connection :as connection]
            [hyperopen.websocket.application.runtime.health-projection :as health-projection]
            [hyperopen.websocket.application.runtime.subscriptions :as subscriptions]
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

(defn- subscribe-state
  [config subscription at-ms]
  (-> (reducer/initial-runtime-state config)
      (assoc :status :connected
             :active-socket-id 1
             :online? true)
      (assoc-in [:transport :connected-at-ms] 10)
      (connection/with-now at-ms)
      (subscriptions/apply-stream-intent {:method "subscribe"
                                          :subscription subscription}
                                         at-ms)
      (subscriptions/refresh-expected-traffic)))

(defn- record-envelope
  [state recv-at-ms envelope]
  (-> state
      (connection/with-now recv-at-ms)
      (subscriptions/record-stream-payload envelope recv-at-ms)
      (subscriptions/refresh-expected-traffic)))

(deftest maybe-refresh-health-hysteresis-is-throttled-between-projection-intervals-test
  (let [config (assoc test-config
                 :freshness-hysteresis-consecutive 1
                 :health-projection-interval-ms 1000)
        subscription {:type "l2Book" :coin "BTC"}
        subscribed (-> (subscribe-state config subscription 100)
                       (health-projection/maybe-refresh-health-hysteresis :cmd/send-message false))
        fast-state-1 (-> subscribed
                         (record-envelope 700 {:topic "l2Book"
                                               :tier :market
                                               :ts 700
                                               :payload {:channel "l2Book"
                                                         :data {:coin "BTC"}
                                                         :seq 2}})
                         (health-projection/maybe-refresh-health-hysteresis :evt/decoded-envelope false))
        fast-state-2 (-> fast-state-1
                         (record-envelope 900 {:topic "l2Book"
                                               :tier :market
                                               :ts 900
                                               :payload {:channel "l2Book"
                                                         :data {:coin "BTC"}
                                                         :seq 3}})
                         (health-projection/maybe-refresh-health-hysteresis :evt/decoded-envelope false))
        refreshed (-> fast-state-2
                      (connection/with-now 12000)
                      (health-projection/maybe-refresh-health-hysteresis :evt/timer-health-tick false))]
    (testing "Health refresh metadata is held stable across sub-interval envelope bursts"
      (is (= 100 (:health-projection-last-refresh-at-ms subscribed)))
      (is (= 100 (:health-projection-last-refresh-at-ms fast-state-2)))
      (is (= 12000 (:health-projection-last-refresh-at-ms refreshed))))
    (testing "Projected health fingerprint stays stable until the interval refresh runs"
      (is (= (:health-projection-fingerprint subscribed)
             (:health-projection-fingerprint fast-state-1)))
      (is (= (:health-projection-fingerprint subscribed)
             (:health-projection-fingerprint fast-state-2)))
      (is (not= (:health-projection-fingerprint subscribed)
                (:health-projection-fingerprint refreshed))))
    (testing "Transport hysteresis pending status advances only when refresh runs"
      (is (= :live (get-in subscribed [:transport :freshness-pending-status])))
      (is (= :live (get-in fast-state-2 [:transport :freshness-pending-status])))
      (is (= :delayed (get-in refreshed [:transport :freshness-pending-status]))))))

(deftest maybe-refresh-health-hysteresis-rolls-seq-gaps-into-health-fingerprint-test
  (let [subscription {:type "trades" :coin "BTC"}
        with-gap (-> (subscribe-state test-config subscription 100)
                     (record-envelope 110 {:topic "trades"
                                           :tier :market
                                           :ts 110
                                           :payload {:channel "trades"
                                                     :seq 1
                                                     :data [{:coin "BTC"}]}})
                     (record-envelope 120 {:topic "trades"
                                           :tier :market
                                           :ts 120
                                           :payload {:channel "trades"
                                                     :seq 2
                                                     :data [{:coin "BTC"}]}})
                     (record-envelope 130 {:topic "trades"
                                           :tier :market
                                           :ts 130
                                           :payload {:channel "trades"
                                                     :seq 5
                                                     :data [{:coin "BTC"}]}}))
        refreshed (-> with-gap
                      (connection/with-now 131)
                      (health-projection/maybe-refresh-health-hysteresis :evt/timer-health-tick true))
        fingerprint (:health-projection-fingerprint refreshed)]
    (testing "Forced health refresh records the latest projection fingerprint"
      (is (= 131 (:health-projection-last-refresh-at-ms refreshed))))
    (testing "Gap rollups are reflected in the health fingerprint"
      (is (true? (:gap/market_data fingerprint)))
      (is (false? (:gap/orders_oms fingerprint)))
      (is (false? (:gap/account fingerprint))))))
