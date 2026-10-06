(ns hyperopen.api.info-client-cache-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.info-client :as info-client]
            [hyperopen.api.info-client.flow :as flow]
            [hyperopen.test-support.info-client :as info-support]))

(def ^:private user-vault-equities-body
  {"type" "userVaultEquities"
   "user" "0xabc"})

(def ^:private user-vault-equities-cache-opts
  {:cache-key [:user-vault-equities "0xabc"]
   :cache-ttl-ms 5000})

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

(deftest info-client-vault-cache-refresh-replaces-a-confirmed-cache-value-before-a-normal-follow-up-test
  (async done
    (let [fetch-calls (atom 0)
          forced-response-resolve! (atom nil)
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (let [call-number (swap! fetch-calls inc)]
                                 (case call-number
                                   1 (js/Promise.resolve
                                      (info-support/fake-http-response 200 {:equity 100}))
                                   2 (js/Promise.
                                      (fn [resolve _reject]
                                        (reset! forced-response-resolve! resolve)))
                                   (js/Promise.reject
                                    (js/Error. "Unexpected extra userVaultEquities request")))))
                   :sleep-ms-fn (fn [_] (js/Promise.resolve nil))
                   :log-fn (fn [& _] nil)})
          request-info! (:request-info! client)
          refresh-opts (assoc user-vault-equities-cache-opts
                              :force-refresh? true
                              :replace-response-cache-on-force? true)]
      (-> (request-info! user-vault-equities-body user-vault-equities-cache-opts)
          (.then (fn [seeded]
                   (is (= {:equity 100} seeded))
                   (let [forced (request-info! user-vault-equities-body refresh-opts)
                         normal-follow-up (request-info! user-vault-equities-body
                                                        user-vault-equities-cache-opts)]
                     ;; The normal route refresh must join the forced request;
                     ;; it cannot return the pre-transfer cache entry.
                     (js/setTimeout
                      (fn []
                        (try
                          (is (= 2 @fetch-calls))
                          (when-let [resolve! @forced-response-resolve!]
                            (resolve! (info-support/fake-http-response 200 {:equity 200})))
                          (-> (js/Promise.all #js [forced normal-follow-up])
                              (.then (fn [responses]
                                       (is (= [{:equity 200} {:equity 200}]
                                              (js->clj responses :keywordize-keys true)))
                                       (request-info! user-vault-equities-body
                                                      user-vault-equities-cache-opts)))
                              (.then (fn [later-normal]
                                       (is (= {:equity 200} later-normal))
                                       (is (= 2 @fetch-calls))
                                       (done)))
                              (.catch (fn [err]
                                        (is false (str "Unexpected cache refresh error: " err))
                                        (done))))
                          (catch :default err
                            (is false (str "Unexpected cache refresh assertion error: " err))
                            (done))))
                      0))
                   nil))
          (.catch (fn [err]
                    (is false (str "Unexpected cache seed error: " err))
                    (done)))))))

(deftest info-client-vault-cache-refresh-prevents-an-older-cache-miss-from-overwriting-fresh-value-test
  (async done
    (let [requests (atom [])
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (js/Promise.
                                (fn [resolve reject]
                                  (swap! requests conj {:resolve resolve
                                                        :reject reject}))))
                   :sleep-ms-fn (fn [_] (js/Promise.resolve nil))
                   :log-fn (fn [& _] nil)})
          request-info! (:request-info! client)
          refresh-opts (assoc user-vault-equities-cache-opts
                              :force-refresh? true
                              :replace-response-cache-on-force? true)
          older-normal (request-info! user-vault-equities-body user-vault-equities-cache-opts)
          forced (request-info! user-vault-equities-body refresh-opts)]
      (js/setTimeout
       (fn []
         (try
           (is (= 2 (count @requests)))
           ;; The newer forced response arrives first. The older normal load
           ;; must still resolve for its caller but may not poison the cache.
           ((:resolve (second @requests))
            (info-support/fake-http-response 200 {:equity 200}))
           (-> forced
               (.then (fn [forced-value]
                        (is (= {:equity 200} forced-value))
                        ((:resolve (first @requests))
                         (info-support/fake-http-response 200 {:equity 100}))
                        older-normal))
               (.then (fn [older-value]
                        (is (= {:equity 100} older-value))
                        (request-info! user-vault-equities-body
                                       user-vault-equities-cache-opts)))
               (.then (fn [later-normal]
                        (is (= {:equity 200} later-normal))
                        (is (= 2 (count @requests)))
                        (done)))
               (.catch (fn [err]
                         (is false (str "Unexpected late cache-miss error: " err))
                         (done))))
           (catch :default err
             (is false (str "Unexpected late cache-miss assertion error: " err))
             (done))))
       0))))

