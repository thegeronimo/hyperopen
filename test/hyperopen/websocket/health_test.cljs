(ns hyperopen.websocket.health-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.websocket.domain.model :as model]
            [hyperopen.websocket.health :as health]))

(defn- mk-stream
  [{:keys [subscribed? topic descriptor stale-threshold-ms]}]
  {:subscribed? subscribed?
   :topic topic
   :descriptor descriptor
   :stale-threshold-ms stale-threshold-ms})

(deftest transport-freshness-gates-delayed-behind-expected-traffic-test
  (testing "Connected but stale transport is live when traffic is not expected"
    (is (= :live
           (health/derive-transport-freshness {:state :connected
                                               :online? true
                                               :expected-traffic? false
                                               :last-recv-at-ms 0
                                               :now-ms 60000
                                               :transport-live-threshold-ms 10000}))))
  (testing "Connected and stale transport becomes delayed when traffic is expected"
    (is (= :delayed
           (health/derive-transport-freshness {:state :connected
                                               :online? true
                                               :expected-traffic? true
                                               :last-recv-at-ms 0
                                               :now-ms 60000
                                               :transport-live-threshold-ms 10000})))))

(deftest stream-status-gating-idle-na-live-delayed-test
  (testing "Subscribed stream is idle until first payload arrives"
    (is (= :idle
           (health/derive-stream-status 2000 {:subscribed? true
                                              :subscribed-at-ms 1000
                                              :first-payload-at-ms nil
                                              :last-payload-at-ms nil
                                              :stale-threshold-ms 5000}))))
  (testing "Event-driven stream without threshold is n-a after first payload"
    (is (= :n-a
           (health/derive-stream-status 200000 {:subscribed? true
                                                :subscribed-at-ms 1000
                                                :first-payload-at-ms 1200
                                                :last-payload-at-ms 1200
                                                :stale-threshold-ms nil}))))
  (testing "Threshold-based stream becomes delayed by payload age"
    (is (= :delayed
           (health/derive-stream-status 10000 {:subscribed? true
                                               :subscribed-at-ms 100
                                               :first-payload-at-ms 200
                                               :last-payload-at-ms 200
                                               :stale-threshold-ms 1000}))))
  (testing "Unsubscribed stream is idle"
    (is (= :idle
           (health/derive-stream-status 10000 {:subscribed? false
                                               :subscribed-at-ms nil
                                               :first-payload-at-ms nil
                                               :last-payload-at-ms nil
                                               :stale-threshold-ms 1000})))))

(deftest transport-hysteresis-requires-two-consecutive-live-delayed-transitions-test
  (let [config {:freshness-hysteresis-consecutive 2}
        t0 (health/advance-transport-freshness config {} :live)
        t1 (health/advance-transport-freshness config t0 :delayed)
        t2 (health/advance-transport-freshness config t1 :delayed)
        t3 (health/advance-transport-freshness config t2 :live)
        t4 (health/advance-transport-freshness config t3 :live)]
    (testing "First opposite candidate does not flip stable freshness"
      (is (= :live (:freshness t1))))
    (testing "Second consecutive opposite candidate flips stable freshness"
      (is (= :delayed (:freshness t2))))
    (testing "Recovery also requires two consecutive fresh candidates"
      (is (= :delayed (:freshness t3)))
      (is (= :live (:freshness t4))))))

(deftest stream-hysteresis-keeps-event-driven-immediate-and-threshold-flips-on-two-ticks-test
  (let [config {:freshness-hysteresis-consecutive 2}
        base-threshold {:subscribed? true
                        :subscribed-at-ms 100
                        :first-payload-at-ms 120
                        :last-payload-at-ms 120
                        :stale-threshold-ms 1000}
        s0 (health/advance-stream-status config base-threshold :live)
        s1 (health/advance-stream-status config s0 :delayed)
        s2 (health/advance-stream-status config s1 :delayed)
        event-driven (health/advance-stream-status config {} :n-a)]
    (testing "Threshold stream needs two consecutive delayed candidates before flipping"
      (is (= :live (:status s1)))
      (is (= :delayed (:status s2))))
    (testing "Event-driven n-a applies immediately"
      (is (= :n-a (:status event-driven))))))

