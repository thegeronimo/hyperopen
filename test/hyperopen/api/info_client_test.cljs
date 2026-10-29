(ns hyperopen.api.info-client-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.info-client :as info-client]
            [hyperopen.api.info-client.flow :as flow]
            [hyperopen.test-support.info-client :as info-support]))

(deftest request-info-shares-single-flight-for-identical-dedupe-keys-test
  (async done
    (let [fetch-calls (atom [])
          now-ms (atom 0)
          payload #js {"meta" #js {}
                       "assetCtxs" #js []}
          fetch-fn (fn [url opts]
                     (swap! fetch-calls conj [url (js->clj opts :keywordize-keys true)])
                     (js/Promise.resolve
                      #js {:ok true
                           :status 200
                           :json (fn []
                                   (js/Promise.resolve payload))}))
          client (info-client/make-info-client
                  {:fetch-fn fetch-fn
                   :now-ms-fn (fn []
                                (swap! now-ms inc))
                   :log-fn (fn [& _] nil)})
          request-info! (:request-info! client)
          opts {:priority :high
                :dedupe-key :asset-contexts}
          p1 (request-info! {"type" "metaAndAssetCtxs"} opts)
          p2 (request-info! {"type" "metaAndAssetCtxs"} opts)]
      (is (identical? p1 p2))
      (-> (js/Promise.all #js [p1 p2])
          (.then (fn [results]
                   (is (= 1 (count @fetch-calls)))
                   (let [[url request-opts] (first @fetch-calls)]
                     (is (= "https://api.hyperliquid.xyz/info" url))
                     (is (= "POST" (:method request-opts))))
                   (is (= [{:meta {} :assetCtxs []}
                           {:meta {} :assetCtxs []}]
                          (js->clj results)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-info-force-refresh-bypasses-single-flight-without-changing-normal-dedupe-test
  (async done
    (let [fetch-calls (atom [])
          resolvers (atom [])
          client (info-client/make-info-client
                  {:fetch-fn (fn [url opts]
                               (let [call-number (inc (count @fetch-calls))]
                                 (swap! fetch-calls conj [url (js->clj opts :keywordize-keys true)])
                                 (js/Promise.
                                  (fn [resolve _reject]
                                    (swap! resolvers conj
                                           (fn []
                                             (resolve (info-support/fake-http-response 200 {:call call-number}))))))))
                   :sleep-ms-fn (fn [_] (js/Promise.resolve nil))
                   :log-fn (fn [& _] nil)})
          request-info! (:request-info! client)
          opts {:dedupe-key :open-orders}
          p1 (request-info! {"type" "frontendOpenOrders"} opts)
          p2 (request-info! {"type" "frontendOpenOrders"} opts)
          forced (request-info! {"type" "frontendOpenOrders"}
                                (assoc opts :force-refresh? true))]
      (is (identical? p1 p2))
      (is (not (identical? p1 forced)))
      (js/setTimeout
       (fn []
         (try
           (is (= 2 (count @fetch-calls)))
           (doseq [resolve! @resolvers]
             (resolve!))
           (-> (js/Promise.all #js [p1 p2 forced])
               (.then (fn [results]
                        (is (= [{:call 1}
                                {:call 1}
                                {:call 2}]
                               (js->clj results)))
                        (done)))
               (.catch (fn [err]
                         (is false (str "Unexpected error: " err))
                         (done))))
           (catch :default err
             (is false (str "Unexpected assertion error: " err))
             (done))))
       0))))

(deftest request-info-clears-single-flight-after-settlement-test
  (async done
    (let [fetch-calls (atom 0)
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (swap! fetch-calls inc)
                               (js/Promise.resolve
                                (info-support/fake-http-response 200 {:ok @fetch-calls})))
                   :sleep-ms-fn (fn [_] (js/Promise.resolve nil))
                   :log-fn (fn [& _] nil)})
          request-info! (:request-info! client)
          first-promise (request-info! {"type" "meta"} {:dedupe-key :meta})]
      (-> first-promise
          (.then (fn [first-response]
                   (is (= {:ok 1} first-response))
                   (let [next-promise (request-info! {"type" "meta"} {:dedupe-key :meta})]
                     (is (not (identical? first-promise next-promise)))
                     next-promise)))
          (.then (fn [second-response]
                   (is (= {:ok 2} second-response))
                   (is (= 2 @fetch-calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-info-supports-three-arity-entry-point-test
  (async done
    (let [client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (js/Promise.resolve
                                (info-support/fake-http-response 200 {:ok true})))
                   :sleep-ms-fn (fn [_] (js/Promise.resolve nil))
                   :log-fn (fn [& _] nil)})]
      (-> ((:request-info! client) {"type" "meta"} {:priority :low} 2)
          (.then (fn [response]
                   (is (= {:ok true} response))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-info-skips-inactive-requests-before-fetch-test
  (async done
    (let [fetch-calls (atom 0)
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (swap! fetch-calls inc)
                               (js/Promise.resolve
                                (info-support/fake-http-response 200 {:ok true})))
                   :sleep-ms-fn (fn [_] (js/Promise.resolve nil))
                   :log-fn (fn [& _] nil)})
          request-info! (:request-info! client)]
      (-> (request-info! {"type" "meta"}
                         {:dedupe-key :meta
                          :active?-fn (fn [] false)})
          (.then (fn [_]
                   (is false "Expected inactive request to reject")
                   (done)))
          (.catch (fn [err]
                    (is (= 0 @fetch-calls))
                    (is (true? (aget err "inactiveRequest")))
                    (is (= "meta" (aget err "requestType")))
                    (done)))))))

(deftest request-info-stops-retrying-once-request-becomes-inactive-test
  (async done
    (let [fetch-calls (atom 0)
          sleep-calls (atom [])
          active? (atom true)
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (swap! fetch-calls inc)
                               (js/Promise.resolve
                                (info-support/fake-http-response 429 {:error "rate-limited"})))
                   :sleep-ms-fn (fn [delay-ms]
                                  (swap! sleep-calls conj delay-ms)
                                  (reset! active? false)
                                  (js/Promise.resolve nil))
                   :log-fn (fn [& _] nil)})
          request-info! (:request-info! client)]
      (-> (request-info! {"type" "meta"}
                         {:dedupe-key :meta
                          :active?-fn (fn [] @active?)})
          (.then (fn [_]
                   (is false "Expected inactive retry sequence to reject")
                   (done)))
          (.catch (fn [err]
                    (is (= 1 @fetch-calls))
                    (is (= 1 (count @sleep-calls)))
                    (is (true? (aget err "inactiveRequest")))
                    (done)))))))

(deftest request-info-invokes-on-rate-limit-listener-on-429-test
  (async done
    (let [events (atom [])
          fetch-calls (atom 0)
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (let [call-number (swap! fetch-calls inc)]
                                 (js/Promise.resolve
                                  (if (= call-number 1)
                                    (info-support/fake-http-response 429 {:error "rate-limited"})
                                    (info-support/fake-http-response 200 {:ok true})))))
                   :sleep-ms-fn (fn [_] (js/Promise.resolve nil))
                   :now-ms-fn (constantly 1000)
                   :log-fn (fn [& _] nil)})
          set-on-rate-limit! (:set-on-rate-limit! client)
          request-info! (:request-info! client)]
      (set-on-rate-limit! (fn [event] (swap! events conj event)))
      (-> (request-info! {"type" "meta"} {:dedupe-key :meta})
          (.then (fn [response]
                   (is (= {:ok true} response))
                   (is (= 2 @fetch-calls))
                   (is (= 1 (count @events)))
                   (is (= {:at-ms 1000
                           :delay-ms 400
                           :cooldown-until-ms 1400
                           :count 1}
                          (first @events)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-info-does-not-invoke-listener-without-rate-limit-test
  (async done
    (let [events (atom [])
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (js/Promise.resolve
                                (info-support/fake-http-response 200 {:ok true})))
                   :sleep-ms-fn (fn [_] (js/Promise.resolve nil))
                   :log-fn (fn [& _] nil)})]
      ((:set-on-rate-limit! client) (fn [event] (swap! events conj event)))
      (-> ((:request-info! client) {"type" "meta"} {:dedupe-key :meta})
          (.then (fn [_]
                   (is (= [] @events))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-with-timeout-aborts-and-rejects-when-fetch-stalls-test
  (async done
    (let [abort-calls (atom 0)
          timer-callbacks (atom [])
          signal #js {:stalled true}
          controller #js {:signal signal
                          :abort (fn [] (swap! abort-calls inc))}
          request-init #js {:method "POST"}
          result (flow/fetch-with-timeout!
                  {:fetch-fn (fn [_url _init]
                               ;; Never settles: simulates a stalled /info connection.
                               (js/Promise. (fn [_resolve _reject] nil)))
                   :set-timeout-fn (fn [callback _ms]
                                     (swap! timer-callbacks conj callback)
                                     :timer-id)
                   :clear-timeout-fn (fn [_] nil)
                   :make-abort-controller (fn [] controller)
                   :request-timeout-ms 5}
                  "https://example.test/info"
                  request-init)]
      (is (identical? signal (aget request-init "signal"))
          "The abort signal must be attached to the request init.")
      (is (= 1 (count @timer-callbacks)))
      ;; Fire the scheduled timeout to simulate the deadline elapsing.
      ((first @timer-callbacks))
      (-> result
          (.then (fn [_]
                   (is false "A stalled fetch must reject, not resolve.")
                   (done)))
          (.catch (fn [err]
                    (is (= 1 @abort-calls)
                        "The stalled fetch's AbortController must be aborted.")
                    (is (true? (aget err "timeout")))
                    (done)))))))

(deftest fetch-with-timeout-passes-through-when-no-timeout-configured-test
  (async done
    (let [resp #js {:ok true}
          result (flow/fetch-with-timeout!
                  {:fetch-fn (fn [_url _init] (js/Promise.resolve resp))
                   :request-timeout-ms nil}
                  "https://example.test/info"
                  #js {:method "POST"})]
      (-> result
          (.then (fn [r]
                   (is (identical? resp r))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected pass-through error: " err))
                    (done)))))))

(defn- persistent-failure-attempts
  "Fetch count for a request whose every attempt fails with `fail`."
  [fail done-fn]
  (let [fetch-calls (atom 0)
        client (info-client/make-info-client
                {:fetch-fn (fn [_ _] (swap! fetch-calls inc) (fail))
                 :sleep-ms-fn (fn [_] (js/Promise.resolve nil))
                 :log-fn (fn [& _] nil)})]
    (-> ((:request-info! client) {"type" "meta"} {:dedupe-key :meta})
        (.then (fn [_] (done-fn :resolved @fetch-calls))
               (fn [_] (done-fn :rejected @fetch-calls))))))

(deftest request-info-retries-a-persistent-429-max-retries-times-test
  ;; a failed nested retry used to be retried again by every outer attempt:
  ;; 31 fetches instead of 5 for a request that kept getting 429
  (async done
    (persistent-failure-attempts
     #(js/Promise.resolve (info-support/fake-http-response 429 {:error "rate-limited"}))
     (fn [outcome calls]
       (is (= :rejected outcome))
       (is (= 5 calls) "the first attempt plus max-retries (4)")
       (done)))))

(deftest request-info-retries-a-persistent-network-error-max-retries-times-test
  (async done
    (persistent-failure-attempts
     #(js/Promise.reject (js/Error. "network down"))
     (fn [outcome calls]
       (is (= :rejected outcome))
       (is (= 5 calls))
       (done)))))
