(ns hyperopen.websocket.application.runtime-reducer-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.websocket.application.runtime-reducer :as reducer]
            [hyperopen.websocket.domain.model :as model]
            [hyperopen.websocket.health :as health]))

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

(defn- step [state msg]
  (reducer/step {:calculate-retry-delay-ms (fn [_ _ _ _] 500)} state msg))

(defn- health-snapshot [state now-ms]
  (health/derive-health-snapshot
    {:now-ms now-ms
     :transport {:state (:status state)
                 :online? (:online? state)
                 :last-recv-at-ms (get-in state [:transport :last-recv-at-ms])
                 :connected-at-ms (get-in state [:transport :connected-at-ms])
                 :expected-traffic? (get-in state [:transport :expected-traffic?])
                 :attempt (:attempt state)
                 :last-close (:last-close state)}
     :streams (:streams state)
     :config (:config state)}))

(defn- effect-types [effects]
  (mapv :fx/type effects))

(defn- timer-effect-keys [effects fx-type]
  (->> effects
       (filter #(= fx-type (:fx/type %)))
       (mapv :timer-key)))

(deftest reducer-determinism-test
  (let [state (reducer/initial-runtime-state test-config)
        msg (model/make-runtime-msg :cmd/init-connection 1000 {:ws-url "wss://example.test/ws"})
        a (step state msg)
        b (step state msg)]
    (testing "Same state+msg yields same state/effects"
      (is (= a b)))))

(deftest send-message-queues-and-requests-connect-test
  (let [state (assoc (reducer/initial-runtime-state test-config)
                     :ws-url "wss://example.test/ws"
                     :online? true)
        msg (model/make-runtime-msg :cmd/send-message 10 {:data {:type "alpha" :n 1}})
        {:keys [state effects]} (step state msg)]
    (testing "Disconnected outbound intent is queued and connect is requested"
      (is (= [{:type "alpha" :n 1}] (:queue state)))
      (is (= :connecting (:status state)))
      (is (some #{:fx/socket-connect} (effect-types effects))))))

(deftest market-coalescing-replaces-and-flushes-test
  (let [base (reducer/initial-runtime-state test-config)
        env-1 {:topic "trades"
               :tier :market
               :ts 1
               :payload {:channel "trades"
                         :data [{:coin "BTC"}]
                         :seq 1}}
        env-2 {:topic "trades"
               :tier :market
               :ts 2
               :payload {:channel "trades"
                         :data [{:coin "BTC"}]
                         :seq 2}}
        {:keys [state]} (step base (model/make-runtime-msg :evt/decoded-envelope 1 {:recv-at-ms 1 :envelope env-1}))
        {:keys [state]} (step state (model/make-runtime-msg :evt/decoded-envelope 2 {:recv-at-ms 2 :envelope env-2}))
        flush-result (step state (model/make-runtime-msg :evt/timer-market-flush-fired 10))
        flushed-effects (filter #(= :fx/router-dispatch-envelope (:fx/type %)) (:effects flush-result))]
    (testing "Market coalescing keeps last value per key"
      (is (= 1 (get-in state [:metrics :market-coalesced])))
      (is (= 1 (count (get-in state [:market-coalesce :pending]))))
      (is (= 2 (get-in (first (vals (get-in state [:market-coalesce :pending]))) [:payload :seq]))))
    (testing "Flush dispatches one envelope and clears pending"
      (is (= 1 (count flushed-effects)))
      (is (= 1 (get-in (:state flush-result) [:metrics :market-dispatched])))
      (is (empty? (get-in (:state flush-result) [:market-coalesce :pending]))))))

(deftest health-tick-start-stop-dedup-test
  (let [base (reducer/initial-runtime-state test-config)
        init-msg (model/make-runtime-msg :cmd/init-connection 100 {:ws-url "wss://example.test/ws"})
        first-init (step base init-msg)
        second-init (step (:state first-init) init-msg)
        disconnect (step (:state first-init) (model/make-runtime-msg :cmd/disconnect 200))]
    (testing "Init starts one health interval and sets active flag"
      (is (true? (get-in first-init [:state :health-tick-active?])))
      (is (some #{"health-tick"} (map name (timer-effect-keys (:effects first-init) :fx/timer-set-interval)))))
    (testing "Second init does not emit another health interval effect"
      (is (empty? (filter #(= :health-tick (:timer-key %))
                          (filter #(= :fx/timer-set-interval (:fx/type %))
                                  (:effects second-init))))))
    (testing "Disconnect clears health interval and resets active flag"
      (is (false? (get-in disconnect [:state :health-tick-active?])))
      (is (some #{"health-tick"} (map name (timer-effect-keys (:effects disconnect) :fx/timer-clear-interval)))))))

(deftest force-reconnect-does-not-create-duplicate-health-timers-test
  (let [base (reducer/initial-runtime-state test-config)
        init-msg (model/make-runtime-msg :cmd/init-connection 100 {:ws-url "wss://example.test/ws"})
        init-state (:state (step base init-msg))
        connected-state (assoc init-state :status :connected :active-socket-id 1)
        force-reconnect (step connected-state (model/make-runtime-msg :cmd/force-reconnect 150))
        health-set-effects (->> (:effects force-reconnect)
                                (filter #(and (= :fx/timer-set-interval (:fx/type %))
                                              (= :health-tick (:timer-key %)))))]
    (testing "Force reconnect keeps one existing health timer without setting a duplicate interval"
      (is (true? (get-in force-reconnect [:state :health-tick-active?])))
      (is (empty? health-set-effects)))))

(deftest subscribe-idle-until-first-payload-and-resubscribe-gate-test
  (let [base (-> (reducer/initial-runtime-state test-config)
                 (assoc :status :connected
                        :active-socket-id 1
                        :online? true)
                 (assoc-in [:transport :connected-at-ms] 50))
        sub {:type "trades" :coin "BTC"}
        sub-key (model/subscription-key sub)
        subscribe-msg (model/make-runtime-msg :cmd/send-message 100 {:data {:method "subscribe"
                                                                             :subscription sub}})
        subscribed-state (:state (step base subscribe-msg))
        idle-status (get-in (health-snapshot subscribed-state 110) [:streams sub-key :status])
        first-payload-state (:state (step subscribed-state
                                          (model/make-runtime-msg :evt/decoded-envelope
                                                                  120
                                                                  {:recv-at-ms 120
                                                                   :envelope {:topic "trades"
                                                                              :tier :market
                                                                              :ts 120
                                                                              :payload {:channel "trades"
                                                                                        :data [{:coin "BTC"}]}}})))
        resubscribed-state (:state (step first-payload-state subscribe-msg))
        after-resubscribe-status (get-in (health-snapshot resubscribed-state 130) [:streams sub-key :status])]
    (testing "Subscribed stream is idle until first payload arrives"
      (is (= :idle idle-status))
      (is (nil? (get-in subscribed-state [:streams sub-key :first-payload-at-ms]))))
    (testing "Resubscribe resets first payload gate back to idle"
      (is (number? (get-in first-payload-state [:streams sub-key :first-payload-at-ms])))
      (is (nil? (get-in resubscribed-state [:streams sub-key :first-payload-at-ms])))
      (is (= :idle after-resubscribe-status)))))

(deftest unsubscribed-stream-stays-idle-test
  (let [base (reducer/initial-runtime-state test-config)
        sub {:type "trades" :coin "ETH"}
        sub-key (model/subscription-key sub)
        subscribe-state (:state (step base
                                      (model/make-runtime-msg :cmd/send-message
                                                              100
                                                              {:data {:method "subscribe"
                                                                      :subscription sub}})))
        unsubscribed-state (:state (step subscribe-state
                                        (model/make-runtime-msg :cmd/send-message
                                                                110
                                                                {:data {:method "unsubscribe"
                                                                        :subscription sub}})))
        stream-status (get-in (health-snapshot unsubscribed-state 1000) [:streams sub-key :status])]
    (testing "Unsubscribed stream remains idle"
      (is (= :idle stream-status))
      (is (false? (get-in unsubscribed-state [:streams sub-key :subscribed?]))))))

(deftest stale-threshold-stream-delayed-when-payload-age-too-old-test
  (let [base (-> (reducer/initial-runtime-state test-config)
                 (assoc :status :connected
                        :active-socket-id 1
                        :online? true)
                 (assoc-in [:transport :connected-at-ms] 10))
        sub {:type "l2Book" :coin "BTC"}
        sub-key (model/subscription-key sub)
        state1 (:state (step base
                             (model/make-runtime-msg :cmd/send-message
                                                     100
                                                     {:data {:method "subscribe"
                                                             :subscription sub}})))
        state2 (:state (step state1
                             (model/make-runtime-msg :evt/decoded-envelope
                                                     120
                                                     {:recv-at-ms 120
                                                      :envelope {:topic "l2Book"
                                                                 :tier :market
                                                                 :ts 120
                                                                 :payload {:channel "l2Book"
                                                                           :data {:coin "BTC"}}}})))
        ticked-1 (:state (step state2 (model/make-runtime-msg :evt/timer-health-tick 7000 {:now-ms 7000})))
        ticked-2 (:state (step ticked-1 (model/make-runtime-msg :evt/timer-health-tick 8000 {:now-ms 8000})))
        stream-status (get-in (health-snapshot ticked-2 8000) [:streams sub-key :status])]
    (testing "Configured stale-threshold stream transitions to delayed by payload age"
      (is (= :delayed stream-status)))))

(deftest event-driven-stream-remains-neutral-without-threshold-test
  (let [base (reducer/initial-runtime-state test-config)
        sub {:type "openOrders" :user "0xabc"}
        sub-key (model/subscription-key sub)
        state1 (:state (step base
                             (model/make-runtime-msg :cmd/send-message
                                                     100
                                                     {:data {:method "subscribe"
                                                             :subscription sub}})))
        state2 (:state (step state1
                             (model/make-runtime-msg :evt/decoded-envelope
                                                     120
                                                     {:recv-at-ms 120
                                                      :envelope {:topic "openOrders"
                                                                 :tier :lossless
                                                                 :ts 120
                                                                 :payload {:channel "openOrders"
                                                                           :data {:user "0xabc"
                                                                                  :openOrders []}}}})))
        stream-status (get-in (health-snapshot state2 100000) [:streams sub-key :status])]
    (testing "Event-driven OMS/account stream remains n-a by silence alone"
      (is (= :n-a stream-status))
      (is (nil? (get-in state2 [:streams sub-key :stale-threshold-ms]))))))

(deftest transport-freshness-delayed-when-expected-traffic-stale-test
  (let [base (reducer/initial-runtime-state test-config)
        init-state (:state (step base (model/make-runtime-msg :cmd/init-connection 10 {:ws-url "wss://example.test/ws"})))
        open-state (:state (step init-state (model/make-runtime-msg :evt/socket-open 20 {:socket-id 1 :at-ms 20})))
        subscribed-state (:state (step open-state
                                       (model/make-runtime-msg :cmd/send-message
                                                               30
                                                               {:data {:method "subscribe"
                                                                       :subscription {:type "trades"
                                                                                      :coin "BTC"}}})))
        ticked-state-1 (:state (step subscribed-state (model/make-runtime-msg :evt/timer-health-tick 20000 {:now-ms 20000})))
        ticked-state-2 (:state (step ticked-state-1 (model/make-runtime-msg :evt/timer-health-tick 21000 {:now-ms 21000})))
        freshness (get-in (health-snapshot ticked-state-2 21000) [:transport :freshness])]
    (testing "Connected socket is delayed when expected traffic is active and recv age exceeds threshold"
      (is (= :connected (:status ticked-state-2)))
      (is (true? (get-in ticked-state-2 [:transport :expected-traffic?])))
      (is (= :delayed freshness)))))

(deftest close-reason-and-code-preserved-in-health-snapshot-test
  (let [state (assoc (reducer/initial-runtime-state test-config)
                     :status :connected
                     :active-socket-id 7)
        closed (:state (step state (model/make-runtime-msg :evt/socket-close
                                                            100
                                                            {:socket-id 7
                                                             :code 1006
                                                             :reason "abnormal"
                                                             :was-clean? false
                                                             :at-ms 100})))
        close-info (get-in (health-snapshot closed 101) [:transport :last-close])]
    (testing "Close info is preserved for diagnostics"
      (is (= 1006 (:code close-info)))
      (is (= "abnormal" (:reason close-info)))
      (is (= 100 (:at-ms close-info))))))

(deftest seq-gap-tracking-and-resubscribe-reset-test
  (let [base (-> (reducer/initial-runtime-state test-config)
                 (assoc :status :connected
                        :active-socket-id 1
                        :online? true)
                 (assoc-in [:transport :connected-at-ms] 10))
        sub {:type "trades" :coin "BTC"}
        sub-key (model/subscription-key sub)
        subscribed (:state (step base
                                 (model/make-runtime-msg :cmd/send-message
                                                         100
                                                         {:data {:method "subscribe"
                                                                 :subscription sub}})))
        with-seq-1 (:state (step subscribed
                                 (model/make-runtime-msg :evt/decoded-envelope
                                                         110
                                                         {:recv-at-ms 110
                                                          :envelope {:topic "trades"
                                                                     :tier :market
                                                                     :ts 110
                                                                     :payload {:channel "trades"
                                                                               :seq 1
                                                                               :data [{:coin "BTC"}]}}})))
        with-seq-2 (:state (step with-seq-1
                                 (model/make-runtime-msg :evt/decoded-envelope
                                                         120
                                                         {:recv-at-ms 120
                                                          :envelope {:topic "trades"
                                                                     :tier :market
                                                                     :ts 120
                                                                     :payload {:channel "trades"
                                                                               :seq 2
                                                                               :data [{:coin "BTC"}]}}})))
        with-gap (:state (step with-seq-2
                               (model/make-runtime-msg :evt/decoded-envelope
                                                       130
                                                       {:recv-at-ms 130
                                                        :envelope {:topic "trades"
                                                                   :tier :market
                                                                   :ts 130
                                                                   :payload {:channel "trades"
                                                                             :seq 5
                                                                             :data [{:coin "BTC"}]}}})))
        resubscribed (:state (step with-gap
                                   (model/make-runtime-msg :cmd/send-message
                                                           140
                                                           {:data {:method "subscribe"
                                                                   :subscription sub}})))
        stream-with-gap (get-in with-gap [:streams sub-key])
        stream-after-resubscribe (get-in resubscribed [:streams sub-key])
        snapshot (health-snapshot with-gap 131)]
    (testing "Contiguous sequence updates do not set gap metadata"
      (is (= 2 (get-in with-seq-2 [:streams sub-key :last-seq])))
      (is (false? (boolean (get-in with-seq-2 [:streams sub-key :seq-gap-detected?])))))
    (testing "Sequence jump marks gap details deterministically"
      (is (= 5 (:last-seq stream-with-gap)))
      (is (true? (:seq-gap-detected? stream-with-gap)))
      (is (= 1 (:seq-gap-count stream-with-gap)))
      (is (= {:expected 3 :actual 5 :at-ms 130}
             (:last-gap stream-with-gap))))
    (testing "Group rollup includes seq gap detection"
      (is (true? (get-in snapshot [:groups :market_data :gap-detected?]))))
    (testing "Resubscribe clears sequence and gap state"
      (is (nil? (:last-seq stream-after-resubscribe)))
      (is (false? (:seq-gap-detected? stream-after-resubscribe)))
      (is (= 0 (:seq-gap-count stream-after-resubscribe)))
      (is (nil? (:last-gap stream-after-resubscribe))))))
