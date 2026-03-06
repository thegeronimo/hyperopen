(ns hyperopen.api.endpoints.market-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.test-support.api-stubs :as api-stubs]
            [hyperopen.test-support.async :as async-support]
            [hyperopen.api.endpoints.market :as market]))

(defn- funding-history-row
  [coin time-ms funding-rate premium]
  {:coin coin
   :fundingRate funding-rate
   :premium premium
   :time time-ms})

(deftest request-meta-and-asset-ctxs-builds-dedupe-keys-by-dex-test
  (let [calls (atom [])
        post-info! (api-stubs/post-info-stub calls [])]
    (market/request-meta-and-asset-ctxs! post-info! nil {})
    (market/request-meta-and-asset-ctxs! post-info! "vault" {})
    (is (= [{"type" "metaAndAssetCtxs"}
            {"type" "metaAndAssetCtxs" "dex" "vault"}]
           (mapv first @calls)))
    (is (= [:meta-and-asset-ctxs-default
            [:meta-and-asset-ctxs "vault"]]
           (mapv (comp :dedupe-key second) @calls)))
    (is (= [4000 4000]
           (mapv (comp :cache-ttl-ms second) @calls)))))

(deftest request-meta-and-asset-ctxs-respects-explicit-dedupe-key-test
  (let [calls (atom [])
        post-info! (api-stubs/post-info-stub calls [])]
    (market/request-meta-and-asset-ctxs! post-info! ""
                                         {:priority :low
                                          :dedupe-key :explicit})
    (let [[body opts] (first @calls)]
      (is (= {"type" "metaAndAssetCtxs"} body))
      (is (= {:priority :low
              :dedupe-key :explicit
              :cache-ttl-ms 4000}
             opts)))))

(deftest request-perp-dexs-parses-named-dexes-test
  (async done
    (let [post-info! (api-stubs/post-info-stub [{:name "vault"}
                                                {:name "scaled"
                                                 :deployerFeeScale 0.25}
                                                {:name ""}
                                                {:foo "bar"}
                                                {:name "partner"}])]
      (-> (market/request-perp-dexs! post-info! {})
          (.then (fn [payload]
                   (is (= ["vault" "scaled" "partner"] (:dex-names payload)))
                   (is (= {"scaled" {:deployer-fee-scale 0.25}}
                          (:fee-config-by-name payload)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-candle-snapshot-builds-time-window-and-skips-missing-coin-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub calls [])
          now-ms-fn (fn [] 10000)]
      (-> (market/request-candle-snapshot! post-info!
                                           now-ms-fn
                                           "BTC"
                                           {:interval :1m
                                            :bars 10
                                            :priority :low})
          (.then
           (fn []
             (let [[body opts] (first @calls)]
               (is (= {"type" "candleSnapshot"
                       "req" {"coin" "BTC"
                              "interval" "1m"
                              "startTime" -590000
                              "endTime" 10000}}
                      body))
               (is (= {:priority :low} opts)))
             (-> (market/request-candle-snapshot! post-info! now-ms-fn nil {})
                 (.then (fn [result]
                          (is (nil? result))
                          (is (= 1 (count @calls)))
                          (done)))
                 (.catch (async-support/unexpected-error done)))))
          (.catch (async-support/unexpected-error done))))))

(deftest request-candle-snapshot-uses-default-interval-bars-and-priority-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub calls [])
          now-ms-fn (fn [] 1000)]
      (-> (market/request-candle-snapshot! post-info! now-ms-fn "ETH" nil)
          (.then
           (fn []
             (let [[body opts] (first @calls)]
               (is (= {"type" "candleSnapshot"
                       "req" {"coin" "ETH"
                              "interval" "1d"
                              "startTime" (- 1000 (* 330 86400000))
                              "endTime" 1000}}
                      body))
               (is (= {:priority :high}
                      opts))
               (done))))
          (.catch (async-support/unexpected-error done))))))

