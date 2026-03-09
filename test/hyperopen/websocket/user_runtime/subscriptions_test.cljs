(ns hyperopen.websocket.user-runtime.subscriptions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.wallet.address-watcher :as address-watcher]
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

(deftest subscription-helpers-filter-runtime-streams-and-build-user-topics-test
  (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        subscribed? @#'subscriptions-runtime/subscribed?
        user-subscriptions @#'subscriptions-runtime/user-subscriptions
        clearinghouse-keys @#'subscriptions-runtime/subscribed-clearinghouse-keys-for-address]
    (set-runtime-streams!
     {["openOrders" nil address nil nil] {:subscribed? true
                                          :descriptor {:type "openOrders"
                                                       :user address}}
      ["clearinghouseState" nil address "vault" nil] {:subscribed? true
                                                      :descriptor {:type "clearinghouseState"
                                                                   :user address
                                                                   :dex "vault"}}
      ["clearinghouseState" nil address "" nil] {:subscribed? true
                                                 :descriptor {:type "clearinghouseState"
                                                              :user address
                                                              :dex " "}}
      ["clearinghouseState" nil "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" "vault" nil]
      {:subscribed? true
       :descriptor {:type "clearinghouseState"
                    :user "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                    :dex "vault"}}
      ["clearinghouseState" nil address "ignored" nil] {:subscribed? false
                                                        :descriptor {:type "clearinghouseState"
                                                                     :user address
                                                                     :dex "ignored"}}
      ["userFills" nil address nil nil] {:subscribed? false
                                         :descriptor {:type "userFills"
                                                      :user address}}})
    (is (true? (subscribed? {:type "openOrders" :user address})))
    (is (false? (subscribed? {:type "userFills" :user address})))
    (is (= [{:type "openOrders" :user address}
            {:type "userFills" :user address}
            {:type "userFundings" :user address}
            {:type "userNonFundingLedgerUpdates" :user address}]
           (user-subscriptions address)))
    (is (= #{[address "vault"]}
           (clearinghouse-keys address)))
    (is (= #{}
           (clearinghouse-keys nil))))
  (reset-runtime-view!))

(deftest subscription-entrypoints-ignore-invalid-addresses-and-log-valid-addresses-test
  (let [address "0xAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        normalized-address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        outbound (atom [])
        logs (atom [])]
    (reset-runtime-view!)
    (with-redefs [ws-client/send-message! (fn [payload]
                                            (swap! outbound conj payload)
                                            true)
                  telemetry/log! (fn [& args]
                                   (swap! logs conj (vec args)))]
      (subscriptions-runtime/sync-perp-dex-clearinghouse-subscriptions! "not-an-address" ["vault"])
      (subscriptions-runtime/subscribe-user! nil)
      (subscriptions-runtime/unsubscribe-user! "not-an-address")
      (is (empty? @outbound))
      (is (empty? @logs))
      (subscriptions-runtime/subscribe-user! address)
      (subscriptions-runtime/unsubscribe-user! address)
      (is (= #{{:type "openOrders" :user normalized-address}
               {:type "userFills" :user normalized-address}
               {:type "userFundings" :user normalized-address}
               {:type "userNonFundingLedgerUpdates" :user normalized-address}}
             (set (map :subscription (filter #(= "subscribe" (:method %)) @outbound)))))
      (is (empty? (filter #(= "unsubscribe" (:method %)) @outbound)))
      (is (= [["Subscribed to user streams for:" normalized-address]
              ["Unsubscribed from user streams for:" normalized-address]]
             @logs))))
  (reset-runtime-view!))

(deftest user-handler-unsubscribes-old-and-subscribes-new-addresses-test
  (let [calls (atom [])
        handler (subscriptions-runtime/create-user-handler
                 (fn [address]
                   (swap! calls conj [:subscribe address]))
                 (fn [address]
                   (swap! calls conj [:unsubscribe address])))]
    (address-watcher/on-address-changed
     handler
     nil
     "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    (address-watcher/on-address-changed
     handler
     "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
     "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    (address-watcher/on-address-changed
     handler
     "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
     nil)
    (is (= [[:subscribe "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
            [:unsubscribe "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
            [:subscribe "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]
            [:unsubscribe "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]]
           @calls))
    (is (= "user-ws-subscription-handler"
           (address-watcher/get-handler-name handler)))))
