(ns hyperopen.api.info-client-cache-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.info-client :as info-client]
            [hyperopen.test-support.info-client :as info-support]))

(deftest info-client-serves-cached-response-within-ttl-test
  (async done
    (let [calls (atom 0)
          now-ms (atom 1000)
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (let [n (swap! calls inc)]
                                 (js/Promise.resolve (info-support/fake-http-response 200 {:call n}))))
                   :now-ms-fn (fn [] @now-ms)
                   :sleep-ms-fn (fn [_] (js/Promise.resolve nil))
                   :log-fn (fn [& _])})]
      (-> ((:request-info! client)
           {"type" "portfolio"
            "user" "0xabc"}
           {:cache-key [:portfolio "0xabc"]
            :cache-ttl-ms 200})
          (.then (fn [first-response]
                   (is (= {:call 1} first-response))
                   (reset! now-ms 1100)
                   ((:request-info! client)
                    {"type" "portfolio"
                     "user" "0xabc"}
                    {:cache-key [:portfolio "0xabc"]
                     :cache-ttl-ms 200})))
          (.then (fn [second-response]
                   (is (= {:call 1} second-response))
                   (is (= 1 @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest info-client-force-refresh-bypasses-cache-test
  (async done
    (let [calls (atom 0)
          now-ms (atom 2000)
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (let [n (swap! calls inc)]
                                 (js/Promise.resolve (info-support/fake-http-response 200 {:call n}))))
                   :now-ms-fn (fn [] @now-ms)
                   :sleep-ms-fn (fn [_] (js/Promise.resolve nil))
                   :log-fn (fn [& _])})]
      (-> ((:request-info! client)
           {"type" "userFees"
            "user" "0xabc"}
           {:cache-key [:user-fees "0xabc"]
            :cache-ttl-ms 1000})
          (.then (fn [_]
                   (reset! now-ms 2100)
                   ((:request-info! client)
                    {"type" "userFees"
                     "user" "0xabc"}
                    {:cache-key [:user-fees "0xabc"]
                     :cache-ttl-ms 1000
                     :force-refresh? true})))
          (.then (fn [response]
                   (is (= {:call 2} response))
                   (is (= 2 @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest info-client-coalesces-concurrent-requests-by-cache-key-test
  (async done
    (let [calls (atom 0)
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (swap! calls inc)
                               (js/Promise.resolve (info-support/fake-http-response 200 {:ok true})))
                   :sleep-ms-fn (fn [_] (js/Promise.resolve nil))
                   :log-fn (fn [& _])})
          p1 ((:request-info! client)
              {"type" "spotMeta"}
              {:cache-key :spot-meta
               :cache-ttl-ms 500})
          p2 ((:request-info! client)
              {"type" "spotMeta"}
              {:cache-key :spot-meta
               :cache-ttl-ms 500})]
      (-> (js/Promise.all #js [p1 p2])
          (.then (fn [results]
                   (is (= 1 @calls))
                   (is (= [{:ok true} {:ok true}]
                          (js->clj results :keywordize-keys true)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest info-client-cache-reduces-rate-limit-retries-for-identical-requests-test
  (async done
    (let [cached-statuses (atom [429 200])
          cached-fetch-calls (atom 0)
          cached-client
          (info-client/make-info-client
           {:fetch-fn (fn [_ _]
                        (swap! cached-fetch-calls inc)
                        (let [status (or (first @cached-statuses) 200)]
                          (swap! cached-statuses (fn [xs]
                                                   (if (seq xs)
                                                     (subvec xs 1)
                                                     xs)))
                          (js/Promise.resolve (info-support/fake-http-response status))))
            :sleep-ms-fn (fn [_] (js/Promise.resolve nil))
            :log-fn (fn [& _])})
          uncached-statuses (atom [429 200 429 200])
          uncached-fetch-calls (atom 0)
          uncached-client
          (info-client/make-info-client
           {:fetch-fn (fn [_ _]
                        (swap! uncached-fetch-calls inc)
                        (let [status (or (first @uncached-statuses) 200)]
                          (swap! uncached-statuses (fn [xs]
                                                     (if (seq xs)
                                                       (subvec xs 1)
                                                       xs)))
                          (js/Promise.resolve (info-support/fake-http-response status))))
            :sleep-ms-fn (fn [_] (js/Promise.resolve nil))
            :log-fn (fn [& _])})]
      (-> ((:request-info! cached-client)
           {"type" "portfolio"
            "user" "0xabc"}
           {:cache-key [:portfolio "0xabc"]
            :cache-ttl-ms 1000})
          (.then (fn [_]
                   ((:request-info! cached-client)
                    {"type" "portfolio"
                     "user" "0xabc"}
                    {:cache-key [:portfolio "0xabc"]
                     :cache-ttl-ms 1000})))
          (.then (fn [_]
                   ((:request-info! uncached-client)
                    {"type" "portfolio"
                     "user" "0xabc"}
                    {:cache-key [:portfolio "0xabc"]
                     :cache-ttl-ms 1000})))
          (.then (fn [_]
                   ((:request-info! uncached-client)
                    {"type" "portfolio"
                     "user" "0xabc"}
                    {:cache-key [:portfolio "0xabc"]
                     :cache-ttl-ms 1000
                     :force-refresh? true})))
          (.then (fn [_]
                   (let [cached-stats ((:get-request-stats cached-client))
                         uncached-stats ((:get-request-stats uncached-client))]
                     (is (= 2 @cached-fetch-calls))
                     (is (= 4 @uncached-fetch-calls))
                     (is (= 1 (:rate-limited cached-stats)))
                     (is (= 2 (:rate-limited uncached-stats)))
                     (done))))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))