(deftest request-asset-contexts-normalizes-response-shape-test
  (async done
    (let [post-info! (api-stubs/post-info-stub [{:universe [{:name "BTC" :marginTableId 1}
                                                             {:name "ETH" :marginTableId 1}]
                                                 :marginTables [[1 {:max-leverage 5}]]}
                                                [{:dayNtlVlm "10" :openInterest "20"}
                                                 {:dayNtlVlm "0" :openInterest "20"}]])]
      (-> (market/request-asset-contexts! post-info! {})
          (.then (fn [contexts]
                   (is (= #{:BTC} (set (keys contexts))))
                   (is (= 0 (get-in contexts [:BTC :idx])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-asset-contexts-uses-default-priority-when-opts-missing-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub calls [{:universe []
                                                       :marginTables []}
                                                      []])]
      (-> (market/request-asset-contexts! post-info! nil)
          (.then (fn [_]
                   (let [[body opts] (first @calls)]
                     (is (= {"type" "metaAndAssetCtxs"} body))
                     (is (= {:priority :high
                             :dedupe-key :asset-contexts
                             :cache-ttl-ms 4000}
                            opts)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-spot-meta-and-public-webdata2-use-default-priority-test
  (let [calls (atom [])
        post-info! (api-stubs/post-info-stub calls {})]
    (market/request-spot-meta! post-info! nil)
    (market/request-public-webdata2! post-info! {})
    (is (= [{"type" "spotMeta"}
            {"type" "webData2"
             "user" "0x0000000000000000000000000000000000000000"}]
           (mapv first @calls)))
    (is (= [{:priority :high
             :dedupe-key :spot-meta
             :cache-ttl-ms 60000}
            {:priority :high
             :dedupe-key :public-webdata2
             :cache-ttl-ms 30000}]
           (mapv second @calls)))))

(deftest request-predicted-fundings-uses-high-priority-and-dedupe-key-test
  (let [calls (atom [])
        post-info! (api-stubs/post-info-stub calls {})]
    (market/request-predicted-fundings! post-info! nil)
    (let [[body opts] (first @calls)]
      (is (= {"type" "predictedFundings"} body))
      (is (= {:priority :high
              :dedupe-key :predicted-fundings
              :cache-ttl-ms 5000}
             opts)))))

(deftest request-market-funding-history-builds-request-and-normalizes-rows-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub
                      calls
                      {:data {:fundingHistory [{:coin "BTC"
                                                :fundingRate "-0.0001"
                                                :premium "-0.0003"
                                                :time "1700000000000"}
                                               {:coin "BTC"
                                                :fundingRate "bad"
                                                :premium "0.01"
                                                :time 1700003600000}]}})]
      (-> (market/request-market-funding-history! post-info!
                                                  "BTC"
                                                  {:start-time-ms 1699990000000
                                                   :end-time-ms 1700003600000
                                                   :priority :low})
          (.then (fn [rows]
                   (is (= [{:coin "BTC"
                            :time-ms 1700000000000
                            :time 1700000000000
                            :funding-rate-raw -0.0001
                            :fundingRate -0.0001
                            :premium -0.0003}]
                          rows))
                   (let [[body opts] (first @calls)]
                     (is (= {"type" "fundingHistory"
                             "coin" "BTC"
                             "startTime" 1699990000000
                             "endTime" 1700003600000}
                            body))
                     (is (= :low (:priority opts)))
                     (is (= [:market-funding-history "BTC" 1699990000000 1700003600000]
                            (:dedupe-key opts)))
                     (is (= 15000 (:cache-ttl-ms opts))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-market-funding-history-paginates-capped-pages-test
  (async done
    (let [calls (atom [])
          coin "BTC"
          start-time-ms 1700000000000
          build-row (fn [idx]
                      (funding-history-row coin
                                           (+ start-time-ms idx)
                                           (str (/ (+ idx 1) 1000000))
                                           "0.0001"))
          page-1 (mapv build-row (range 500))
          page-2 (mapv build-row (range 500 720))
          end-time-ms (+ start-time-ms 719)
          post-info! (fn [body opts]
                       (swap! calls conj [body opts])
                       (js/Promise.resolve
                        {:data {:fundingHistory (case (count @calls)
                                                  1 page-1
                                                  2 page-2
                                                  [])}}))]
      (-> (market/request-market-funding-history! post-info!
                                                  coin
                                                  {:start-time-ms start-time-ms
                                                   :end-time-ms end-time-ms
                                                   :priority :low
                                                   :dedupe-key :explicit-flight
                                                   :cache-key :explicit-cache})
          (.then (fn [rows]
                   (is (= 720 (count rows)))
                   (is (= start-time-ms
                          (get-in rows [0 :time-ms])))
                   (is (= end-time-ms
                          (get-in rows [719 :time-ms])))
                   (is (= 2 (count @calls)))
                   (let [[[body-1 opts-1] [body-2 opts-2]] @calls
                         next-start-ms (inc (+ start-time-ms 499))]
                     (is (= {"type" "fundingHistory"
                             "coin" coin
                             "startTime" start-time-ms
                             "endTime" end-time-ms}
                            body-1))
                     (is (= {"type" "fundingHistory"
                             "coin" coin
                             "startTime" next-start-ms
                             "endTime" end-time-ms}
                            body-2))
                     (is (= {:priority :low
                             :dedupe-key [:market-funding-history-page
                                          :explicit-flight
                                          start-time-ms
                                          end-time-ms]
                             :cache-key [:market-funding-history-page-cache
                                         :explicit-cache
                                         start-time-ms
                                         end-time-ms]
                             :cache-ttl-ms 15000}
                            opts-1))
                     (is (= {:priority :low
                             :dedupe-key [:market-funding-history-page
                                          :explicit-flight
                                          next-start-ms
                                          end-time-ms]
                             :cache-key [:market-funding-history-page-cache
                                         :explicit-cache
                                         next-start-ms
                                         end-time-ms]
                             :cache-ttl-ms 15000}
                            opts-2)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-market-funding-history-stops-when-capped-page-does-not-advance-test
  (async done
    (let [calls (atom [])
          coin "BTC"
          start-time-ms 1700000000000
          build-row (fn [idx]
                      (funding-history-row coin
                                           (+ start-time-ms idx)
                                           "0.0001"
                                           "0.0"))
          page-1 (mapv build-row (range 500))
          repeated-row (build-row 499)
          page-2 (vec (repeat 500 repeated-row))
          post-info! (fn [body opts]
                       (swap! calls conj [body opts])
                       (js/Promise.resolve
                        {:data {:fundingHistory (case (count @calls)
                                                  1 page-1
                                                  2 page-2
                                                  [])}}))]
      (-> (market/request-market-funding-history! post-info!
                                                  coin
                                                  {:start-time-ms start-time-ms
                                                   :end-time-ms (+ start-time-ms 999)})
          (.then (fn [rows]
                   (is (= 500 (count rows)))
                   (is (= 2 (count @calls)))
                   (is (= (+ start-time-ms 500)
                          (get-in (second @calls) [0 "startTime"])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-market-funding-history-uses-raw-page-size-for-pagination-test
  (async done
    (let [calls (atom [])
          coin "BTC"
          start-time-ms 1700000000000
          build-row (fn [idx]
                      (funding-history-row coin
                                           (+ start-time-ms idx)
                                           "0.0001"
                                           "0.0"))
          invalid-row (funding-history-row coin
                                           (+ start-time-ms 123)
                                           "bad"
                                           "0.0")
          page-1 (vec (concat (map build-row (range 123))
                              [invalid-row]
                              (map build-row (range 124 500))))
          page-2 [(build-row 500)]
          post-info! (fn [body opts]
                       (swap! calls conj [body opts])
                       (js/Promise.resolve
                        {:data {:fundingHistory (case (count @calls)
                                                  1 page-1
                                                  2 page-2
                                                  [])}}))]
      (-> (market/request-market-funding-history! post-info!
                                                  coin
                                                  {:start-time-ms start-time-ms
                                                   :end-time-ms (+ start-time-ms 500)})
          (.then (fn [rows]
                   (is (= 500 (count rows)))
                   (is (= (+ start-time-ms 500)
                          (get-in rows [499 :time-ms])))
                   (is (= 2 (count @calls)))
                   (is (= (+ start-time-ms 500)
                          (get-in (second @calls) [0 "startTime"])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest build-market-state-bootstrap-uses-default-dex-and-skips-active-market-resolution-test
  (let [perp-calls (atom [])
        now-ms-fn (fn [] 4242)]
    (with-redefs [markets/build-perp-markets (fn [meta asset-ctxs token-by-index & {:keys [dex perp-dex-index]}]
                                               (swap! perp-calls conj {:meta meta
                                                                       :asset-ctxs asset-ctxs
                                                                       :token-by-index token-by-index
                                                                       :dex dex
                                                                       :perp-dex-index perp-dex-index})
                                               [{:key [:perp dex] :coin (or dex "DEFAULT")}])
                  markets/build-spot-markets (fn [_spot-meta _spot-asset-ctxs]
                                               [{:key [:spot "HYPE"] :coin "HYPE"}])
                  markets/resolve-market-by-coin (fn [& _]
                                                   (throw (js/Error. "unexpected active-market lookup")))]
      (let [result (market/build-market-state now-ms-fn
                                              nil
                                              :bootstrap
                                              ["vault"]
                                              {:tokens [{:index 0 :name "HYPE"}]}
                                              {:spot true}
                                              [[{:meta "m0"} {:ctx "c0"}]])]
        (is (= 1 (count @perp-calls)))
        (is (= nil (:dex (first @perp-calls))))
        (is (= 0 (:perp-dex-index (first @perp-calls))))
        (is (= {0 "HYPE"} (:token-by-index (first @perp-calls))))
        (is (= nil (:active-market result)))
        (is (= 2 (count (:markets result))))
        (is (= 2 (count (:market-by-key result))))
        (is (= {[:perp nil] 0
                [:spot "HYPE"] 1}
               (:market-index-by-key result)))
        (is (= 4242 (:loaded-at-ms result)))))))

(deftest build-market-state-live-phase-prefixes-default-dex-and-resolves-active-market-test
  (let [perp-calls (atom [])
        resolve-calls (atom [])
        now-ms-fn (fn [] 999)]
    (with-redefs [markets/build-perp-markets (fn [_meta _asset-ctxs _token-by-index & {:keys [dex perp-dex-index]}]
                                               (swap! perp-calls conj {:dex dex
                                                                       :perp-dex-index perp-dex-index})
                                               [{:key [:perp dex] :coin (or dex "DEFAULT")}])
                  markets/build-spot-markets (fn [_spot-meta _spot-asset-ctxs]
                                               [{:key [:spot "SOL"] :coin "SOL"}])
                  markets/resolve-market-by-coin (fn [market-by-key active-asset]
                                                   (swap! resolve-calls conj [market-by-key active-asset])
                                                   (get market-by-key [:perp "vault"]))]
      (let [result (market/build-market-state now-ms-fn
                                              "BTC"
                                              :live
                                              ["vault" "partner"]
                                              {:tokens []}
                                              {}
                                              [[{:meta :m0} {:ctx :c0}]
                                               [{:meta :m1} {:ctx :c1}]
                                               [{:meta :m2} {:ctx :c2}]])]
        (is (= [nil "vault" "partner"] (mapv :dex @perp-calls)))
        (is (= [0 1 2] (mapv :perp-dex-index @perp-calls)))
        (is (= 1 (count @resolve-calls)))
        (is (= "BTC" (second (first @resolve-calls))))
        (is (= [:perp "vault"] (:key (:active-market result))))
        (is (= 4 (count (:markets result))))
        (is (= {[:perp nil] 0
                [:perp "vault"] 1
                [:perp "partner"] 2
                [:spot "SOL"] 3}
               (:market-index-by-key result)))
        (is (= 999 (:loaded-at-ms result)))))))
