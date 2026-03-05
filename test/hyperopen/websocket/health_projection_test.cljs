(ns hyperopen.websocket.health-projection-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.websocket.health-projection :as health-projection]))

(deftest append-diagnostics-event-bounds-timeline-test
  (let [state {:websocket-ui {:diagnostics-timeline [{:event :connected :at-ms 1}
                                                     {:event :reconnecting :at-ms 2}]}}
        result (health-projection/append-diagnostics-event
                state
                :gap-detected
                3
                {:group :market_data}
                2)]
    (is (= [{:event :reconnecting :at-ms 2}
            {:event :gap-detected :at-ms 3 :details {:group :market_data}}]
           (get-in result [:websocket-ui :diagnostics-timeline])))))

(deftest gap-detected-transition-requires-new-gap-test
  (is (true? (health-projection/gap-detected-transition?
              {:gap/orders_oms false
               :gap/market_data false
               :gap/account false}
              {:gap/orders_oms false
               :gap/market_data true
               :gap/account false})))
  (is (false? (health-projection/gap-detected-transition?
               {:gap/orders_oms true
                :gap/market_data false
                :gap/account false}
               {:gap/orders_oms true
                :gap/market_data true
                :gap/account false}))))

(deftest auto-recover-eligible-enforces-gates-test
  (let [health {:generated-at-ms 1700000000000
                :transport {:state :connected
                            :freshness :live}
                :streams {["l2Book" "BTC" nil nil nil]
                          {:group :market_data
                           :topic "l2Book"
                           :status :delayed
                           :last-payload-at-ms (- 1700000000000 50000)
                           :stale-threshold-ms 5000}}}
        eligible-state {:websocket-ui {:reset-in-progress? false
                                       :auto-recover-cooldown-until-ms nil}}
        cooldown-state {:websocket-ui {:reset-in-progress? false
                                       :auto-recover-cooldown-until-ms 1800000000000}}]
    (is (true? (health-projection/auto-recover-eligible?
                eligible-state
                health
                {:enabled? true
                 :severe-threshold-ms 30000})))
    (is (false? (health-projection/auto-recover-eligible?
                 eligible-state
                 health
                 {:enabled? false
                  :severe-threshold-ms 30000})))
    (is (false? (health-projection/auto-recover-eligible?
                 cooldown-state
                 health
                 {:enabled? true
                  :severe-threshold-ms 30000})))))

(deftest topic-stream-live-matches-user-selectors-case-insensitively-test
  (let [health {:transport {:state :connected
                            :freshness :live}
                :streams {["openOrders" nil "0xabc" nil nil]
                          {:topic "openOrders"
                           :status :live
                           :subscribed? true
                           :descriptor {:type "openOrders"
                                        :user "0xabc"}}
                          ["webData2" nil "0xabc" nil nil]
                          {:topic "webData2"
                           :status :live
                           :subscribed? true
                           :descriptor {:type "webData2"
                                        :user "0xabc"}}}}]
    (is (true? (health-projection/topic-stream-live? health
                                                     "openOrders"
                                                     {:user "0xAbC"})))
    (is (true? (health-projection/topic-stream-live? health
                                                     "webData2"
                                                     {:user "0xABC"})))))

(deftest topic-stream-live-requires-live-transport-and-unique-live-match-test
  (let [base-streams {["openOrders" nil "0xabc" nil nil]
                      {:topic "openOrders"
                       :status :live
                       :subscribed? true
                       :descriptor {:type "openOrders"
                                    :user "0xabc"}}
                      ["openOrders" nil "0xdef" nil nil]
                      {:topic "openOrders"
                       :status :live
                       :subscribed? true
                       :descriptor {:type "openOrders"
                                    :user "0xdef"}}}
        disconnected {:transport {:state :disconnected
                                  :freshness :offline}
                      :streams base-streams}
        ambiguous {:transport {:state :connected
                               :freshness :live}
                   :streams base-streams}
        delayed {:transport {:state :connected
                             :freshness :live}
                 :streams {["openOrders" nil "0xabc" nil nil]
                           {:topic "openOrders"
                            :status :delayed
                            :subscribed? true
                            :descriptor {:type "openOrders"
                                         :user "0xabc"}}}}]
    (is (false? (health-projection/topic-stream-live? disconnected
                                                      "openOrders"
                                                      {:user "0xabc"})))
    (is (false? (health-projection/topic-stream-live? ambiguous
                                                      "openOrders"
                                                      nil)))
    (is (false? (health-projection/topic-stream-live? delayed
                                                      "openOrders"
                                                      {:user "0xabc"})))))

(deftest topic-stream-usable-allows-event-driven-n-a-status-test
  (let [base-stream {:topic "openOrders"
                     :status :n-a
                     :subscribed? true
                     :descriptor {:type "openOrders"
                                  :user "0xabc"}}
        connected {:transport {:state :connected
                               :freshness :live}
                   :streams {["openOrders" nil "0xabc" nil nil] base-stream}}
        disconnected {:transport {:state :disconnected
                                  :freshness :offline}
                      :streams {["openOrders" nil "0xabc" nil nil] base-stream}}]
    (is (false? (health-projection/topic-stream-live? connected
                                                      "openOrders"
                                                      {:user "0xabc"})))
    (is (true? (health-projection/topic-stream-usable? connected
                                                       "openOrders"
                                                       {:user "0xabc"})))
    (is (false? (health-projection/topic-stream-usable? disconnected
                                                        "openOrders"
                                                        {:user "0xabc"})))))