(deftest l2book-fixture-matches-subscription-key-test
  (let [sub {:type "l2Book" :coin "BTC"}
        sub-key (model/subscription-key sub)
        streams {sub-key (mk-stream {:subscribed? true
                                     :topic "l2Book"
                                     :descriptor sub
                                     :stale-threshold-ms 5000})}
        envelope {:topic "l2Book"
                  :payload {:channel "l2Book"
                            :data {:coin "BTC"
                                   :time 1710000000000}}}]
    (is (= [sub-key]
           (health/match-stream-keys streams envelope)))))

(deftest trades-fixture-matches-subscription-key-test
  (let [btc {:type "trades" :coin "BTC"}
        eth {:type "trades" :coin "ETH"}
        btc-key (model/subscription-key btc)
        eth-key (model/subscription-key eth)
        streams {btc-key (mk-stream {:subscribed? true
                                     :topic "trades"
                                     :descriptor btc
                                     :stale-threshold-ms 10000})
                 eth-key (mk-stream {:subscribed? true
                                     :topic "trades"
                                     :descriptor eth
                                     :stale-threshold-ms 10000})}
        envelope {:topic "trades"
                  :payload {:channel "trades"
                            :data [{:coin "ETH"
                                    :px "3200.0"
                                    :sz "0.5"}]}}]
    (is (= [eth-key]
           (health/match-stream-keys streams envelope)))))

(deftest user-channel-fixture-matching-prefers-user-and-avoids-ambiguous-fallback-test
  (let [alice {:type "openOrders" :user "0xalice"}
        bob {:type "openOrders" :user "0xbob"}
        alice-key (model/subscription-key alice)
        bob-key (model/subscription-key bob)
        streams {alice-key (mk-stream {:subscribed? true
                                       :topic "openOrders"
                                       :descriptor alice})
                 bob-key (mk-stream {:subscribed? true
                                     :topic "openOrders"
                                     :descriptor bob})}]
    (testing "Payload user disambiguates the matching subscription key"
      (is (= [bob-key]
             (health/match-stream-keys streams
                                       {:topic "openOrders"
                                        :payload {:channel "openOrders"
                                                  :data {:user "0xbob"
                                                         :openOrders []}}}))))
    (testing "No discriminator + multiple active subscriptions yields no match"
      (is (= []
             (health/match-stream-keys streams
                                       {:topic "openOrders"
                                        :payload {:channel "openOrders"
                                                  :data {:openOrders []}}}))))))

(deftest health-snapshot-includes-seq-gap-fields-and-group-rollup-test
  (let [sub-key ["trades" "BTC" nil nil nil]
        snapshot (health/derive-health-snapshot
                   {:now-ms 1000
                    :transport {:state :connected
                                :online? true
                                :last-recv-at-ms 900
                                :expected-traffic? true
                                :attempt 0}
                    :streams {sub-key {:subscribed? true
                                       :subscribed-at-ms 100
                                       :first-payload-at-ms 200
                                       :last-payload-at-ms 900
                                       :message-count 3
                                       :topic "trades"
                                       :group :market_data
                                       :descriptor {:type "trades" :coin "BTC"}
                                       :stale-threshold-ms 5000
                                       :last-seq 9
                                       :seq-gap-detected? true
                                       :seq-gap-count 2
                                       :last-gap {:expected 7 :actual 9 :at-ms 900}}}
                    :config {}})]
    (is (= 9 (get-in snapshot [:streams sub-key :last-seq])))
    (is (true? (get-in snapshot [:streams sub-key :seq-gap-detected?])))
    (is (= 2 (get-in snapshot [:streams sub-key :seq-gap-count])))
    (is (= {:expected 7 :actual 9 :at-ms 900}
           (get-in snapshot [:streams sub-key :last-gap])))
    (is (true? (get-in snapshot [:groups :market_data :gap-detected?])))))
