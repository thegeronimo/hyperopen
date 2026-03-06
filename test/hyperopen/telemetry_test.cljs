(ns hyperopen.telemetry-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.platform :as platform]
            [hyperopen.telemetry :as telemetry]))

(defn- reset-telemetry-state!
  []
  (telemetry/clear-events!)
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
    (telemetry/emit! :websocket/market-projection-flush {:store-id "emit-store"})
    (let [entry (first (telemetry/events))]
      (is (= :log/message (:event entry)))
      (is (string? (:message entry)))
      (is (vector? (:args entry))))
    (is (= 1 (count (telemetry/market-projection-flush-events))))
    (is (= 1 (count (telemetry/market-projection-flush-diagnostics-events))))
    (telemetry/clear-events!)
    (is (= [] (telemetry/events)))
    (is (= [] (telemetry/market-projection-flush-events)))
    (is (= [] (telemetry/market-projection-flush-diagnostics-events)))))

(deftest telemetry-market-projection-flush-rings-are-filtered-and-bounded-test
  (with-redefs [hyperopen.telemetry/dev-enabled? (constantly true)
                hyperopen.platform/now-ms (constantly 404)]
    (let [limit telemetry/market-projection-flush-event-limit
          total-flush-events (+ limit 2)]
      (telemetry/emit! :log/message {:message "not a flush event"})
      (doseq [idx (range total-flush-events)]
        (telemetry/emit! :websocket/market-projection-flush
                         {:store-id "emit-store"
                          :flush-count idx
                          :ignored idx}))
      (let [flush-events (telemetry/market-projection-flush-events)
            diagnostics-events (telemetry/market-projection-flush-diagnostics-events)]
        (is (= limit (count flush-events)))
        (is (= limit (count diagnostics-events)))
        (is (every? (fn [entry]
                      (= :websocket/market-projection-flush (:event entry)))
                    flush-events))
        (is (every? (fn [entry]
                      (= :websocket/market-projection-flush (:event entry)))
                    diagnostics-events))
        (is (= (- total-flush-events limit)
               (:flush-count (first flush-events))))
        (is (= (dec total-flush-events)
               (:flush-count (last flush-events))))
        (is (= (select-keys (first flush-events)
                            [:seq
                             :event
                             :at-ms
                             :store-id
                             :pending-count
                             :overwrite-count
                             :flush-duration-ms
                             :queue-wait-ms
                             :flush-count
                             :max-pending-depth
                             :p95-flush-duration-ms
                             :queued-total
                             :overwrite-total])
               (first diagnostics-events)))
        (is (not (contains? (first diagnostics-events) :ignored)))))))

(deftest telemetry-noop-when-disabled-test
  (with-redefs [hyperopen.telemetry/dev-enabled? (constantly false)
                hyperopen.platform/now-ms (constantly 303)]
    (is (nil? (telemetry/emit! :ignored {:a 1})))
    (telemetry/log! "ignored")
    (is (= [] (telemetry/events)))))
