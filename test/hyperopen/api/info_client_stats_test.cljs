(ns hyperopen.api.info-client-stats-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.info-client :as info-client]
            [hyperopen.test-support.info-client :as info-support]))

(deftest info-client-tracks-request-type-source-and-latency-test
  (async done
    (let [now-ms-fn (info-support/stepping-now-ms [1000 1030])
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (js/Promise.resolve (info-support/fake-http-response 200 {:ok true})))
                   :now-ms-fn now-ms-fn
                   :sleep-ms-fn (fn [_] (js/Promise.resolve nil))
                   :log-fn (fn [& _])})]
      (-> ((:request-info! client)
           {"type" "frontendOpenOrders"
            "user" "0xabc"}
           {:priority :high
            :request-source "startup/stage-b"})
          (.then
           (fn [_]
             (let [stats ((:get-request-stats client))]
               (is (= {:high 1 :low 0}
                      (:started stats)))
               (is (= {:high 1 :low 0}
                      (:completed stats)))
               (is (= 1
                      (get-in stats [:started-by-type "frontendOpenOrders"])))
               (is (= 1
                      (get-in stats [:completed-by-type "frontendOpenOrders"])))
               (is (= 1
                      (get-in stats [:started-by-source "startup/stage-b"])))
               (is (= 1
                      (get-in stats [:completed-by-source "startup/stage-b"])))
               (is (= 1
                      (get-in stats [:started-by-type-source "frontendOpenOrders" "startup/stage-b"])))
               (is (= 1
                      (get-in stats [:completed-by-type-source "frontendOpenOrders" "startup/stage-b"])))
               (is (= {:count 1 :total-ms 30 :max-ms 30}
                      (get-in stats [:latency-ms-by-type "frontendOpenOrders"])))
               (is (= {:count 1 :total-ms 30 :max-ms 30}
                      (get-in stats [:latency-ms-by-source "startup/stage-b"])))
               (is (= {:count 1 :total-ms 30 :max-ms 30}
                      (get-in stats [:latency-ms-by-type-source "frontendOpenOrders" "startup/stage-b"]))))
             (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest info-client-defaults-to-unknown-request-type-and-source-test
  (async done
    (let [now-ms-fn (info-support/stepping-now-ms [2000 2015])
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (js/Promise.resolve (info-support/fake-http-response 200 {:ok true})))
                   :now-ms-fn now-ms-fn
                   :sleep-ms-fn (fn [_] (js/Promise.resolve nil))
                   :log-fn (fn [& _])})]
      (-> ((:request-info! client) {} {:priority :low})
          (.then
           (fn [_]
             (let [stats ((:get-request-stats client))]
               (is (= {:high 0 :low 1}
                      (:started stats)))
               (is (= {:high 0 :low 1}
                      (:completed stats)))
               (is (= 1
                      (get-in stats [:started-by-type "unknown"])))
               (is (= 1
                      (get-in stats [:completed-by-type "unknown"])))
               (is (= 1
                      (get-in stats [:started-by-source "unknown"])))
               (is (= 1
                      (get-in stats [:completed-by-source "unknown"])))
               (is (= {:count 1 :total-ms 15 :max-ms 15}
                      (get-in stats [:latency-ms-by-type "unknown"])))
               (is (= {:count 1 :total-ms 15 :max-ms 15}
                      (get-in stats [:latency-ms-by-source "unknown"]))))
             (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest info-client-attributes-rate-limits-by-type-and-source-test
  (async done
    (let [attempts (atom 0)
          sleeps (atom [])
          now-ms-fn (info-support/stepping-now-ms [3000 3010 3020 3040])
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (let [attempt (swap! attempts inc)]
                                 (if (= attempt 1)
                                   (js/Promise.resolve (info-support/fake-http-response 429))
                                   (js/Promise.resolve (info-support/fake-http-response 200 {:ok true})))))
                   :now-ms-fn now-ms-fn
                   :sleep-ms-fn (fn [ms]
                                  (swap! sleeps conj ms)
                                  (js/Promise.resolve nil))
                   :log-fn (fn [& _])})]
      (-> ((:request-info! client)
           {"type" "clearinghouseState"
            "user" "0xabc"}
           {:priority :high
            :request-source "websocket/user-fill-refresh"})
          (.then
           (fn [_]
             (let [stats ((:get-request-stats client))]
               (is (= 2
                      (get-in stats [:started-by-type "clearinghouseState"])))
               (is (= 2
                      (get-in stats [:completed-by-type "clearinghouseState"])))
               (is (= 1
                      (:rate-limited stats)))
               (is (= 1
                      (get-in stats [:rate-limited-by-type "clearinghouseState"])))
               (is (= 1
                      (get-in stats [:rate-limited-by-source "websocket/user-fill-refresh"])))
               (is (= 1
                      (get-in stats [:rate-limited-by-type-source "clearinghouseState" "websocket/user-fill-refresh"])))
               ;; One explicit retry delay plus cooldown wait before next attempt.
               (is (= 2 (count @sleeps))))
             (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest top-request-hotspots-sorts-by-started-then-rate-limit-and-respects-limit-test
  (let [stats {:started-by-type-source {"frontendOpenOrders" {"startup/stage-b" 5
                                                              "websocket/fill-refresh" 2}
                                        "clearinghouseState" {"websocket/fill-refresh" 5}
                                        "portfolio" {"route/portfolio" 3}}
               :completed-by-type-source {"frontendOpenOrders" {"startup/stage-b" 4
                                                                "websocket/fill-refresh" 2}
                                          "clearinghouseState" {"websocket/fill-refresh" 5}
                                          "portfolio" {"route/portfolio" 3}}
               :rate-limited-by-type-source {"frontendOpenOrders" {"startup/stage-b" 1}
                                             "clearinghouseState" {"websocket/fill-refresh" 2}
                                             "portfolio" {"route/portfolio" 0}}
               :latency-ms-by-type-source {"frontendOpenOrders" {"startup/stage-b" {:count 4
                                                                                     :total-ms 80
                                                                                     :max-ms 30}
                                                                  "websocket/fill-refresh" {:count 2
                                                                                            :total-ms 20
                                                                                            :max-ms 12}}
                                           "clearinghouseState" {"websocket/fill-refresh" {:count 5
                                                                                            :total-ms 50
                                                                                            :max-ms 15}}
                                           "portfolio" {"route/portfolio" {:count 3
                                                                           :total-ms 30
                                                                           :max-ms 12}}}}
        hotspots (info-client/top-request-hotspots stats {:limit 3})]
    (is (= 3 (count hotspots)))
    (is (= {:request-type "clearinghouseState"
            :request-source "websocket/fill-refresh"
            :started 5
            :completed 5
            :rate-limited 2
            :latency-ms {:count 5 :total-ms 50 :max-ms 15}
            :avg-latency-ms 10}
           (first hotspots)))
    (is (= {:request-type "frontendOpenOrders"
            :request-source "startup/stage-b"
            :started 5
            :completed 4
            :rate-limited 1
            :latency-ms {:count 4 :total-ms 80 :max-ms 30}
            :avg-latency-ms 20}
           (second hotspots)))
    (is (= {:request-type "portfolio"
            :request-source "route/portfolio"
            :started 3
            :completed 3
            :rate-limited 0
            :latency-ms {:count 3 :total-ms 30 :max-ms 12}
            :avg-latency-ms 10}
           (nth hotspots 2)))))
