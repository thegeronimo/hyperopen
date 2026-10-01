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

(deftest request-user-role-posts-user-role-with-a-long-ttl-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub calls {:role "missing"})]
      (-> (account/request-user-role! post-info!
                                      "0xABCDEF0000000000000000000000000000000001"
                                      {:priority :low})
          (.then (fn [response]
                   (is (= {:role "missing"} response))
                   (let [[body opts] (first @calls)]
                     (is (= {"type" "userRole"
                             "user" "0xABCDEF0000000000000000000000000000000001"}
                            body))
                     (is (= {:priority :low
                             :dedupe-key [:user-role "0xabcdef0000000000000000000000000000000001"]
                             :cache-ttl-ms 60000}
                            opts)))
                   (account/request-user-role! post-info! nil {})))
          (.then (fn [response]
                   (is (nil? response) "no address, no request")
                   (is (= 1 (count @calls)))
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

(deftest request-sub-accounts-normalizes-null-and-addresses-test
  (async done
    (let [owner "0x1111111111111111111111111111111111111111"
          subaccount "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          clearinghouse-state {:marginSummary {:accountValue "123.45"}}
          spot-state {:balances [{:coin "USDC"
                                  :total "10"}]}
          calls (atom [])
          run-request (fn [payload]
                        (account/request-sub-accounts!
                         (api-stubs/post-info-stub calls payload)
                         owner
                         {}))]
      (try
        (-> (run-request nil)
            (.then
             (fn [nil-result]
               (is (= [] nil-result))
               (run-request [{:name "Desk A"
                              :subAccountUser "0xAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                              :master "0x1111111111111111111111111111111111111111"
                              :clearinghouseState clearinghouse-state
                              :spotState spot-state}
                             {:name "Missing user"
                              :master owner}
                             {:name "Invalid user"
                              :subAccountUser "0xabc"
                              :master owner}
                             "not-a-row"])))
            (.then
             (fn [rows]
               (is (= [{:name "Desk A"
                        :sub-account-user subaccount
                        :master owner
                        :clearinghouse-state clearinghouse-state
                        :spot-state spot-state}]
                      rows))
               (let [[body opts] (first @calls)]
                 (is (= {"type" "subAccounts"
                         "user" owner}
                        body))
                 (is (= {:priority :high
                         :dedupe-key [:sub-accounts owner]
                         :cache-ttl-ms 5000}
                        opts)))
               (is (= 2 (count @calls)))
               (done)))
            (.catch (async-support/unexpected-error done)))
        (catch :default err
          (is false (str "request-sub-accounts! should exist and return a promise: " err))
          (done))))))

(deftest request-sub-accounts-unwraps-wrapped-payload-shapes-test
  (async done
    (let [owner "0x1111111111111111111111111111111111111111"
          subaccount "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          row {:name "Desk A"
               :subAccountUser "0xAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
               :master owner}
          expected [{:name "Desk A"
                     :sub-account-user subaccount
                     :master owner
                     :clearinghouse-state nil
                     :spot-state nil}]
          run-request (fn [payload]
                        (account/request-sub-accounts!
                         (api-stubs/post-info-stub payload)
                         owner
                         {}))]
      ;; A bare array is the canonical Hyperliquid shape, but a proxy/gateway
      ;; that wraps rows in `{:subAccounts [...]}` must not silently collapse
      ;; the page to "No subaccounts found".
      (-> (js/Promise.all
           #js [(run-request {:subAccounts [row]})
                (run-request {"subAccounts" [row]})
                (run-request {:sub-accounts [row]})
                (run-request {:subAccounts []})
                (run-request {:unexpected :shape})])
          (.then
           (fn [results]
             (let [results* (vec (array-seq results))]
               (is (= expected (nth results* 0)))
               (is (= expected (nth results* 1)))
               (is (= expected (nth results* 2)))
               (is (= [] (nth results* 3)))
               (is (= [] (nth results* 4))))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-sub-accounts-short-circuits-without-owner-test
  (async done
    (let [calls (atom 0)
          post-info! (api-stubs/post-info-stub
                      (fn [_body _opts]
                        (swap! calls inc)
                        []))]
      (try
        (-> (account/request-sub-accounts! post-info! nil {})
            (.then
             (fn [nil-result]
               (is (= [] nil-result))
               (account/request-sub-accounts! post-info! "" {})))
            (.then
             (fn [blank-result]
               (is (= [] blank-result))
               (account/request-sub-accounts! post-info! "   " {})))
            (.then
             (fn [whitespace-result]
               (is (= [] whitespace-result))
               (is (= 0 @calls))
               (done)))
            (.catch (async-support/unexpected-error done)))
        (catch :default err
          (is false (str "request-sub-accounts! should exist and return a promise: " err))
          (done))))))

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
