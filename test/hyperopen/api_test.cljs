(ns hyperopen.api-test
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [hyperopen.api :as api]
            [hyperopen.api.info-client :as info-client]))

(defn- fake-http-response
  [status]
  (doto (js-obj)
    (aset "status" status)
    (aset "ok" (= status 200))
    (aset "json" (fn [] (js/Promise.resolve #js {})))))

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
          original-post-info hyperopen.api/post-info!]
      (set! hyperopen.api/post-info!
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
                (set! hyperopen.api/post-info! original-post-info))))))))

(deftest scheduler-prioritizes-high-after-saturation-test
  (async done
    (let [enqueue-request! @#'hyperopen.api/enqueue-request!
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

(deftest normalize-info-funding-row-maps-delta-shape-test
  (let [row (api/normalize-info-funding-row
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
  (let [row-a (api/normalize-ws-funding-row {:time 1700000000000
                                              :coin "HYPE"
                                              :usdc "1.0"
                                              :szi "100.0"
                                              :fundingRate "0.0001"})
        row-b (api/normalize-ws-funding-row {:time 1700003600000
                                              :coin "BTC"
                                              :usdc "-2.0"
                                              :szi "-50.0"
                                              :fundingRate "-0.0003"})
        merged (api/merge-funding-history-rows [row-a row-b row-a] [])]
    (is (= 2 (count merged)))
    (is (= [1700003600000 1700000000000] (mapv :time-ms merged)))
    (is (= ["BTC"]
           (mapv :coin
                 (api/filter-funding-history-rows
                  merged
                  {:coin-set #{"BTC"}
                   :start-time-ms 0
                   :end-time-ms 2000000000000}))))))

(deftest fetch-user-funding-history-paginates-until-empty-page-test
  (async done
    (let [calls (atom [])
          original-post-info hyperopen.api/post-info!]
      (set! hyperopen.api/post-info!
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
      (-> (api/fetch-user-funding-history! (atom {}) "0xabc"
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
              (set! hyperopen.api/post-info! original-post-info)))))))

(deftest fetch-historical-orders-sends-request-and-normalizes-vector-payload-test
  (async done
    (let [calls (atom [])
          original-post-info hyperopen.api/post-info!]
      (set! hyperopen.api/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([body _opts]
               (swap! calls conj body)
               (js/Promise.resolve [{:order {:coin "BTC" :oid 1}}
                                    {:coin "ETH" :oid 2}]))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-historical-orders! (atom {}) "0xabc" {:priority :high})
          (.then (fn [rows]
                   (is (= {"type" "historicalOrders" "user" "0xabc"}
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
              (set! hyperopen.api/post-info! original-post-info)))))))

(deftest fetch-historical-orders-supports-wrapped-payload-keys-test
  (async done
    (let [original-post-info hyperopen.api/post-info!]
      (set! hyperopen.api/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([_body _opts]
               (js/Promise.resolve {:orders [{:order {:coin "SOL" :oid 9}}]}))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-historical-orders! (atom {}) "0xabc" {})
          (.then (fn [rows]
                   (is (= 1 (count rows)))
                   (is (= "SOL" (get-in rows [0 :order :coin])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
            (fn []
              (set! hyperopen.api/post-info! original-post-info)))))))

(deftest fetch-user-fills-sends-aggregate-request-and-preserves-liquidation-fields-test
  (async done
    (let [store (atom {:orders {}})
          calls (atom [])
          original-post-info hyperopen.api/post-info!]
      (set! hyperopen.api/post-info!
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
      (-> (api/fetch-user-fills! store "0xabc")
          (.then (fn [rows]
                   (let [request-body (first @calls)
                         stored-row (first (get-in @store [:orders :fills]))]
                     (is (= {"type" "userFills"
                             "user" "0xabc"
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
              (set! hyperopen.api/post-info! original-post-info)))))))

(deftest fetch-user-abstraction-sends-request-and-projects-unified-mode-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :account {:mode :classic
                                 :abstraction-raw nil}})
          calls (atom [])
          original-post-info hyperopen.api/post-info!]
      (set! hyperopen.api/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([body _opts]
               (swap! calls conj body)
               (js/Promise.resolve "portfolioMargin"))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-user-abstraction! store "0xAbC")
          (.then (fn [snapshot]
                   (is (= {"type" "userAbstraction"
                           "user" "0xAbC"}
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
              (set! hyperopen.api/post-info! original-post-info)))))))

(deftest fetch-user-abstraction-maps-classic-modes-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :account {:mode :unified
                                 :abstraction-raw "unifiedAccount"}})
          original-post-info hyperopen.api/post-info!]
      (set! hyperopen.api/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([_body _opts]
               (js/Promise.resolve "default"))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-user-abstraction! store "0xabc")
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
              (set! hyperopen.api/post-info! original-post-info)))))))

(deftest fetch-user-abstraction-skips-stale-address-write-test
  (async done
    (let [store (atom {:wallet {:address "0xdef"}
                       :account {:mode :classic
                                 :abstraction-raw nil}})
          original-post-info hyperopen.api/post-info!]
      (set! hyperopen.api/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([_body _opts]
               (js/Promise.resolve "unifiedAccount"))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-user-abstraction! store "0xabc")
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
              (set! hyperopen.api/post-info! original-post-info)))))))
