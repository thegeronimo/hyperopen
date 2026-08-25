(ns hyperopen.portfolio.optimizer.infrastructure.history-client-fallback-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.portfolio.optimizer.infrastructure.history-client :as history-client]
            [hyperopen.test-support.async :as async-support]))

(defn- json-response
  [status payload]
  #js {:ok (<= 200 status 299)
       :status status
       :json (fn []
               (js/Promise.resolve (clj->js payload)))})

(deftest request-history-bundle-falls-back-only-for-api-v2-gaps-test
  (async done
    (let [api-body (atom nil)
          progress-events (atom [])
          legacy-calls (atom [])
          deps {:optimizer-history-api {:enabled? true
                                        :base-url "https://history.test"
                                        :fallback-to-legacy? true
                                        :legacy-fallback-request-spacing-ms 0}
                :fetch-fn
                (fn [_url init]
                  (let [body (js->clj
                              (js/JSON.parse (aget init "body")))]
                    (reset! api-body body)
                    (js/Promise.resolve
                     (json-response
                      200
                      {:contract_version "optimizer-history-api-v2"
                       :request_id "rid-partial"
                       :dataset_version "dv-partial"
                       :status "partial"
                       :series_by_instrument
                       {"hl:perp:BTC" {:instrument_id "hl:perp:BTC"
                                       :lineage_kind "native"
                                       :series_kind "market_price"
                                       :points [{:time_ms 1000
                                                 :close 100
                                                 :return nil}
                                                {:time_ms 2000
                                                 :close 110
                                                 :return 0.1}]
                                       :funding {:status "available"
                                                 :annualized_carry 0.01}
                                       :warnings []}
                        "hl:perp:ETH" {:instrument_id "hl:perp:ETH"
                                       :lineage_kind "missing"
                                       :series_kind "market_price"
                                       :points []
                                       :funding {:status "missing"}
                                       :warnings [{:code "missing-candle-history"}]}}
                       :warnings []}))))
                :request-id (fn [] "rid-partial")
                :request-candle-snapshot!
                (fn [coin _opts]
                  (swap! legacy-calls conj [:candle coin])
                  (js/Promise.resolve [{:time 1000 :close "100"}
                                       {:time 2000 :close "120"}]))
                :request-market-funding-history!
                (fn [coin _opts]
                  (swap! legacy-calls conj [:funding coin])
                  (js/Promise.resolve [{:time-ms 1000
                                        :funding-rate-raw "0.001"}]))
                :on-progress #(swap! progress-events conj %)}
          request {:universe [{:instrument-id "perp:BTC"
                               :market-type :perp
                               :coin "BTC"
                               :optimizer-history/instrument-id "hl:perp:BTC"}
                              {:instrument-id "perp:ETH"
                               :market-type :perp
                               :coin "ETH"
                               :optimizer-history/instrument-id "hl:perp:ETH"}
                              {:instrument-id "perp:DOGE"
                               :market-type :perp
                               :coin "DOGE"}]
                   :bars 30
                   :interval :1d
                   :now-ms 2000}]
      (-> (history-client/request-history-bundle! deps request)
          (.then
           (fn [bundle]
             (is (= [{"client_instrument_id" "perp:BTC"
                      "instrument_id" "hl:perp:BTC"}
                     {"client_instrument_id" "perp:ETH"
                      "instrument_id" "hl:perp:ETH"}]
                    (get @api-body "instruments")))
             (is (= [[:candle "ETH"]
                     [:candle "DOGE"]
                     [:funding "ETH"]
                     [:funding "DOGE"]]
                    @legacy-calls))
             (is (= #{"ETH" "DOGE"}
                    (set (keys (:candle-history-by-coin bundle)))))
             (is (contains? (set (map :code (:warnings bundle)))
                            :optimizer-history-api-legacy-fallback))
             (let [backend-events (filterv #(= :backend-api (:source %))
                                           @progress-events)
                   info-events (filterv #(= :info-endpoint (:source %))
                                        @progress-events)
                   backend-succeeded (first (filter #(= :succeeded (:status %))
                                                    backend-events))
                   info-started (first (filter #(= :started (:status %))
                                               info-events))]
               (is (= [:started :loading :succeeded]
                      (mapv :status backend-events)))
               (is (= {:requested-count 2
                       :returned-count 2
                       :usable-count 1
                       :fallback-asset-count 2}
                      (select-keys backend-succeeded
                                   [:requested-count
                                    :returned-count
                                    :usable-count
                                    :fallback-asset-count])))
               (is (= {:asset-count 2
                       :completed 0
                       :total 4
                       :percent 0}
                      (select-keys info-started
                                   [:asset-count :completed :total :percent])))
               (is (= [1 2 3 4]
                      (mapv :completed (filter #(not= :started (:status %))
                                               info-events)))))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-history-bundle-does-not-targeted-fallback-hard-api-v2-rejections-test
  (async done
    (let [legacy-calls (atom [])
          deps {:optimizer-history-api {:enabled? true
                                        :base-url "https://history.test"
                                        :fallback-to-legacy? true
                                        :legacy-fallback-request-spacing-ms 0}
                :fetch-fn
                (fn [_url _init]
                  (js/Promise.resolve
                   (json-response
                    200
                    {:contract_version "optimizer-history-api-v2"
                     :request_id "rid-hard-warning"
                     :dataset_version "dv-hard-warning"
                     :status "partial"
                     :series_by_instrument
                     {"hl:perp:BTC" {:instrument_id "hl:perp:BTC"
                                     :lineage_kind "native"
                                     :series_kind "market_price"
                                     :points [{:time_ms 1000
                                               :close 100
                                               :return nil}
                                              {:time_ms 2000
                                               :close 110
                                               :return 0.1}]
                                     :funding {:status "available"
                                               :annualized_carry 0.01}
                                     :warnings []}
                      "hl:perp:BAD" {:instrument_id "hl:perp:BAD"
                                     :lineage_kind "rejected"
                                     :series_kind "market_price"
                                     :points []
                                     :funding {:status "rejected"}
                                     :warnings [{:code "validation-failed"
                                                 :instrument_id "hl:perp:BAD"}]}}
                     :warnings [{:code "validation-failed"
                                 :instrument_id "hl:perp:BAD"}]})))
                :request-id (fn [] "rid-hard-warning")
                :request-candle-snapshot!
                (fn [coin _opts]
                  (swap! legacy-calls conj [:candle coin])
                  (js/Promise.resolve [{:time 1000 :close "10"}
                                       {:time 2000 :close "12"}]))
                :request-market-funding-history!
                (fn [coin _opts]
                  (swap! legacy-calls conj [:funding coin])
                  (js/Promise.resolve [{:time-ms 1000
                                        :funding-rate-raw "0.001"}]))}
          request {:universe [{:instrument-id "perp:BTC"
                               :market-type :perp
                               :coin "BTC"
                               :optimizer-history/instrument-id "hl:perp:BTC"}
                              {:instrument-id "perp:BAD"
                               :market-type :perp
                               :coin "BAD"
                               :optimizer-history/instrument-id "hl:perp:BAD"}]
                   :bars 30
                   :interval :1d
                   :now-ms 2000}]
      (-> (history-client/request-history-bundle! deps request)
          (.then
           (fn [bundle]
             (is (= [] @legacy-calls))
             (is (= #{:validation-failed}
                    (set (map :code (:warnings bundle)))))
             (is (not (contains? (set (map :code (:warnings bundle)))
                                 :optimizer-history-api-legacy-fallback)))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-history-bundle-throttles-legacy-info-requests-test
  (async done
    (let [active (atom 0)
          max-active (atom 0)
          progress-events (atom [])
          request! (fn [kind id]
                     (swap! active inc)
                     (swap! max-active max @active)
                     (js/Promise.
                      (fn [resolve _reject]
                        (js/setTimeout
                         (fn []
                           (swap! active dec)
                           (resolve (case kind
                                      :funding [{:time-ms 1000
                                                 :funding-rate-raw "0.001"}]
                                      [{:time 1000 :close "100"}
                                       {:time 2000 :close "110"}])))
                         0))))
          deps {:optimizer-history-api {:enabled? false
                                        :legacy-fallback-request-spacing-ms 0}
                :request-candle-snapshot!
                (fn [coin _opts] (request! :candle coin))
                :request-market-funding-history!
                (fn [coin _opts] (request! :funding coin))
                :on-progress #(swap! progress-events conj %)}
          request {:universe [{:instrument-id "perp:BTC"
                               :market-type :perp
                               :coin "BTC"}
                              {:instrument-id "perp:ETH"
                               :market-type :perp
                               :coin "ETH"}]
                   :now-ms 2000}]
      (-> (history-client/request-history-bundle! deps request)
          (.then
           (fn [_bundle]
             (is (= 1 @max-active))
             (is (= #{:info-endpoint}
                    (set (map :source @progress-events))))
             (is (= {:status :started
                     :completed 0
                     :total 4}
                    (select-keys (first @progress-events)
                                 [:status :completed :total])))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-history-bundle-does-not-legacy-fallback-when-disabled-for-request-test
  (async done
    (let [legacy-called? (atom false)
          deps {:optimizer-history-api {:enabled? true
                                        :base-url "https://history.test"
                                        :fallback-to-legacy? true}
                :fetch-fn (fn [& _]
                            (is false "backend API should not be called without backend ids")
                            (js/Promise.resolve
                             (json-response
                              200
                              {:contract_version "optimizer-history-api-v2"
                               :status "ok"})))
                :request-candle-snapshot!
                (fn [& _]
                  (reset! legacy-called? true)
                  (js/Promise.resolve []))
                :request-market-funding-history!
                (fn [& _]
                  (reset! legacy-called? true)
                  (js/Promise.resolve []))}
          request {:universe [{:instrument-id "perp:DUST"
                               :market-type :perp
                               :coin "DUST"}]
                   :allow-legacy-fallback? false}]
      (-> (history-client/request-history-bundle! deps request)
          (.then
           (fn [_bundle]
             (is false "missing backend ids should reject when request fallback is disabled")
             (done)))
          (.catch
           (fn [err]
             (is (false? @legacy-called?))
             (is (= "Optimizer history API request has no backend instrument ids."
                    (.-message err)))
             (done)))))))

(defn- targeted-fallback-deps
  "Backend serves BTC natively. ETH is the gap-fill target, shaped by `eth-series`.
  The /info candle request fails the FIRST time it is asked for ETH and succeeds
  afterwards, so an escalation to the whole-universe legacy loader would visibly
  succeed and record a [:candle \"BTC\"] call."
  [legacy-calls eth-series]
  {:optimizer-history-api {:enabled? true
                           :base-url "https://history.test"
                           :fallback-to-legacy? true
                           :legacy-fallback-request-spacing-ms 0}
   :fetch-fn
   (fn [_url _init]
     (js/Promise.resolve
      (json-response
       200
       {:contract_version "optimizer-history-api-v2"
        :request_id "rid-targeted-fail"
        :status "partial"
        :series_by_instrument
        {"hl:perp:BTC" {:instrument_id "hl:perp:BTC"
                        :lineage_kind "native"
                        :series_kind "market_price"
                        :points [{:time_ms 1000 :close 100 :return nil}
                                 {:time_ms 2000 :close 110 :return 0.1}]
                        :funding {:status "available"
                                  :annualized_carry 0.01}
                        :warnings []}
         "hl:perp:ETH" eth-series}
        :warnings []})))
   :request-id (fn [] "rid-targeted-fail")
   :request-candle-snapshot!
   (fn [coin _opts]
     (swap! legacy-calls conj [:candle coin])
     (if (and (= "ETH" coin)
              (= 1 (count (filterv #(= [:candle "ETH"] %) @legacy-calls))))
       (js/Promise.reject (js/Error. "info endpoint blew up"))
       (js/Promise.resolve [{:time 1000 :close "100"}
                            {:time 2000 :close "110"}])))
   :request-market-funding-history!
   (fn [coin _opts]
     (swap! legacy-calls conj [:funding coin])
     (js/Promise.resolve [{:time-ms 1000 :funding-rate-raw "0.001"}]))})

(def ^:private targeted-fallback-request
  {:universe [{:instrument-id "perp:BTC"
               :market-type :perp
               :coin "BTC"
               :optimizer-history/instrument-id "hl:perp:BTC"}
              {:instrument-id "perp:ETH"
               :market-type :perp
               :coin "ETH"
               :optimizer-history/instrument-id "hl:perp:ETH"}]
   :bars 30
   :interval :1d
   :now-ms 2000})

(deftest request-history-bundle-lossless-targeted-fallback-failure-degrades-test
  ;; ETH's API series HAS usable points and only carries a recoverable
  ;; :missing-candle-history warning, so the gap-fill is a TOP-UP. Losing it
  ;; costs no history, so the failure degrades to a warning. Pre-2026-08-24 the
  ;; rejection reached the total-failure handler and refetched the WHOLE
  ;; universe through the serial legacy loader.
  (async done
    (let [legacy-calls (atom [])]
      (-> (history-client/request-history-bundle!
           (targeted-fallback-deps legacy-calls
                                   {:instrument_id "hl:perp:ETH"
                                    :lineage_kind "native"
                                    :series_kind "market_price"
                                    :points [{:time_ms 1000 :close 20 :return nil}
                                             {:time_ms 2000 :close 22 :return 0.1}]
                                    :funding {:status "missing"}
                                    :warnings [{:code "missing-candle-history"}]})
           targeted-fallback-request)
          (.then
           (fn [bundle]
             (is (contains? (set (keys (get-in bundle [:api-v2-history
                                                       :series-by-instrument])))
                            "perp:BTC"))
             ;; No escalation: BTC was never re-requested from /info.
             (is (= [[:candle "ETH"]] @legacy-calls))
             (is (contains? (set (map :code (:warnings bundle)))
                            :optimizer-history-targeted-fallback-failed))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-history-bundle-lossy-targeted-fallback-failure-fails-the-load-test
  ;; ETH's API series has NO points, so the gap-fill was its ONLY source.
  ;; Resolving here would hand apply-history-success a bundle carrying no
  ;; :candle-history-by-coin at all, and that assoc-in REPLACES history-data
  ;; wholesale — destroying candles an earlier load had fetched and persisting
  ;; the degraded bundle. The load must fail so apply-history-error retains the
  ;; existing history, and it must still not escalate to the legacy loader.
  (async done
    (let [legacy-calls (atom [])]
      (-> (history-client/request-history-bundle!
           (targeted-fallback-deps legacy-calls
                                   {:instrument_id "hl:perp:ETH"
                                    :lineage_kind "missing"
                                    :series_kind "market_price"
                                    :points []
                                    :funding {:status "missing"}
                                    :warnings [{:code "missing-candle-history"}]})
           targeted-fallback-request)
          (.then (fn [_bundle]
                   (is false "a lossy gap-fill failure must fail the load")
                   (done)))
          (.catch (fn [err]
                    (is (= "info endpoint blew up" (.-message err)))
                    ;; Crucially: no whole-universe legacy refetch.
                    (is (not (contains? (set @legacy-calls) [:candle "BTC"])))
                    (is (= [[:candle "ETH"]] @legacy-calls))
                    (done)))))))
(deftest request-history-bundle-does-not-space-after-the-final-legacy-request-test
  ;; The spacing separates one /info request from the NEXT one. Sleeping after the
  ;; last entry only delayed the resolved bundle (200ms in production config) with
  ;; nothing waiting on it. Serialization itself is deliberate and unchanged — see
  ;; request-history-bundle-throttles-legacy-info-requests-test.
  (async done
    (let [sleeps (atom 0)
          real-set-timeout js/setTimeout
          entries (atom [])]
      (set! js/setTimeout
            (fn [f ms]
              (when (= 5 ms)
                (swap! sleeps inc))
              (real-set-timeout f 0)))
      ;; js/setTimeout is process-global here, so a synchronous throw out of the
      ;; call below would leave every later test running against the stub. The
      ;; promise paths restore it too; this try only covers the sync path.
      (try
        (-> (history-client/request-history-bundle!
           {:optimizer-history-api {:enabled? false
                                    :legacy-fallback-request-spacing-ms 5}
            :request-candle-snapshot!
            (fn [coin _opts]
              (swap! entries conj [:candle coin])
              (js/Promise.resolve [{:time 1000 :close "100"}
                                   {:time 2000 :close "110"}]))
            :request-market-funding-history!
            (fn [coin _opts]
              (swap! entries conj [:funding coin])
              (js/Promise.resolve [{:time-ms 1000 :funding-rate-raw "0.001"}]))}
           {:universe [{:instrument-id "perp:BTC" :market-type :perp :coin "BTC"}
                       {:instrument-id "perp:ETH" :market-type :perp :coin "ETH"}]
            :now-ms 2000})
          (.then (fn [_bundle]
                   (set! js/setTimeout real-set-timeout)
                   ;; 4 entries (candles BTC/ETH, funding BTC/ETH), 3 gaps.
                   (is (= 4 (count @entries)))
                   (is (= 3 @sleeps))
                   (done)))
          (.catch (fn [err]
                    (set! js/setTimeout real-set-timeout)
                    ((async-support/unexpected-error done) err))))
        (catch :default e
          (set! js/setTimeout real-set-timeout)
          ((async-support/unexpected-error done) e))))))

(deftest request-history-bundle-timeout-does-not-escalate-to-legacy-fallback-test
  ;; The deadline exists to give the loading status a terminal state. The
  ;; total-failure handler's normal reading of a rejection is "the API is
  ;; unavailable, serve everything from /info" — but that loader is the slowest
  ;; path in the system and has no deadline of its own, so honouring it here
  ;; would replace a bounded wait with an unbounded one. A timeout must reject.
  (async done
    (let [legacy-calls (atom [])]
      (-> (history-client/request-history-bundle!
           {:optimizer-history-api {:enabled? true
                                    :base-url "https://history.test"
                                    ;; Production posture: legacy fallback ON.
                                    :fallback-to-legacy? true
                                    :legacy-fallback-request-spacing-ms 0
                                    :request-timeout-ms 10}
            :fetch-fn (fn [_url _init]
                        (js/Promise. (fn [_resolve _reject])))
            :request-candle-snapshot!
            (fn [coin _opts]
              (swap! legacy-calls conj [:candle coin])
              (js/Promise.resolve [{:time 1000 :close "100"}]))
            :request-market-funding-history!
            (fn [coin _opts]
              (swap! legacy-calls conj [:funding coin])
              (js/Promise.resolve [{:time-ms 1000 :funding-rate-raw "0.001"}]))}
           {:universe [{:instrument-id "perp:BTC"
                        :market-type :perp
                        :coin "BTC"
                        :optimizer-history/instrument-id "hl:perp:BTC"}]
            :bars 30
            :interval :1d
            :now-ms 2000})
          (.then (fn [_bundle]
                   (is false "a timed-out load must reject, not fall back")
                   (done)))
          (.catch (fn [err]
                    (is (= :timeout (aget err "status")))
                    ;; The whole-universe legacy loader was never started.
                    (is (= [] @legacy-calls))
                    (done)))))))
