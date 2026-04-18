(ns hyperopen.websocket.diagnostics.view-model-test
  (:require [cljs.spec.alpha :as s]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.websocket.diagnostics.schema :as diagnostics-schema]
            [hyperopen.websocket.diagnostics.view-model :as diagnostics-vm]))

(def ^:private address
  "0x1234567890abcdef1234567890abcdef12345678")

(defn- base-state
  []
  {:router {:path "/trade"}
   :trade-ui {:mobile-surface :ticket}
   :websocket {:health {:generated-at-ms 1700000000000
                        :transport {:state :connected
                                    :freshness :live
                                    :last-recv-at-ms 1699999999500
                                    :expected-traffic? true
                                    :attempt 2
                                    :last-close {:code 1006
                                                 :reason "abnormal"
                                                 :at-ms 1699999999000}}
                        :groups {:orders_oms {:worst-status :idle}
                                 :market_data {:worst-status :live}
                                 :account {:worst-status :n-a}}
                        :streams {["openOrders" nil address nil nil]
                                  {:group :orders_oms
                                   :topic "openOrders"
                                   :subscribed? true
                                   :status :n-a
                                   :last-payload-at-ms 1699999999500
                                   :stale-threshold-ms nil
                                   :descriptor {:type "openOrders"
                                                :user address}}}
                        :market-projection {:stores [{:store-id nil
                                                      :pending-count 0
                                                      :overwrite-total 0
                                                      :flush-count 1
                                                      :max-pending-depth 1
                                                      :p95-flush-duration-ms 6
                                                      :last-flush-duration-ms 6
                                                      :last-queue-wait-ms 2}]
                                            :flush-events [{:seq 1
                                                            :at-ms 1699999999900
                                                            :store-id nil
                                                            :pending-count 1
                                                            :overwrite-count 0
                                                            :flush-duration-ms 6
                                                            :queue-wait-ms 2}]}}}
   :websocket-ui {:diagnostics-open? true
                  :reveal-sensitive? false
                  :copy-status nil
                  :show-surface-freshness-cues? true
                  :reset-counts {:orders_oms 1}
                  :diagnostics-timeline [{:event :connected
                                          :at-ms 1699999999800
                                          :details {:user address}}]}})

(deftest footer-view-model-satisfies-schema-and-masks-sensitive-values-test
  (let [vm (diagnostics-vm/footer-view-model
            (base-state)
            {:app-version "0.1.0"
             :build-id "build-123"
             :wall-now-ms 1700000001000
             :diagnostics-timeline-limit 10
             :network-hint {:effective-type "3g"
                            :rtt 450
                            :downlink 0.7
                            :save-data? false}})
        stream-row (get-in vm [:diagnostics :stream-groups 0 :streams 0])
        timeline-row (get-in vm [:diagnostics :timeline 0])]
    (is (s/valid? ::diagnostics-schema/footer-vm vm))
    (is (not (.includes (:stream-key-text stream-row) address)))
    (is (.includes (:stream-key-text stream-row) "0x1234...45678"))
    (is (not (.includes (:details-text timeline-row) address)))
    (is (.includes (:details-text timeline-row) "0x1234...45678"))))

(deftest footer-view-model-hides-market-projection-from-visible-diagnostics-test
  (let [vm (diagnostics-vm/footer-view-model
            (base-state)
            {:app-version "0.1.0"
             :build-id "build-123"
             :wall-now-ms 1700000001000
             :diagnostics-timeline-limit 10})]
    (is (not (contains? (:diagnostics vm) :market-projection)))))

(deftest footer-view-model-builds-trader-first-diagnostics-popover-test
  (let [vm (diagnostics-vm/footer-view-model
            (-> (base-state)
                (assoc-in [:websocket :health :generated-at-ms] 1700000008000)
                (assoc-in [:websocket :health :groups :market_data :worst-status] :delayed)
                (assoc-in [:websocket :health :streams ["l2Book" "BTC" nil nil nil]]
                          {:group :market_data
                           :topic "l2Book"
                           :subscribed? true
                           :status :delayed
                           :last-payload-at-ms 1700000004800
                           :stale-threshold-ms 10000
                           :descriptor {:type "l2Book" :coin "BTC"}}))
            {:app-version "0.1.0"
             :build-id "build-123"
             :wall-now-ms 1700000009000
             :diagnostics-timeline-limit 10})
        diagnostics (:diagnostics vm)
        market-group (second (:groups diagnostics))
        stream-preview (first (:stream-groups diagnostics))
        market-stream (->> (:streams stream-preview)
                           (filter #(= "Order book" (:display-topic %)))
                           first)]
    (is (= :delayed (:state diagnostics)))
    (is (= "Market data is behind" (:title diagnostics)))
    (is (= "Market data" (:label market-group)))
    (is (= "Delayed" (:status-label market-group)))
    (is (= "Order book is 3s behind" (:detail market-group)))
    (is (= "Streams" (:title stream-preview)))
    (is (= "Order book" (:display-topic market-stream)))
    (is (= "BTC" (:stream-key-text market-stream)))
    (is (true? (:developer-open? diagnostics)))
    (is (not (contains? diagnostics :summary-rows)))
    (is (not (contains? diagnostics :transport-rows)))
    (is (not (contains? diagnostics :market-projection)))))
