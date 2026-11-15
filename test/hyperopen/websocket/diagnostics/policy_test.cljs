(ns hyperopen.websocket.diagnostics.policy-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.websocket.diagnostics.policy :as policy]))

(defn- base-health
  []
  {:generated-at-ms 1700000000000
   :transport {:state :connected
               :freshness :live
               :last-recv-at-ms 1699999999500
               :expected-traffic? true
               :attempt 2}
   :groups {:orders_oms {:worst-status :idle}
            :market_data {:worst-status :live}
            :account {:worst-status :n-a}}
   :streams {["trades" "BTC" nil nil nil]
             {:group :market_data
              :topic "trades"
              :subscribed? true
              :status :live
              :last-payload-at-ms 1699999995000
              :stale-threshold-ms 10000
              :descriptor {:type "trades" :coin "BTC"}}}})

(deftest healthy-transport-scores-higher-than-delayed-transport-test
  (let [healthy (policy/connection-meter-model (base-health))
        delayed (policy/connection-meter-model
                 (assoc-in (base-health) [:transport :freshness] :delayed))]
    (is (> (:score healthy) (:score delayed)))))

(deftest delayed-stream-never-improves-connection-score-test
  (let [healthy (policy/connection-meter-model (base-health))
        delayed-stream (policy/connection-meter-model
                        (-> (base-health)
                            (assoc-in [:groups :market_data :worst-status] :delayed)
                            (assoc-in [:streams ["trades" "BTC" nil nil nil] :status] :delayed)
                            (assoc-in [:streams ["trades" "BTC" nil nil nil] :last-payload-at-ms]
                                      1699999980000)))]
    (is (<= (:score delayed-stream) (:score healthy)))))

(deftest browser-network-breakdown-is-exposed-when-it-affects-score-test
  (let [meter (policy/connection-meter-model
               (base-health)
               {:network-hint {:effective-type "3g"
                               :rtt 450
                               :downlink 0.7
                               :save-data? false}})
        network-row (some #(when (= :browser-network (:id %)) %) (:breakdown meter))]
    (is (some? network-row))
    (is (= 20 (:penalty network-row)))
    (is (re-find #"3g" (:detail network-row)))))

(deftest info-rate-limit-model-reports-active-throttle-and-clears-after-cooldown-test
  (is (nil? (policy/info-rate-limit-model {} 1700000000000)))
  (is (nil? (policy/info-rate-limit-model
             {:websocket-ui {:info-rate-limit {:count 0 :until-ms 0 :last-at-ms nil}}}
             1700000000000)))
  (let [state {:websocket-ui {:info-rate-limit {:count 2
                                                :until-ms 1700000005000
                                                :last-at-ms 1700000000000}}}
        model (policy/info-rate-limit-model state 1700000000000)]
    (is (true? (:active? model)))
    (is (= 2 (:count model)))
    (is (= 5 (:retry-in-seconds model)))
    (is (= 1700000000000 (:last-at-ms model))))
  (let [state {:websocket-ui {:info-rate-limit {:count 2
                                                :until-ms 1700000005000
                                                :last-at-ms 1700000000000}}}
        model (policy/info-rate-limit-model state 1700000009000)]
    (is (false? (:active? model)))
    (is (= 2 (:count model)))
    (is (nil? (:retry-in-seconds model)))))

(deftest reconnect-and-reset-availability-models-use-deterministic-cooldown-labels-test
  (let [state {:websocket-ui {:reconnect-cooldown-until-ms 1700000005000
                              :reset-cooldown-until-ms 1700000004000}
               :websocket {:health {:generated-at-ms 1700000000000
                                    :transport {:state :connected}}}}
        health {:generated-at-ms 1700000000000
                :transport {:state :connected}}]
    (is (= "Reconnect in 5s"
           (:label (policy/reconnect-availability state health 1700000000000))))
    (is (= "Reset in 4s"
           (:label (policy/reset-availability state health 1700000000000))))))
