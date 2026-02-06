(ns hyperopen.websocket.acl.hyperliquid-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.websocket.acl.hyperliquid :as acl]
            [hyperopen.websocket.domain.model :as model]))

(deftest parse-raw-envelope-success-test
  (let [result (acl/parse-raw-envelope {:raw "{\"channel\":\"trades\",\"data\":[{\"coin\":\"BTC\"}]}"
                                        :socket-id 42
                                        :source :hyperliquid/ws
                                        :now-ms (constantly 1234567890)
                                        :topic->tier (fn [topic]
                                                       (if (= topic "trades")
                                                         :market
                                                         :lossless))})]
    (testing "ACL maps provider JSON into a domain envelope"
      (is (contains? result :ok))
      (is (model/domain-message-envelope? (:ok result)))
      (is (= "trades" (get-in result [:ok :topic])))
      (is (= :market (get-in result [:ok :tier])))
      (is (= 42 (get-in result [:ok :socket-id]))))))

(deftest parse-raw-envelope-error-test
  (let [result (acl/parse-raw-envelope {:raw "{invalid-json"
                                        :socket-id 0
                                        :now-ms (constantly 0)
                                        :topic->tier (constantly :lossless)})]
    (testing "Invalid provider payload returns structured error result"
      (is (contains? result :error))
      (is (not (contains? result :ok))))))

(deftest parse-raw-envelope-missing-channel-test
  (let [result (acl/parse-raw-envelope {:raw "{\"data\":[]}"
                                        :socket-id 0
                                        :now-ms (constantly 0)
                                        :topic->tier (constantly :lossless)})]
    (testing "Payloads without provider channel are rejected by ACL"
      (is (contains? result :error))
      (is (not (contains? result :ok))))))
