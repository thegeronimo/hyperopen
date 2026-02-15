(ns hyperopen.telemetry-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.platform :as platform]
            [hyperopen.telemetry :as telemetry]))

(defn- reset-telemetry-state!
  []
  (reset! @#'hyperopen.telemetry/event-log [])
  (reset! @#'hyperopen.telemetry/event-seq 0))

(use-fixtures
  :each
  {:before reset-telemetry-state!
   :after reset-telemetry-state!})

(deftest telemetry-emit-and-readback-test
  (with-redefs [hyperopen.telemetry/dev-enabled? (constantly true)
                hyperopen.platform/now-ms (constantly 101)]
    (let [entry-a (telemetry/emit! :alpha {:kind :seed})
          entry-b (telemetry/emit! :beta)
          entries (telemetry/events)
          json (telemetry/events-json)]
      (is (= 1 (:seq entry-a)))
      (is (= 2 (:seq entry-b)))
      (is (= :alpha (:event (first entries))))
      (is (= :beta (:event (second entries))))
      (is (= 101 (:at-ms (first entries))))
      (is (string? json))
      (is (>= (count json) 2)))))

(deftest telemetry-log-and-clear-test
  (with-redefs [hyperopen.telemetry/dev-enabled? (constantly true)
                hyperopen.platform/now-ms (constantly 202)]
    (telemetry/log! "wallet" {:address "0xabc"} :ok true)
    (let [entry (first (telemetry/events))]
      (is (= :log/message (:event entry)))
      (is (string? (:message entry)))
      (is (vector? (:args entry))))
    (telemetry/clear-events!)
    (is (= [] (telemetry/events)))))

(deftest telemetry-noop-when-disabled-test
  (with-redefs [hyperopen.telemetry/dev-enabled? (constantly false)
                hyperopen.platform/now-ms (constantly 303)]
    (is (nil? (telemetry/emit! :ignored {:a 1})))
    (telemetry/log! "ignored")
    (is (= [] (telemetry/events)))))