(deftest info-client-vault-cache-refresh-failure-invalidates-an-expired-cache-generation-test
  (async done
    (let [now-ms (atom 1000)
          response-cache (atom {[:user-vault-equities "0xabc"]
                                {:value {:equity 100}
                                 :expires-at-ms 1010}})
          single-flight-promises (atom {})
          requests (atom [])
          request-attempt! (fn [_body _request-opts _attempt]
                             (js/Promise.
                              (fn [resolve reject]
                                (swap! requests conj {:resolve resolve
                                                      :reject reject}))))
          cache-opts (assoc user-vault-equities-cache-opts :cache-ttl-ms 10)
          refresh-opts (assoc cache-opts
                              :force-refresh? true
                              :replace-response-cache-on-force? true)]
      ;; Use the real cache flow with deterministic transport promises. The
      ;; runtime queue deliberately rethrows rejected fetches, so injecting the
      ;; attempt boundary here isolates the cache failure contract itself.
      (reset! now-ms 1011)
      (let [older-normal (flow/request-info-with-flow!
                          :high response-cache (fn [] @now-ms)
                          single-flight-promises request-attempt!
                          user-vault-equities-body cache-opts)
            forced (flow/request-info-with-flow!
                    :high response-cache (fn [] @now-ms)
                    single-flight-promises request-attempt!
                    user-vault-equities-body refresh-opts)
            forced-failure-consumed (.catch forced (fn [_] :forced-failure-consumed))]
        (is (= 2 (count @requests)))
        ((:reject (second @requests)) (js/Error. "forced refresh failed"))
        (-> forced-failure-consumed
            (.then (fn [_]
                     ((:resolve (first @requests)) {:equity 150})
                     older-normal))
            (.then (fn [older-value]
                     (is (= {:equity 150} older-value))
                     (let [fresh-request (flow/request-info-with-flow!
                                          :high response-cache (fn [] @now-ms)
                                          single-flight-promises request-attempt!
                                          user-vault-equities-body cache-opts)]
                       (is (= 3 (count @requests)))
                       (if-let [fresh-response (nth @requests 2 nil)]
                         (do
                           ((:resolve fresh-response) {:equity 300})
                           fresh-request)
                         ;; Keep the RED result assertion-based if the stale
                         ;; response was reused instead of issuing a new read.
                         (js/Promise.resolve :missing-fresh-network-request)))))
            (.then (fn [fresh-after-failure]
                     (is (= {:equity 300} fresh-after-failure))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Unexpected failed refresh cache error: " err))
                      (done))))))))

(deftest info-client-keeps-the-vault-cache-refresh-option-out-of-request-attempt-options-test
  (async done
    (let [attempt-opts (atom nil)]
      (-> (flow/request-info-with-flow!
           :high
           (atom {})
           (constantly 1000)
           (atom {})
           (fn [_body opts _attempt]
             (reset! attempt-opts opts)
             (js/Promise.resolve {:equity 200}))
           user-vault-equities-body
           (assoc user-vault-equities-cache-opts
                  :force-refresh? true
                  :replace-response-cache-on-force? true))
          (.then (fn [_]
                   (is (not (contains? @attempt-opts :force-refresh?)))
                   (is (not (contains? @attempt-opts :replace-response-cache-on-force?)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected internal option error: " err))
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
