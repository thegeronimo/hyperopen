(ns hyperopen.websocket.diagnostics-runtime-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.websocket.diagnostics-runtime :as diagnostics-runtime]))

(defn- append-diagnostics-event!
  [store event at-ms details]
  (swap! store update-in [:websocket-ui :diagnostics-timeline]
         (fnil conj [])
         {:event event
          :at-ms at-ms
          :details details}))

(deftest ws-reset-subscriptions-sends-deduped-descriptors-test
  (let [sends (atom [])
        health {:generated-at-ms 1700000000000
                :transport {:state :connected}
                :streams {["trades" "BTC" nil nil nil]
                          {:group :market_data
                           :subscribed? true
                           :descriptor {:type "trades" :coin "BTC"}}
                          ["dup-trades" "BTC" nil nil nil]
                          {:group :market_data
                           :subscribed? true
                           :descriptor {:type "trades" :coin "BTC"}}
                          ["openOrders" nil "0xabc" nil nil]
                          {:group :orders_oms
                           :subscribed? true
                           :descriptor {:type "openOrders" :user "0xabc"}}}}
        store (atom {:websocket-ui {:reset-in-progress? false
                                    :reset-cooldown-until-ms nil
                                    :reset-counts {:market_data 0 :orders_oms 0 :all 0}
                                    :diagnostics-timeline []}})]
    (diagnostics-runtime/ws-reset-subscriptions!
     {:store store
      :group :market_data
      :source :manual
      :get-health-snapshot (fn [] health)
      :effective-now-ms (fn [generated-at-ms] generated-at-ms)
      :reset-subscriptions-cooldown-ms 5000
      :send-message! (fn [payload]
                       (swap! sends conj payload)
                       true)
      :append-diagnostics-event! append-diagnostics-event!})
    (is (= [{:method "unsubscribe" :subscription {:type "trades" :coin "BTC"}}
            {:method "subscribe" :subscription {:type "trades" :coin "BTC"}}]
           @sends))
    (is (= 1 (get-in @store [:websocket-ui :reset-counts :market_data])))
    (is (= 1700000005000 (get-in @store [:websocket-ui :reset-cooldown-until-ms])))
    (is (= :reset-market (get-in @store [:websocket-ui :diagnostics-timeline 0 :event])))))

(deftest ws-reset-subscriptions-noops-when-transport-is-reconnecting-test
  (let [sends (atom [])
        health {:generated-at-ms 1700000000000
                :transport {:state :reconnecting}
                :streams {["trades" "BTC" nil nil nil]
                          {:group :market_data
                           :subscribed? true
                           :descriptor {:type "trades" :coin "BTC"}}}}
        store (atom {:websocket-ui {:reset-in-progress? false
                                    :reset-cooldown-until-ms nil
                                    :reset-counts {:market_data 0 :orders_oms 0 :all 0}
                                    :diagnostics-timeline []}})]
    (diagnostics-runtime/ws-reset-subscriptions!
     {:store store
      :group :market_data
      :source :manual
      :get-health-snapshot (fn [] health)
      :effective-now-ms (fn [generated-at-ms] generated-at-ms)
      :reset-subscriptions-cooldown-ms 5000
      :send-message! (fn [payload]
                       (swap! sends conj payload)
                       true)
      :append-diagnostics-event! append-diagnostics-event!})
    (is (empty? @sends))
    (is (= 0 (get-in @store [:websocket-ui :reset-counts :market_data])))))
