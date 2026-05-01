(ns hyperopen.api.endpoints.account-staking-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is]]
            [hyperopen.test-support.api-stubs :as api-stubs]
            [hyperopen.test-support.async :as async-support]
            [hyperopen.api.endpoints.account :as account]))

(deftest request-staking-validator-summaries-normalizes-array-stats-shape-test
  (async done
    (let [calls (atom [])
          validator "0x000000000056f99d36b6f2e0c51fd41496bbacb8"
          post-info! (api-stubs/post-info-stub
                      calls
                      [{:validator validator
                        :signer "0x0000000008b0b558419582041f85740344ae8fde"
                        :name "ValiDAO"
                        :description "The People's Validator."
                        :stake 556530876114619
                        :isJailed false
                        :isActive true
                        :commission "0.04"
                        :stats [["day" {:uptimeFraction "1.0"
                                        :predictedApr "0.0215700249"
                                        :nSamples 1440}]
                                ["week" {:uptimeFraction "0.9941468254"
                                         :predictedApr "0.0214437718"
                                         :nSamples 10080}]
                                ["month" {:uptimeFraction "0.9986342593"
                                          :predictedApr "0.0215405659"
                                          :nSamples 43200}]]}])]
      (-> (account/request-staking-validator-summaries! post-info! {})
          (.then (fn [rows]
                   (is (= 1 (count rows)))
                   (let [row (first rows)]
                     (is (= validator (:validator row)))
                     (is (= "ValiDAO" (:name row)))
                     (is (< (js/Math.abs (- 5565308.76114619
                                            (:stake row)))
                            1e-9))
                     (is (= 1 (:uptime-fraction (get-in row [:stats :day]))))
                     (is (= 0.0215700249 (get-in row [:stats :day :predicted-apr])))
                     (is (= 0.9941468254 (get-in row [:stats :week :uptime-fraction])))
                     (is (= 0.0215405659 (get-in row [:stats :month :predicted-apr]))))
                   (let [[body opts] (first @calls)]
                     (is (= {"type" "validatorSummaries"} body))
                     (is (= {:priority :high
                             :dedupe-key :validator-summaries
                             :cache-ttl-ms 10000}
                            opts)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-staking-validator-summaries-supports-map-and-entry-object-stats-shapes-test
  (async done
    (let [validator-a "0x000000000056f99d36b6f2e0c51fd41496bbacb8"
          validator-b "0x0000000008b0b558419582041f85740344ae8fde"
          post-info! (api-stubs/post-info-stub
                      [{:validator validator-a
                        :stats {:day {:uptimeFraction "0.99"
                                      :predictedApr "0.02"
                                      :nSamples 10}
                                "week" {:uptime-fraction "0.98"
                                        :predicted-apr "0.03"
                                        :sample-count 20}
                                :month {:uptimeFraction "0.97"
                                        :predictedApr "0.04"
                                        :sampleCount 30}}}
                       {:validator validator-b
                        :stats [{:key "day"
                                 :uptimeFraction "0.96"
                                 :predictedApr "0.05"
                                 :nSamples 40}
                                {:name "week"
                                 :value {:uptimeFraction "0.95"
                                         :predictedApr "0.06"
                                         :nSamples 50}}
                                {:period "month"
                                 :stats {:uptimeFraction "0.94"
                                         :predictedApr "0.07"
                                         :nSamples 60}}]}])]
      (-> (account/request-staking-validator-summaries! post-info! {})
          (.then
           (fn [rows]
             (is (= 2 (count rows)))
             (let [[map-row entry-row] rows]
               (is (= 0.99 (get-in map-row [:stats :day :uptime-fraction])))
               (is (= 0.03 (get-in map-row [:stats :week :predicted-apr])))
               (is (= 30 (get-in map-row [:stats :month :sample-count])))
               (is (= 0.96 (get-in entry-row [:stats :day :uptime-fraction])))
               (is (= 50 (get-in entry-row [:stats :week :sample-count])))
               (is (= 0.07 (get-in entry-row [:stats :month :predicted-apr]))))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-staking-delegator-summary-builds-request-and-normalizes-response-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub
                      calls
                      {:delegated "1.5"
                       :undelegated 2
                       :totalPendingWithdrawal "3.25"
                       :pending-withdrawals "4"})]
      (-> (account/request-staking-delegator-summary! post-info! "0xAbC" {:priority :low})
          (.then
           (fn [summary]
             (is (= {:delegated 1.5
                     :undelegated 2
                     :total-pending-withdrawal 3.25
                     :pending-withdrawals 4}
                    summary))
             (let [[body opts] (first @calls)]
               (is (= {"type" "delegatorSummary"
                       "user" "0xAbC"}
                      body))
               (is (= {:priority :low
                       :dedupe-key [:delegator-summary "0xabc"]
                       :cache-ttl-ms 5000}
                      opts)))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-staking-delegations-builds-request-and-normalizes-response-test
  (async done
    (let [calls (atom [])
          validator-a "0x000000000056f99d36b6f2e0c51fd41496bbacb8"
          validator-b "0x0000000008b0b558419582041f85740344ae8fde"
          post-info! (api-stubs/post-info-stub
                      calls
                      [{:validator (str/upper-case validator-a)
                        :amount "1.5"
                        :lockedUntilTimestamp 1000}
                       {:validator validator-b
                        :amount 2
                        :lockedUntil 2000}
                       {:validator "invalid"
                        :amount 3}])]
      (-> (account/request-staking-delegations! post-info! "0xAbC" {:priority :low})
          (.then
           (fn [rows]
             (is (= [{:validator validator-a
                      :amount 1.5
                      :locked-until-timestamp 1000}
                     {:validator validator-b
                      :amount 2
                      :locked-until-timestamp 2000}]
                    rows))
             (let [[body opts] (first @calls)]
               (is (= {"type" "delegations"
                       "user" "0xAbC"}
                      body))
               (is (= {:priority :low
                       :dedupe-key [:delegations "0xabc"]
                       :cache-ttl-ms 5000}
                      opts)))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-staking-delegator-rewards-builds-request-and-normalizes-response-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub
                      calls
                      [{:time 1000
                        :source "staking"
                        :totalAmount "1.25"}
                       {:time-ms 2000
                        :amount "2.5"}])]
      (-> (account/request-staking-delegator-rewards! post-info! "0xAbC" {:priority :low})
          (.then
           (fn [rows]
             (is (= [{:time-ms 2000
                      :source :unknown
                      :total-amount 2.5}
                     {:time-ms 1000
                      :source :staking
                      :total-amount 1.25}]
                    rows))
             (let [[body opts] (first @calls)]
               (is (= {"type" "delegatorRewards"
                       "user" "0xAbC"}
                      body))
               (is (= {:priority :low
                       :dedupe-key [:delegator-rewards "0xabc"]
                       :cache-ttl-ms 10000}
                      opts)))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-staking-delegator-history-builds-request-and-normalizes-response-test
  (async done
    (let [calls (atom [])
          validator "0x000000000056f99d36b6f2e0c51fd41496bbacb8"
          post-info! (api-stubs/post-info-stub
                      calls
                      [{:time 100
                        :hash " 0x1 "
                        :delta {:delegate {:validator (str/upper-case validator)
                                           :amount "1.0"}}}
                       {:time 200
                        :delta {:delegate {:validator validator
                                           :amount "2.0"
                                           :isUndelegate true}}}
                       {:time-ms 300
                        :delta {:cDeposit {:amount "3.0"}}}
                       {:time 400
                        :delta {:cWithdraw {:amount "4.0"}}}
                       {:time 500
                        :delta {:withdrawal {:amount "5.0"
                                             :phase "ready"}}}
                       {:time 600
                        :delta {:unexpected {:foo "bar"}}}])]
      (-> (account/request-staking-delegator-history! post-info! "0xAbC" {:priority :low})
          (.then
           (fn [rows]
             (is (= [{:time-ms 600
                      :hash nil
                      :delta {:kind :unknown
                              :raw {:unexpected {:foo "bar"}}}}
                     {:time-ms 500
                      :hash nil
                      :delta {:kind :withdrawal
                              :amount 5
                              :phase :ready}}
                     {:time-ms 400
                      :hash nil
                      :delta {:kind :withdraw
                              :amount 4}}
                     {:time-ms 300
                      :hash nil
                      :delta {:kind :deposit
                              :amount 3}}
                     {:time-ms 200
                      :hash nil
                      :delta {:kind :undelegate
                              :validator validator
                              :amount 2
                              :is-undelegate? true}}
                     {:time-ms 100
                      :hash "0x1"
                      :delta {:kind :delegate
                              :validator validator
                              :amount 1
                              :is-undelegate? false}}]
                    rows))
             (let [[body opts] (first @calls)]
               (is (= {"type" "delegatorHistory"
                       "user" "0xAbC"}
                      body))
               (is (= {:priority :low
                       :dedupe-key [:delegator-history "0xabc"]
                       :cache-ttl-ms 10000}
                      opts)))
             (done)))
          (.catch (async-support/unexpected-error done))))))
