(ns hyperopen.api.endpoints.account-clearinghouse-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.test-support.api-stubs :as api-stubs]
            [hyperopen.test-support.async :as async-support]
            [hyperopen.api.endpoints.account :as account]))

(deftest request-spot-clearinghouse-state-short-circuits-without-address-test
  (async done
    (let [calls (atom 0)
          post-info! (api-stubs/post-info-stub
                      (fn [_body _opts]
                        (swap! calls inc)
                        {}))]
      (-> (account/request-spot-clearinghouse-state! post-info! nil {})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-spot-clearinghouse-state-builds-request-body-test
  (let [calls (atom [])
        post-info! (api-stubs/post-info-stub calls {})]
    (account/request-spot-clearinghouse-state! post-info! "0xAbC" {:priority :low})
    (is (= {"type" "spotClearinghouseState"
            "user" "0xAbC"}
           (ffirst @calls)))
    (is (= {:priority :low
            :dedupe-key [:spot-clearinghouse-state "0xabc"]
            :cache-ttl-ms 15000}
           (second (first @calls))))))

(deftest request-spot-clearinghouse-state-allows-explicit-policy-overrides-test
  (let [calls (atom [])
        post-info! (api-stubs/post-info-stub calls {})]
    (account/request-spot-clearinghouse-state! post-info!
                                               "0xAbC"
                                               {:priority :low
                                                :dedupe-key :explicit
                                                :cache-ttl-ms 777})
    (is (= {"type" "spotClearinghouseState"
            "user" "0xAbC"}
           (ffirst @calls)))
    (is (= {:priority :low
            :dedupe-key :explicit
            :cache-ttl-ms 777}
           (second (first @calls))))))

(deftest request-clearinghouse-state-uses-optional-dex-test
  (let [calls (atom [])
        post-info! (api-stubs/post-info-stub calls {})]
    (account/request-clearinghouse-state! post-info! "0xabc" nil {})
    (account/request-clearinghouse-state! post-info! "0xabc" "" {})
    (account/request-clearinghouse-state! post-info! "0xabc" "vault" {:priority :low})
    (is (= [{"type" "clearinghouseState"
             "user" "0xabc"}
            {"type" "clearinghouseState"
             "user" "0xabc"}
            {"type" "clearinghouseState"
             "user" "0xabc"
             "dex" "vault"}]
           (mapv first @calls)))
    (is (= [{:priority :high
             :dedupe-key [:clearinghouse-state "0xabc" nil]
             :cache-ttl-ms 5000}
            {:priority :high
             :dedupe-key [:clearinghouse-state "0xabc" nil]
             :cache-ttl-ms 5000}
            {:priority :low
             :dedupe-key [:clearinghouse-state "0xabc" "vault"]
             :cache-ttl-ms 5000}]
           (mapv second @calls)))))

(deftest request-clearinghouse-state-allows-explicit-policy-overrides-test
  (let [calls (atom [])
        post-info! (api-stubs/post-info-stub calls {})]
    (account/request-clearinghouse-state! post-info!
                                          "0xAbC"
                                          "Vault"
                                          {:priority :low
                                           :dedupe-key :explicit
                                           :cache-ttl-ms 777})
    (is (= {"type" "clearinghouseState"
            "user" "0xAbC"
            "dex" "Vault"}
           (ffirst @calls)))
    (is (= {:priority :low
            :dedupe-key :explicit
            :cache-ttl-ms 777}
           (second (first @calls))))))

(deftest request-clearinghouse-state-short-circuits-without-address-test
  (async done
    (let [calls (atom 0)
          post-info! (api-stubs/post-info-stub
                      (fn [_body _opts]
                        (swap! calls inc)
                        {}))]
      (-> (account/request-clearinghouse-state! post-info! nil "vault" {})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))
