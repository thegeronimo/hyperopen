(ns hyperopen.api.endpoints.account-identity-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.test-support.api-stubs :as api-stubs]
            [hyperopen.test-support.async :as async-support]
            [hyperopen.api.endpoints.account :as account]))

(deftest request-extra-agents-normalizes-encoded-valid-until-and-addresses-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub
                      calls
                      {:extraAgents [{:agentName "Desk valid_until 1700000000000"
                                      :agentAddress "0xABCDEFabcdefABCDEFabcdefABCDEFabcdefABCD"}]})]
      (-> (account/request-extra-agents! post-info!
                                         "0x1234567890abcdef1234567890abcdef12345678"
                                         {})
          (.then (fn [rows]
                   (is (= [{:row-kind :named
                            :name "Desk"
                            :approval-name "Desk valid_until 1700000000000"
                            :address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                            :valid-until-ms 1700000000000}]
                          rows))
                   (let [[body opts] (first @calls)]
                     (is (= {"type" "extraAgents"
                             "user" "0x1234567890abcdef1234567890abcdef12345678"}
                            body))
                     (is (= {:priority :high
                             :dedupe-key [:extra-agents "0x1234567890abcdef1234567890abcdef12345678"]
                             :cache-ttl-ms 5000}
                            opts)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-extra-agents-supports-payload-shape-variants-test
  (async done
    (let [address "0x1234567890abcdef1234567890abcdef12345678"
          row {:agentName "Desk valid_until 1700000000000"
               :agentAddress "0xABCDEFabcdefABCDEFabcdefABCDEFabcdefABCD"}
          expected [{:row-kind :named
                     :name "Desk"
                     :approval-name "Desk valid_until 1700000000000"
                     :address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                     :valid-until-ms 1700000000000}]
          run-request (fn [payload]
                        (account/request-extra-agents!
                         (api-stubs/post-info-stub payload)
                         address
                         {}))]
      (-> (js/Promise.all
           #js [(run-request [row])
                (run-request {:extra-agents [row]})
                (run-request {:wallets [row]})
                (run-request {:data {:agents [row]}})
                (run-request {:data [row]})
                (run-request {:extraAgents {:bad true}})
                (run-request {:data {:wallets {:bad true}}})])
          (.then
           (fn [results]
             (let [results* (vec (array-seq results))]
               (is (= expected (nth results* 0)))
               (is (= expected (nth results* 1)))
               (is (= expected (nth results* 2)))
               (is (= expected (nth results* 3)))
               (is (= expected (nth results* 4)))
               (is (= [] (nth results* 5)))
               (is (= [] (nth results* 6))))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-user-webdata2-sends-user-specific-webdata-body-test
  (async done
    (let [calls (atom [])
          snapshot {:serverTime 1700000000000
                    :agentAddress "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
          post-info! (api-stubs/post-info-stub calls snapshot)]
      (-> (account/request-user-webdata2! post-info!
                                          "0x1234567890abcdef1234567890abcdef12345678"
                                          {})
          (.then (fn [result]
                   (is (= snapshot result))
                   (let [[body opts] (first @calls)]
                     (is (= {"type" "webData2"
                             "user" "0x1234567890abcdef1234567890abcdef12345678"}
                            body))
                     (is (= {:priority :high
                             :dedupe-key [:user-webdata2 "0x1234567890abcdef1234567890abcdef12345678"]
                             :cache-ttl-ms 5000}
                            opts)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-user-abstraction-builds-dedupe-key-per-address-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub calls "unifiedAccount")]
      (-> (account/request-user-abstraction! post-info! "0xAbC" {})
          (.then (fn [_]
                   (let [[body opts] (first @calls)]
                     (is (= {"type" "userAbstraction"
                             "user" "0xAbC"}
                            body))
                     (is (= [:user-abstraction "0xabc"] (:dedupe-key opts)))
                     (done))))
          (.catch (async-support/unexpected-error done))))))

(deftest request-user-abstraction-short-circuits-without-address-test
  (async done
    (let [calls (atom 0)
          post-info! (api-stubs/post-info-stub
                      (fn [_body _opts]
                        (swap! calls inc)
                        nil))]
      (-> (account/request-user-abstraction! post-info! nil {})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-user-abstraction-allows-explicit-dedupe-key-override-test
  (let [calls (atom [])
        post-info! (api-stubs/post-info-stub calls "default")]
    (account/request-user-abstraction! post-info!
                                       "0xabc"
                                       {:priority :low
                                        :dedupe-key :explicit})
    (is (= {"type" "userAbstraction"
            "user" "0xabc"}
           (ffirst @calls)))
    (is (= {:priority :low
            :dedupe-key :explicit
            :cache-ttl-ms 60000}
           (second (first @calls))))))

(deftest normalize-user-abstraction-mode-maps-known-values-test
  (is (= :unified (account/normalize-user-abstraction-mode "unifiedAccount")))
  (is (= :unified (account/normalize-user-abstraction-mode "portfolioMargin")))
  (is (= :classic (account/normalize-user-abstraction-mode " dexAbstraction ")))
  (is (= :classic (account/normalize-user-abstraction-mode "default")))
  (is (= :classic (account/normalize-user-abstraction-mode "disabled")))
  (is (= :classic (account/normalize-user-abstraction-mode "  unknown  ")))
  (is (= :classic (account/normalize-user-abstraction-mode nil))))
