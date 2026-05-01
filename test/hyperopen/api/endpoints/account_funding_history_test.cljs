(ns hyperopen.api.endpoints.account-funding-history-test
  (:require [cljs.test :refer-macros [async deftest is]]
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
