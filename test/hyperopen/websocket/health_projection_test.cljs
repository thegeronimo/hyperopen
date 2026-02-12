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
