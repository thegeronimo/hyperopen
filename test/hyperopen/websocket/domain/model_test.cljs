(ns hyperopen.websocket.domain.model-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.websocket.domain.model :as model]))

(deftest normalize-method-and-subscription-key-branches-test
  (testing "normalize-method handles nil and mixed-case values"
    (is (nil? (model/normalize-method nil)))
    (is (= "subscribe" (model/normalize-method "SuBsCrIbE"))))
  (testing "subscription-key uses canonical fields when available"
    (is (= ["trades" "BTC" "0xabc" "mainnet" "1m"]
           (model/subscription-key {:type "trades"
                                    :coin "BTC"
                                    :user "0xabc"
                                    :dex "mainnet"
                                    :interval "1m"}))))
  (testing "subscription-key falls back to :raw key for empty subscriptions"
    (let [key* (model/subscription-key {:foo "bar"})]
      (is (= :raw (first key*)))
      (is (string? (second key*))))))

(deftest apply-subscription-intent-branches-test
  (let [base {}
        subscription {:type "trades" :coin "BTC"}
        subscribed (model/apply-subscription-intent base {:method "subscribe"
                                                          :subscription subscription})]
    (testing "subscribe and unsubscribe mutate desired subscriptions by derived key"
      (is (= subscription (get subscribed (model/subscription-key subscription))))
      (is (= {} (model/apply-subscription-intent subscribed {:method "unsubscribe"
                                                             :subscription subscription}))))
    (testing "unknown methods and non-map subscriptions are ignored"
      (is (= subscribed (model/apply-subscription-intent subscribed {:method "noop"
                                                                     :subscription subscription})))
      (is (= subscribed (model/apply-subscription-intent subscribed {:method "subscribe"
                                                                     :subscription "BTC"}))))))

(deftest constructor-and-predicate-branches-test
  (let [connection-basic (model/make-connection-command :connect 10)
        connection-attrs (model/make-connection-command :connect 10 {:socket-id :primary})
        connection-applied (apply model/make-connection-command [:connect 11])
        envelope (model/make-domain-message-envelope {:topic "trades"
                                                      :tier :market
                                                      :ts 22
                                                      :payload {:channel "trades"}
                                                      :source :ws
                                                      :socket-id 9})
        event-basic (model/make-transport-event :socket/open 31)
        event-attrs (model/make-transport-event :socket/message 32 {:socket-id :primary})
        event-applied (apply model/make-transport-event [:socket/open 33])
        runtime-basic (model/make-runtime-msg :evt/socket-open 41)
        runtime-attrs (model/make-runtime-msg :evt/socket-message 42 {:socket-id :primary})
        runtime-applied (apply model/make-runtime-msg [:evt/socket-open 43])
        effect-basic (model/make-runtime-effect :fx/socket-connect)
        effect-attrs (model/make-runtime-effect :fx/socket-send {:socket-id :primary})
        effect-applied (apply model/make-runtime-effect [:fx/socket-connect])
        topic-group-applied (apply model/topic->group ["trades"])]
    (testing "constructor arities return expected maps"
      (is (= {:op :connect :ts 10} connection-basic))
      (is (= :primary (:socket-id connection-attrs)))
      (is (= 11 (:ts connection-applied)))
      (is (= "trades" (:topic envelope)))
      (is (= :socket/open (:event/type event-basic)))
      (is (= :primary (:socket-id event-attrs)))
      (is (= 33 (:ts event-applied)))
      (is (= :evt/socket-open (:msg/type runtime-basic)))
      (is (= :primary (:socket-id runtime-attrs)))
      (is (= 43 (:ts runtime-applied)))
      (is (= :fx/socket-connect (:fx/type effect-basic)))
      (is (= :primary (:socket-id effect-attrs)))
      (is (= :fx/socket-connect (:fx/type effect-applied)))
      (is (= :market_data topic-group-applied)))
    (testing "predicates accept valid values and reject invalid branches"
      (is (true? (model/connection-command? connection-attrs)))
      (is (false? (model/connection-command? {:op "connect" :ts 10})))
      (is (true? (model/domain-message-envelope? envelope)))
      (is (false? (model/domain-message-envelope? (assoc envelope :payload []))))
      (is (true? (model/transport-event? event-basic)))
      (is (false? (model/transport-event? {:event/type :unknown :ts 10})))
      (is (false? (model/transport-event? {:event/type :socket/open :ts "10"})))
      (is (true? (model/runtime-msg? runtime-basic)))
      (is (false? (model/runtime-msg? {:msg/type :evt/unknown :ts 10})))
      (is (false? (model/runtime-msg? {:msg/type :evt/socket-open :ts nil})))
      (is (true? (model/runtime-effect? effect-basic)))
      (is (false? (model/runtime-effect? {:fx/type :fx/unknown}))))))

(deftest market-coalesce-key-and-topic-group-branches-test
  (testing "market coalesce key resolves coin from payload and nested data variants"
    (is (= ["trades" "BTC"]
           (model/market-coalesce-key {:topic "trades"
                                       :payload {:coin "BTC"}})))
    (is (= ["trades" "ETH"]
           (model/market-coalesce-key {:topic "trades"
                                       :payload {:data {:coin "ETH"}}})))
    (is (= ["trades" "SOL"]
           (model/market-coalesce-key {:topic "trades"
                                       :payload {:data [{:coin "SOL"}]}})))
    (is (= ["trades" "ARB"]
           (model/market-coalesce-key {:topic "trades"
                                       :payload {:data [{:symbol "ARB"}]}})))
    (is (= ["trades" "AVAX"]
           (model/market-coalesce-key {:topic "trades"
                                       :payload {:data [{:asset "AVAX"}]}})))
    (is (= ["trades" nil]
           (model/market-coalesce-key {:topic "trades"
                                       :payload {:data []}}))))
  (testing "topic->group handles both arities and fallback groups"
    (is (= :market_data (model/topic->group "trades")))
    (is (= :market_data (model/topic->group "candle")))
    (is (= :account (model/topic->group "unknown-topic")))
    (is (= :custom (apply model/topic->group [{"foo" :custom} "foo"])))
    (is (= :custom (model/topic->group {"foo" :custom} "foo")))
    (is (= :account (model/topic->group {"foo" :custom} "bar")))))
