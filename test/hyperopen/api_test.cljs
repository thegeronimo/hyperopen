(ns hyperopen.api-test
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [hyperopen.api.default :as api]
            [hyperopen.domain.funding-history :as funding-history]
            [hyperopen.api.info-client :as info-client]))

(defn- fake-http-response
  ([status]
   (fake-http-response status {}))
  ([status payload]
   (doto (js-obj)
     (aset "status" status)
     (aset "ok" (= status 200))
     (aset "json" (fn [] (js/Promise.resolve (clj->js payload)))))))

(defn- stepping-now-ms
  [values]
  (let [remaining (atom (vec values))
        last-value (atom (or (last values) 0))]
    (fn []
      (if-let [next-value (first @remaining)]
        (do
          (swap! remaining subvec 1)
          (reset! last-value next-value)
          next-value)
        @last-value))))

(use-fixtures
  :each
  {:before (fn []
             (api/reset-request-runtime!))
   :after (fn []
            (api/reset-request-runtime!))})

(deftest ensure-perp-dexs-single-flight-test
  (async done
    (let [store (atom {:perp-dexs []})
          calls (atom 0)
          original-post-info hyperopen.api.default/post-info!]
      (set! hyperopen.api.default/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([body _opts]
               (swap! calls inc)
               (js/Promise.resolve [{:name "dex-a"}]))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (let [p1 (api/ensure-perp-dexs! store)
            p2 (api/ensure-perp-dexs! store)]
        (-> (js/Promise.all #js [p1 p2])
            (.then (fn [results]
                     (is (= [["dex-a"] ["dex-a"]]
                            (js->clj results)))
                     (is (= 1 @calls))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Unexpected error: " err))
                      (done)))
            (.finally
              (fn []
                (set! hyperopen.api.default/post-info! original-post-info))))))))

(deftest scheduler-prioritizes-high-after-saturation-test
  (async done
    (let [client (info-client/make-info-client {:log-fn (fn [& _])})
          enqueue-request! (:enqueue-request! client)
          started (atom [])
          releases (atom {})
          make-task (fn [label]
                      (fn []
                        (swap! started conj label)
                        (js/Promise.
                         (fn [resolve _]
                           (swap! releases assoc label resolve)))))]
      (doseq [label [:low-1 :low-2 :low-3 :low-4]]
        (enqueue-request! :low (make-task label)))
      (enqueue-request! :high (make-task :high-1))
      (enqueue-request! :low (make-task :low-5))
      (is (= [:low-1 :low-2 :low-3 :low-4] @started))
      ((get @releases :low-1) :ok)
      (js/setTimeout
       (fn []
         (is (= :high-1 (nth @started 4)))
         (doseq [label [:low-2 :low-3 :low-4 :high-1]]
           (when-let [resolve! (get @releases label)]
             (resolve! :ok)))
         (js/setTimeout
          (fn []
            (is (= :low-5 (last @started)))
            (when-let [resolve! (get @releases :low-5)]
              (resolve! :ok))
            (done))
          0))
       0))))

(deftest info-client-retries-and-parses-data-test
  (async done
    (let [attempts (atom 0)
          sleeps (atom [])
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (let [status (if (zero? @attempts) 500 200)]
                                 (swap! attempts inc)
                                 (if (= status 200)
                                   (doto (fake-http-response 200)
                                     (aset "json" (fn []
                                                    (js/Promise.resolve #js [#js {:name "dex-a"}]))))
                                   (js/Promise.resolve (fake-http-response status)))))
                   :sleep-ms-fn (fn [ms]
                                  (swap! sleeps conj ms)
                                  (js/Promise.resolve nil))
                   :log-fn (fn [& _])})]
      (-> ((:request-info! client) {"type" "perpDexs"} {:priority :high})
          (.then (fn [data]
                   (is (= 2 @attempts))
                   (is (= 1 (count @sleeps)))
                   (is (= [{:name "dex-a"}] data))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest info-client-tracks-request-type-source-and-latency-test
  (async done
    (let [now-ms-fn (stepping-now-ms [1000 1030])
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (js/Promise.resolve (fake-http-response 200 {:ok true})))
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
    (let [now-ms-fn (stepping-now-ms [2000 2015])
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (js/Promise.resolve (fake-http-response 200 {:ok true})))
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
          now-ms-fn (stepping-now-ms [3000 3010 3020 3040])
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (let [attempt (swap! attempts inc)]
                                 (if (= attempt 1)
                                   (js/Promise.resolve (fake-http-response 429))
                                   (js/Promise.resolve (fake-http-response 200 {:ok true})))))
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

(deftest info-client-serves-cached-response-within-ttl-test
  (async done
    (let [calls (atom 0)
          now-ms (atom 1000)
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (let [n (swap! calls inc)]
                                 (js/Promise.resolve (fake-http-response 200 {:call n}))))
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
                                 (js/Promise.resolve (fake-http-response 200 {:call n}))))
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
                               (js/Promise.resolve (fake-http-response 200 {:ok true})))
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
                          (js/Promise.resolve (fake-http-response status))))
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
                          (js/Promise.resolve (fake-http-response status))))
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

(deftest normalize-info-funding-row-maps-delta-shape-test
  (let [row (funding-history/normalize-info-funding-row
             {:time 1700000000000
              :delta {:type "funding"
                      :coin "HYPE"
                      :usdc "-1.2500"
                      :szi "-250.5"
                      :fundingRate "0.00045"}})]
    (is (= "HYPE" (:coin row)))
    (is (= 1700000000000 (:time-ms row)))
    (is (= :short (:position-side row)))
    (is (= 250.5 (:size-raw row)))
    (is (= -1.25 (:payment-usdc-raw row)))
    (is (= 4.5e-4 (:funding-rate-raw row)))))

(deftest funding-history-merge-and-filter-are-deterministic-test
  (let [row-a (funding-history/normalize-ws-funding-row {:time 1700000000000
                                                          :coin "HYPE"
                                                          :usdc "1.0"
                                                          :szi "100.0"
                                                          :fundingRate "0.0001"})
        row-b (funding-history/normalize-ws-funding-row {:time 1700003600000
                                                          :coin "BTC"
                                                          :usdc "-2.0"
                                                          :szi "-50.0"
                                                          :fundingRate "-0.0003"})
        merged (funding-history/merge-funding-history-rows [row-a row-b row-a] [])
        filters (funding-history/normalize-funding-history-filters
                 {:coin-set #{"BTC"}
                  :start-time-ms 0
                  :end-time-ms 2000000000000}
                 1700000000000
                 funding-history/default-window-ms)]
    (is (= 2 (count merged)))
    (is (= [1700003600000 1700000000000] (mapv :time-ms merged)))
    (is (= ["BTC"]
           (mapv :coin
                 (funding-history/filter-funding-history-rows merged filters))))))

(deftest fetch-user-funding-history-paginates-until-empty-page-test
  (async done
    (let [calls (atom [])
          original-post-info hyperopen.api.default/post-info!]
      (set! hyperopen.api.default/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([body _opts]
               (swap! calls conj body)
               (let [start-time (get body "startTime")
                     payload (cond
                               (= start-time 1000)
                               [{:time 1000
                                 :delta {:type "funding"
                                         :coin "HYPE"
                                         :usdc "1.0"
                                         :szi "10.0"
                                         :fundingRate "0.0001"}}
                                {:time 2000
                                 :delta {:type "funding"
                                         :coin "BTC"
                                         :usdc "-1.0"
                                         :szi "-3.0"
                                         :fundingRate "-0.0002"}}]
                               (= start-time 2001)
                               [{:time 3000
                                 :delta {:type "funding"
                                         :coin "ETH"
                                         :usdc "0.5"
                                         :szi "4.0"
                                         :fundingRate "0.0003"}}]
                               :else
                               [])]
                 (js/Promise.resolve payload)))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-user-funding-history! (atom {}) "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                           {:start-time-ms 1000
                                            :end-time-ms 5000})
          (.then (fn [rows]
                   (is (= 3 (count rows)))
                   (is (= [3000 2000 1000] (mapv :time-ms rows)))
                   (is (= [1000 2001 3001] (mapv #(get % "startTime") @calls)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
            (fn []
              (set! hyperopen.api.default/post-info! original-post-info)))))))

(deftest fetch-historical-orders-sends-request-and-normalizes-vector-payload-test
  (async done
    (let [calls (atom [])
          original-post-info hyperopen.api.default/post-info!]
      (set! hyperopen.api.default/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([body _opts]
               (swap! calls conj body)
               (js/Promise.resolve [{:order {:coin "BTC" :oid 1}}
                                    {:coin "ETH" :oid 2}]))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-historical-orders! (atom {}) "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" {:priority :high})
          (.then (fn [rows]
                   (is (= {"type" "historicalOrders" "user" "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                          (first @calls)))
                   (is (= 2 (count rows)))
                   (is (= "BTC" (get-in rows [0 :order :coin])))
                   (is (= "ETH" (get-in rows [1 :order :coin])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
            (fn []
              (set! hyperopen.api.default/post-info! original-post-info)))))))

(deftest fetch-historical-orders-supports-wrapped-payload-keys-test
  (async done
    (let [original-post-info hyperopen.api.default/post-info!]
      (set! hyperopen.api.default/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([_body _opts]
               (js/Promise.resolve {:orders [{:order {:coin "SOL" :oid 9}}]}))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-historical-orders! (atom {}) "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" {})
          (.then (fn [rows]
                   (is (= 1 (count rows)))
                   (is (= "SOL" (get-in rows [0 :order :coin])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
            (fn []
              (set! hyperopen.api.default/post-info! original-post-info)))))))

(deftest fetch-user-fills-sends-aggregate-request-and-preserves-liquidation-fields-test
  (async done
    (let [store (atom {:orders {}})
          calls (atom [])
          original-post-info hyperopen.api.default/post-info!]
      (set! hyperopen.api.default/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([body _opts]
               (swap! calls conj body)
               (js/Promise.resolve [{:tid 1
                                     :coin "PUMP"
                                     :side "A"
                                     :dir "Market Order Liquidation: Close Long"
                                     :liquidation {:markPx "0.001780"
                                                   :method "market"}
                                     :time 1700000000000}]))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-user-fills! store "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
          (.then (fn [rows]
                   (let [request-body (first @calls)
                         stored-row (first (get-in @store [:orders :fills]))]
                     (is (= {"type" "userFills"
                             "user" "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                             "aggregateByTime" true}
                            request-body))
                     (is (= "Market Order Liquidation: Close Long" (:dir stored-row)))
                     (is (= "0.001780" (get-in stored-row [:liquidation :markPx])))
                     (is (= "market" (get-in stored-row [:liquidation :method])))
                     (is (= rows (get-in @store [:orders :fills])))
                     (done))))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
            (fn []
              (set! hyperopen.api.default/post-info! original-post-info)))))))

(deftest fetch-user-abstraction-sends-request-and-projects-unified-mode-test
  (async done
    (let [store (atom {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                       :account {:mode :classic
                                 :abstraction-raw nil}})
          calls (atom [])
          original-post-info hyperopen.api.default/post-info!]
      (set! hyperopen.api.default/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([body _opts]
               (swap! calls conj body)
               (js/Promise.resolve "portfolioMargin"))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-user-abstraction! store "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
          (.then (fn [snapshot]
                   (is (= {"type" "userAbstraction"
                           "user" "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                          (first @calls)))
                   (is (= {:mode :unified
                           :abstraction-raw "portfolioMargin"}
                          snapshot))
                   (is (= snapshot (:account @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
            (fn []
              (set! hyperopen.api.default/post-info! original-post-info)))))))

(deftest fetch-user-abstraction-maps-classic-modes-test
  (async done
    (let [store (atom {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                       :account {:mode :unified
                                 :abstraction-raw "unifiedAccount"}})
          original-post-info hyperopen.api.default/post-info!]
      (set! hyperopen.api.default/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([_body _opts]
               (js/Promise.resolve "default"))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-user-abstraction! store "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
          (.then (fn [snapshot]
                   (is (= {:mode :classic
                           :abstraction-raw "default"}
                          snapshot))
                   (is (= snapshot (:account @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
            (fn []
              (set! hyperopen.api.default/post-info! original-post-info)))))))

(deftest fetch-user-abstraction-maps-dex-abstraction-to-classic-mode-test
  (async done
    (let [store (atom {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                       :account {:mode :unified
                                 :abstraction-raw "unifiedAccount"}})
          original-post-info hyperopen.api.default/post-info!]
      (set! hyperopen.api.default/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([_body _opts]
               (js/Promise.resolve "dexAbstraction"))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-user-abstraction! store "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
          (.then (fn [snapshot]
                   (is (= {:mode :classic
                           :abstraction-raw "dexAbstraction"}
                          snapshot))
                   (is (= snapshot (:account @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
            (fn []
              (set! hyperopen.api.default/post-info! original-post-info)))))))

(deftest fetch-user-abstraction-skips-stale-address-write-test
  (async done
    (let [store (atom {:wallet {:address "0xdddddddddddddddddddddddddddddddddddddddd"}
                       :account {:mode :classic
                                 :abstraction-raw nil}})
          original-post-info hyperopen.api.default/post-info!]
      (set! hyperopen.api.default/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([_body _opts]
               (js/Promise.resolve "unifiedAccount"))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-user-abstraction! store "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
          (.then (fn [snapshot]
                   (is (= {:mode :unified
                           :abstraction-raw "unifiedAccount"}
                          snapshot))
                   (is (= {:mode :classic
                           :abstraction-raw nil}
                          (:account @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
            (fn []
              (set! hyperopen.api.default/post-info! original-post-info)))))))
