(ns hyperopen.websocket.user-runtime.subscriptions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.user-runtime.subscriptions :as subscriptions-runtime]))

(defn- default-runtime-view
  []
  {:active-socket-id nil
   :connection {:status :disconnected}
   :stream {:streams {}
            :metrics {}
            :tier-depth {:market 0
                         :lossless 0}
            :market-coalesce {:pending {}}
            :transport {:state :disconnected}}})

(defn- reset-runtime-view!
  []
  (reset! ws-client/runtime-view (default-runtime-view)))

(defn- set-runtime-streams!
  [streams]
  (reset! ws-client/runtime-view
          (assoc-in (default-runtime-view) [:stream :streams] streams)))

(deftest sync-perp-dex-clearinghouse-subscriptions-reads-runtime-view-test
  (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        dex-a-key ["clearinghouseState" nil address "dex-a" nil]
        dex-b-key ["clearinghouseState" nil address "dex-b" nil]
        outbound (atom [])]
    (reset-runtime-view!)
    (with-redefs [ws-client/send-message! (fn [payload]
                                            (swap! outbound conj payload)
                                            true)]
      (subscriptions-runtime/sync-perp-dex-clearinghouse-subscriptions! address ["dex-a" "dex-b"])
      (set-runtime-streams!
       {dex-a-key {:topic "clearinghouseState"
                   :subscribed? true
                   :descriptor {:type "clearinghouseState"
                                :user address
                                :dex "dex-a"}}
        dex-b-key {:topic "clearinghouseState"
                   :subscribed? true
                   :descriptor {:type "clearinghouseState"
                                :user address
                                :dex "dex-b"}}})
      (subscriptions-runtime/sync-perp-dex-clearinghouse-subscriptions! address ["dex-a" "dex-b"])
      (subscriptions-runtime/sync-perp-dex-clearinghouse-subscriptions! address ["dex-b"])
      (is (= #{{:type "clearinghouseState" :user address :dex "dex-a"}
               {:type "clearinghouseState" :user address :dex "dex-b"}}
             (set (map :subscription (filter #(= "subscribe" (:method %)) @outbound)))))
      (is (= #{{:type "clearinghouseState" :user address :dex "dex-a"}}
             (set (map :subscription (filter #(= "unsubscribe" (:method %)) @outbound)))))))
    (reset-runtime-view!))

(deftest subscribe-and-unsubscribe-user-read-runtime-view-state-test
  (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        outbound (atom [])]
    (set-runtime-streams!
     {["openOrders" nil address nil nil] {:topic "openOrders"
                                          :subscribed? true
                                          :descriptor {:type "openOrders"
                                                       :user address}}
      ["userFills" nil address nil nil] {:topic "userFills"
                                         :subscribed? true
                                         :descriptor {:type "userFills"
                                                      :user address}}
      ["userFundings" nil address nil nil] {:topic "userFundings"
                                            :subscribed? false
                                            :descriptor {:type "userFundings"
                                                         :user address}}
      ["userNonFundingLedgerUpdates" nil address nil nil] {:topic "userNonFundingLedgerUpdates"
                                                           :subscribed? true
                                                           :descriptor {:type "userNonFundingLedgerUpdates"
                                                                        :user address}}
      ["clearinghouseState" nil address "vault" nil] {:topic "clearinghouseState"
                                                      :subscribed? true
                                                      :descriptor {:type "clearinghouseState"
                                                                   :user address
                                                                   :dex "vault"}}})
    (with-redefs [ws-client/send-message! (fn [payload]
                                            (swap! outbound conj payload)
                                            true)]
      (subscriptions-runtime/subscribe-user! address)
      (subscriptions-runtime/unsubscribe-user! address)
      (is (= #{{:type "userFundings" :user address}}
             (set (map :subscription (filter #(= "subscribe" (:method %)) @outbound)))))
      (is (= #{{:type "openOrders" :user address}
               {:type "userFills" :user address}
               {:type "userNonFundingLedgerUpdates" :user address}
               {:type "clearinghouseState" :user address :dex "vault"}}
             (set (map :subscription (filter #(= "unsubscribe" (:method %)) @outbound)))))))
    (reset-runtime-view!))
