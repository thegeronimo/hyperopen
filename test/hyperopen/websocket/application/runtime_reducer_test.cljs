(ns hyperopen.websocket.application.runtime-reducer-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.websocket.application.runtime-reducer :as reducer]
            [hyperopen.websocket.domain.model :as model]))

(def test-config
  {:max-queue-size 3
   :watchdog-interval-ms 10000
   :stale-visible-ms 45000
   :stale-hidden-ms 180000
   :market-coalesce-window-ms 5})

(defn- step [state msg]
  (reducer/step {:calculate-retry-delay-ms (fn [_ _ _ _] 500)} state msg))

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
        {:keys [state effects]} (step state msg)
        effect-types (mapv :fx/type effects)]
    (testing "Disconnected outbound intent is queued and connect is requested"
      (is (= [{:type "alpha" :n 1}] (:queue state)))
      (is (= :connecting (:status state)))
      (is (some #{:fx/socket-connect} effect-types)))))

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
        {:keys [state]} (step base (model/make-runtime-msg :evt/decoded-envelope 1 {:envelope env-1}))
        {:keys [state]} (step state (model/make-runtime-msg :evt/decoded-envelope 2 {:envelope env-2}))
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

(deftest disconnect-does-not-schedule-retry-test
  (let [state (assoc (reducer/initial-runtime-state test-config)
                     :ws-url "wss://example.test/ws"
                     :status :connected
                     :active-socket-id 7
                     :retry-timer-active? true
                     :watchdog-active? true)
        {:keys [state effects]} (step state (model/make-runtime-msg :cmd/disconnect 100))
        effect-types (set (map :fx/type effects))]
    (testing "Disconnect transitions to disconnected and clears timers"
      (is (= :disconnected (:status state)))
      (is (false? (:retry-timer-active? state)))
      (is (false? (:watchdog-active? state)))
      (is (contains? effect-types :fx/timer-clear-timeout))
      (is (contains? effect-types :fx/timer-clear-interval))
      (is (not (contains? effect-types :fx/timer-set-timeout))))))
