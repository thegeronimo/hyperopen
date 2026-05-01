(ns hyperopen.api.endpoints.account-accounting-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.test-support.api-stubs :as api-stubs]
            [hyperopen.test-support.async :as async-support]
            [hyperopen.api.endpoints.account :as account]))

(deftest request-user-fees-short-circuits-without-address-test
  (async done
    (let [calls (atom 0)
          post-info! (api-stubs/post-info-stub
                      (fn [_body _opts]
                        (swap! calls inc)
                        {:fees []}))]
      (-> (account/request-user-fees! post-info! nil {})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-user-fees-builds-dedupe-key-and-honors-override-test
  (let [calls (atom [])
        post-info! (api-stubs/post-info-stub calls {:fees []})]
    (account/request-user-fees! post-info! "0xAbC" {:priority :low})
    (account/request-user-fees! post-info! "0xAbC"
                                {:priority :low
                                 :dedupe-key :explicit})
    (is (= [{"type" "userFees"
             "user" "0xAbC"}
            {"type" "userFees"
             "user" "0xAbC"}]
           (mapv first @calls)))
    (is (= [{:priority :low
             :dedupe-key [:user-fees "0xabc"]
             :cache-ttl-ms 15000}
            {:priority :low
             :dedupe-key :explicit
             :cache-ttl-ms 15000}]
           (mapv second @calls)))))

(deftest request-user-fees-defaults-priority-and-dedupe-when-opts-are-nil-test
  (let [calls (atom [])
        post-info! (api-stubs/post-info-stub calls {:fees []})]
    (account/request-user-fees! post-info! "0xAbC" nil)
    (is (= {"type" "userFees"
            "user" "0xAbC"}
           (ffirst @calls)))
    (is (= {:priority :high
            :dedupe-key [:user-fees "0xabc"]
            :cache-ttl-ms 15000}
           (second (first @calls))))))

(deftest request-user-non-funding-ledger-updates-short-circuits-without-address-test
  (async done
    (let [calls (atom 0)
          post-info! (api-stubs/post-info-stub
                      (fn [_body _opts]
                        (swap! calls inc)
                        [{:time 1}]))]
      (-> (account/request-user-non-funding-ledger-updates! post-info! nil 1000 2000 {})
          (.then (fn [result]
                   (is (= [] result))
                   (is (= 0 @calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-user-non-funding-ledger-updates-builds-body-and-normalizes-response-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub calls {:data {:nonFundingLedgerUpdates [{:time 123
                                                                                        :delta {:type "deposit"
                                                                                                :usdc "10.0"}}]}})]
      (-> (account/request-user-non-funding-ledger-updates! post-info!
                                                             "0xAbC"
                                                             1000.9
                                                             2000.2
                                                             {:priority :low})
          (.then (fn [rows]
                   (let [[body opts] (first @calls)]
                     (is (= {"type" "userNonFundingLedgerUpdates"
                             "user" "0xAbC"
                             "startTime" 1000
                             "endTime" 2000}
                            body))
                     (is (= {:priority :low
                             :dedupe-key [:user-non-funding-ledger "0xabc" 1000 2000]
                             :cache-ttl-ms 5000}
                            opts))
                     (is (= [{:time 123
                              :delta {:type "deposit"
                                      :usdc "10.0"}}]
                            rows))
                     (done))))
          (.catch (async-support/unexpected-error done))))))
