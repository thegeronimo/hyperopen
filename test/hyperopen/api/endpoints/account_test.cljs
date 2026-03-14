(ns hyperopen.api.endpoints.account-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is]]
            [hyperopen.test-support.api-stubs :as api-stubs]
            [hyperopen.test-support.async :as async-support]
            [hyperopen.api.endpoints.account :as account]))

(deftest request-user-funding-history-paginates-forward-by-time-test
  (async done
    (let [calls (atom [])
          delays (atom [])
          post-info! (api-stubs/post-info-body-stub
                      calls
                      (fn [body _opts]
                        (let [start-time (get body "startTime")]
                          (cond
                            (= start-time 1000)
                            [{:time-ms 1000} {:time-ms 2000}]

                            (= start-time 2001)
                            [{:time-ms 3000}]

                            :else
                            []))))
          normalize-rows-fn identity
          sort-rows-fn (fn [rows]
                         (->> rows
                              (sort-by :time-ms >)
                              vec))]
      (-> (account/request-user-funding-history! post-info!
                                                 normalize-rows-fn
                                                 sort-rows-fn
                                                 "0xabc"
                                                 1000
                                                 5000
                                                 {:wait-ms-fn (fn [delay-ms]
                                                                (swap! delays conj delay-ms)
                                                                (js/Promise.resolve nil))})
          (.then (fn [rows]
                   (is (= [3000 2000 1000] (mapv :time-ms rows)))
                   (is (= [1000 2001 3001] (mapv #(get % "startTime") @calls)))
                   (is (= [1250 1250] @delays))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-user-funding-history-supports-wrapped-payloads-test
  (async done
    (let [delays (atom [])
          post-info! (api-stubs/post-info-stub
                      (fn [body _opts]
                        (let [start-time (get body "startTime")]
                          (if (= start-time 0)
                            {:data {:fundings [{:time 1000
                                                :delta {:type "funding"
                                                        :coin "HYPE"
                                                        :usdc "1.0"
                                                        :szi "2.0"
                                                        :fundingRate "0.0001"}}]}}
                            {:data {:fundings []}}))))
          normalize-rows-fn (fn [rows]
                              (mapv (fn [row]
                                      {:time-ms (:time row)
                                       :coin (get-in row [:delta :coin])})
                                    rows))
          sort-rows-fn identity]
      (-> (account/request-user-funding-history! post-info!
                                                 normalize-rows-fn
                                                 sort-rows-fn
                                                 "0xabc"
                                                 0
                                                 5000
                                                 {:wait-ms-fn (fn [delay-ms]
                                                                (swap! delays conj delay-ms)
                                                                (js/Promise.resolve nil))})
          (.then (fn [rows]
                   (is (= 1 (count rows)))
                   (is (= [{:time-ms 1000
                            :coin "HYPE"}]
                          rows))
                   (is (= [1250] @delays))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-user-funding-history-adapts-page-delay-to-page-density-test
  (async done
    (let [delays (atom [])
          post-info! (api-stubs/post-info-stub
                      (fn [body _opts]
                        (if (= 0 (get body "startTime"))
                          [{:time-ms 0}
                           {:time-ms 1}
                           {:time-ms 2}
                           {:time-ms 3}]
                          [])))]
      (-> (account/request-user-funding-history! post-info!
                                                 identity
                                                 identity
                                                 "0xabc"
                                                 0
                                                 10
                                                 {:user-funding-page-min-delay-ms 1000
                                                  :user-funding-page-max-delay-ms 5000
                                                  :user-funding-page-size 2
                                                  :wait-ms-fn (fn [delay-ms]
                                                                (swap! delays conj delay-ms)
                                                                (js/Promise.resolve nil))})
          (.then (fn [rows]
                   (is (= 4 (count rows)))
                   (is (= [2000] @delays))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-user-funding-history-short-circuits-without-address-test
  (async done
    (let [calls (atom 0)
          post-info! (api-stubs/post-info-stub
                      (fn [_body _opts]
                        (swap! calls inc)
                        []))]
      (-> (account/request-user-funding-history! post-info!
                                                 identity
                                                 identity
                                                 nil
                                                 0
                                                 5000
                                                 {})
          (.then (fn [rows]
                   (is (= [] rows))
                   (is (= 0 @calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-user-funding-history-omits-non-numeric-time-bounds-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub calls [])]
      (-> (account/request-user-funding-history! post-info!
                                                 identity
                                                 identity
                                                 "0xabc"
                                                 "1000"
                                                 nil
                                                 nil)
          (.then (fn [rows]
                   (is (= [] rows))
                   (let [[body opts] (first @calls)]
                     (is (= {"type" "userFunding"
                             "user" "0xabc"}
                            body))
                     (is (= {:priority :high
                             :dedupe-key [:user-funding-history "0xabc" nil nil]
                             :cache-ttl-ms 5000}
                            opts)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-user-funding-history-supports-several-payload-shapes-test
  (async done
    (let [run-request (fn [payload]
                        (account/request-user-funding-history!
                         (api-stubs/post-info-stub payload)
                         identity
                         identity
                         "0xabc"
                         0
                         0
                         {}))]
      (-> (js/Promise.all
           #js [(run-request [{:time-ms 1 :id :sequential}])
                (run-request {:fundings [{:time-ms 5 :id :fundings}]})
                (run-request {:userFunding [{:time-ms 2 :id :user-funding}]})
                (run-request {:userFundings [{:time-ms 3 :id :user-fundings}]})
                (run-request {:data [{:time-ms 4 :id :data-seq}]})
                (run-request {:data {:fundings [{:time-ms 6 :id :nested-fundings}]}})
                (run-request {:data {:userFunding [{:time-ms 7 :id :nested-user-funding}]}})
                (run-request {:data {:userFundings [{:time-ms 8 :id :nested-user-fundings}]}})
                (run-request {:data {:unexpected true}})
                (run-request "not-a-map-or-seq")])
          (.then
           (fn [results]
             (let [results* (vec (array-seq results))]
               (is (= [{:time-ms 1 :id :sequential}] (nth results* 0)))
               (is (= [{:time-ms 5 :id :fundings}] (nth results* 1)))
               (is (= [{:time-ms 2 :id :user-funding}] (nth results* 2)))
               (is (= [{:time-ms 3 :id :user-fundings}] (nth results* 3)))
               (is (= [{:time-ms 4 :id :data-seq}] (nth results* 4)))
               (is (= [{:time-ms 6 :id :nested-fundings}] (nth results* 5)))
               (is (= [{:time-ms 7 :id :nested-user-funding}] (nth results* 6)))
               (is (= [{:time-ms 8 :id :nested-user-fundings}] (nth results* 7)))
               (is (= [] (nth results* 8)))
               (is (= [] (nth results* 9))))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-user-funding-history-stops-when-next-page-start-does-not-advance-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-body-stub calls [{:time-ms 999}])]
      (-> (account/request-user-funding-history! post-info!
                                                 identity
                                                 identity
                                                 "0xabc"
                                                 1000
                                                 5000
                                                 {})
          (.then (fn [rows]
                   (is (= [{:time-ms 999}] rows))
                   (is (= [1000] (mapv #(get % "startTime") @calls)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-user-funding-history-warns-on-non-empty-page-when-normalization-drops-all-rows-test
  (async done
    (let [warnings (atom [])
          console-object (or (.-console js/globalThis) #js {})
          original-warn (.-warn console-object)
          post-info! (api-stubs/post-info-stub [{:time 1000
                                                 :delta {:type "funding"
                                                         :coin "HYPE"
                                                         :usdc "1.0"
                                                         :szi "2.0"
                                                         :fundingRate "0.0001"}}])
          normalize-rows-fn (fn [_rows] [])
          sort-rows-fn identity]
      (set! (.-warn console-object)
            (fn [& args]
              (swap! warnings conj (vec args))))
      (-> (account/request-user-funding-history! post-info!
                                                 normalize-rows-fn
                                                 sort-rows-fn
                                                 "0xabc"
                                                 0
                                                 5000
                                                 {})
          (.then (fn [rows]
                   (is (= [] rows))
                   (is (= 1 (count @warnings)))
                   (let [[message payload] (first @warnings)
                         payload* (js->clj payload :keywordize-keys true)]
                     (is (= "Funding history normalization dropped all rows on a non-empty page."
                            message))
                     (is (= "funding-history-normalization-drop" (:event payload*)))
                     (is (= 1 (:raw-row-count payload*)))
                     (is (= 0 (:start-time-ms payload*)))
                     (is (= 5000 (:end-time-ms payload*))))
                   (done)))
          (.catch (async-support/unexpected-error done))
          (.finally (fn []
                      (set! (.-warn console-object) original-warn)))))))

(deftest request-user-funding-history-skips-warning-when-console-warn-is-not-a-function-test
  (async done
    (let [console-object (or (.-console js/globalThis) #js {})
          original-warn (.-warn console-object)
          post-info! (api-stubs/post-info-stub [{:time-ms 1000}])
          normalize-rows-fn (fn [_rows] [])]
      (set! (.-warn console-object) nil)
      (-> (account/request-user-funding-history! post-info!
                                                 normalize-rows-fn
                                                 identity
                                                 "0xabc"
                                                 0
                                                 5000
                                                 {})
          (.then (fn [rows]
                   (is (= [] rows))
                   (done)))
          (.catch (async-support/unexpected-error done))
          (.finally (fn []
                      (set! (.-warn console-object) original-warn)))))))

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

(deftest request-user-funding-history-stops-when-next-page-start-exceeds-end-time-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-body-stub calls [{:time-ms 1001}])]
      (-> (account/request-user-funding-history! post-info!
                                                 identity
                                                 identity
                                                 "0xabc"
                                                 1000
                                                 1001
                                                 {})
          (.then (fn [rows]
                   (is (= [{:time-ms 1001}] rows))
                   (is (= [1000] (mapv #(get % "startTime") @calls)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest normalize-portfolio-summary-supports-shape-variants-test
  (is (= {:day {:account "day" :equity "1"}
          :perp-all-time {:key "perpAllTime" :equity "2"}
          :week {:equity "3"}
          :custom-range {:range " customRange " :equity "4"}}
         (account/normalize-portfolio-summary
          [{:account "day" :equity "1"}
           {:key "perpAllTime" :equity "2"}
           ["week" {:equity "3"}]
           {:range " customRange " :equity "4"}
           ["month" 10]
           ["bad"]
           {:equity "missing-key"}
           :invalid])))
  (is (= {:month {:equity "5"}}
         (account/normalize-portfolio-summary
          {:data {"month" {:equity "5"}}})))
  (is (= {:all-time {:equity "6"}}
         (account/normalize-portfolio-summary
          {:portfolio {"all-time" {:equity "6"}}})))
  (is (= {:perp-week {:equity "7"}}
         (account/normalize-portfolio-summary
          {:perpWeek {:equity "7"}})))
  (is (= {}
         (account/normalize-portfolio-summary "not-a-map-or-seq"))))

(deftest normalize-portfolio-summary-normalizes-all-range-key-variants-test
  (doseq [[input expected] [["day" :day]
                            ["week" :week]
                            ["month" :month]
                            ["3m" :three-month]
                            ["3M" :three-month]
                            ["threeMonth" :three-month]
                            ["quarter" :three-month]
                            ["three-months" :three-month]
                            ["6m" :six-month]
                            ["sixMonth" :six-month]
                            ["half-year" :six-month]
                            ["six-months" :six-month]
                            ["1y" :one-year]
                            ["1Y" :one-year]
                            ["year" :one-year]
                            ["one-years" :one-year]
                            ["2y" :two-year]
                            ["2Y" :two-year]
                            ["two-year" :two-year]
                            ["two-years" :two-year]
                            ["alltime" :all-time]
                            ["all-time" :all-time]
                            ["perpday" :perp-day]
                            ["perp-day" :perp-day]
                            ["perpweek" :perp-week]
                            ["perp-week" :perp-week]
                            ["perpmonth" :perp-month]
                            ["perp-month" :perp-month]
                            ["perp3M" :perp-three-month]
                            ["perpquarter" :perp-three-month]
                            ["perp-three-months" :perp-three-month]
                            ["perpSixMonth" :perp-six-month]
                            ["perp-half-year" :perp-six-month]
                            ["perp-six-months" :perp-six-month]
                            ["perp1Y" :perp-one-year]
                            ["perpyear" :perp-one-year]
                            ["perp-one-years" :perp-one-year]
                            ["perp2Y" :perp-two-year]
                            ["perp-two-year" :perp-two-year]
                            ["perp-two-years" :perp-two-year]
                            ["perpalltime" :perp-all-time]
                            ["perp-all-time" :perp-all-time]
                            ["custom-range" :custom-range]]]
    (is (= {expected {:input input}}
           (account/normalize-portfolio-summary [[input {:input input}]]))))
  (is (= {:perp-custom-range {:input "perpCustomRange"}}
         (account/normalize-portfolio-summary
          [["perpCustomRange" {:input "perpCustomRange"}]])))
  (is (= {}
         (account/normalize-portfolio-summary [[nil {:input :nil-key}]
                                               ["   " {:input :blank-key}]]))))

(deftest request-portfolio-short-circuits-without-address-test
  (async done
    (let [calls (atom 0)
          post-info! (api-stubs/post-info-stub
                      (fn [_body _opts]
                        (swap! calls inc)
                        {:data {"day" {:equity "1"}}}))]
      (-> (account/request-portfolio! post-info! nil {})
          (.then (fn [result]
                   (is (= {} result))
                   (is (= 0 @calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-portfolio-builds-dedupe-key-and-normalizes-response-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub calls {:data {"day" {:equity "1"}
                                                             "perpAllTime" {:equity "2"}}})]
      (-> (account/request-portfolio! post-info! "0xAbC" {:priority :low})
          (.then (fn [summary]
                   (let [[body opts] (first @calls)]
                     (is (= {"type" "portfolio"
                             "user" "0xAbC"}
                            body))
                     (is (= {:priority :low
                             :dedupe-key [:portfolio "0xabc"]
                             :cache-ttl-ms 8000}
                            opts))
                     (is (= {:day {:equity "1"}
                             :perp-all-time {:equity "2"}}
                            summary))
                     (done))))
          (.catch (async-support/unexpected-error done))))))

(deftest request-portfolio-defaults-priority-and-dedupe-when-opts-are-nil-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub calls {:data {"month" {:equity "10"}}})]
      (-> (account/request-portfolio! post-info! "0xAbC" nil)
          (.then (fn [summary]
                   (is (= {"type" "portfolio"
                           "user" "0xAbC"}
                          (ffirst @calls)))
                   (is (= {:priority :high
                           :dedupe-key [:portfolio "0xabc"]
                           :cache-ttl-ms 8000}
                          (second (first @calls))))
                   (is (= {:month {:equity "10"}}
                          summary))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-portfolio-allows-explicit-dedupe-key-override-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub calls {:data {"day" {:equity "1"}}})]
      (-> (account/request-portfolio! post-info!
                                      "0xAbC"
                                      {:priority :low
                                       :dedupe-key :explicit})
          (.then (fn [_summary]
                   (is (= {"type" "portfolio"
                           "user" "0xAbC"}
                          (ffirst @calls)))
                   (is (= {:priority :low
                           :dedupe-key :explicit
                           :cache-ttl-ms 8000}
                          (second (first @calls))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

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
