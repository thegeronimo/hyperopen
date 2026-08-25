(ns hyperopen.portfolio.optimizer.infrastructure.history-api-v2-client-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.portfolio.optimizer.infrastructure.history-api-v2-client :as client]
            [hyperopen.test-support.async :as async-support]))

(defn- json-response
  [status payload]
  #js {:ok (<= 200 status 299)
       :status status
       :json (fn []
               (js/Promise.resolve (clj->js payload)))})

(deftest request-instruments-sends-request-id-and-keywordizes-response-test
  (async done
    (let [calls (atom [])
          fetch-fn (fn [url init]
                     (swap! calls conj [url (js->clj init)])
                     (js/Promise.resolve
                      (json-response
                       200
                       {:contract_version "optimizer-history-api-v2"
                        :request_id "rid-1"
                        :dataset_version "2026-05-11T00:00:00.000Z"
                        :status "ok"
                        :instruments [{:instrument_id "hl:perp:BTC"
                                       :aliases {:hyperopen_market_key "perp:BTC"}}]
                        :warnings []})))]
      (-> (client/request-instruments!
           {:fetch-fn fetch-fn
            :base-url "https://history.test/"
            :request-id (fn [] "rid-1")})
          (.then
           (fn [body]
             ;; Discovery carries the same deadline as the bundle request, so
             ;; its init now includes an AbortSignal. Compare the addressing
             ;; fields, then pin the signal separately.
             (is (= [["https://history.test/v1/optimizer/instruments"
                      {"method" "GET"
                       "headers" {"x-request-id" "rid-1"}}]]
                    (mapv (fn [[url init]] [url (dissoc init "signal")])
                          @calls)))
             (is (some? (get-in (vec @calls) [0 1 "signal"]))
                 "discovery must be bounded by a deadline")
             (is (= "optimizer-history-api-v2" (:contract-version body)))
             (is (= "hl:perp:BTC"
                    (get-in body [:instruments 0 :instrument-id])))
             (is (= "perp:BTC"
                    (get-in body [:instruments 0 :aliases :hyperopen-market-key])))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-history-bundle-posts-strict-backend-ids-test
  (async done
    (let [calls (atom [])
          fetch-fn (fn [url init]
                     (swap! calls conj [url (js->clj init)])
                     (js/Promise.resolve
                      (json-response
                       200
                       {:contract_version "optimizer-history-api-v2"
                        :request_id "rid-2"
                        :dataset_version "dv-1"
                        :status "ok"
                        :series_by_instrument {}
                        :warnings []})))]
      (-> (client/request-history-bundle!
           {:fetch-fn fetch-fn
            :base-url "https://history.test"
            :request-id (fn [] "rid-2")
            :proxy-policy :approved-proxy-allowed
            :include-aligned-returns? true}
           {:bars 90
            :interval :1d
            :universe [{:instrument-id "perp:BTC"
                        :market-type :perp
                        :optimizer-history/instrument-id "hl:perp:BTC"}]})
          (.then
           (fn [_body]
             (let [[url init] (first @calls)
                   body (js->clj (js/JSON.parse (get init "body")))]
               (is (= "https://history.test/v1/optimizer/history-bundle" url))
               (is (= "POST" (get init "method")))
               (is (= {"content-type" "application/json"
                       "x-request-id" "rid-2"}
                      (get init "headers")))
               (is (= {"lookback_days" 90
                       "interval" "1d"
                       "proxy_policy" "approved_proxy_allowed"
                       "include_aligned_returns" true
                       "instruments" [{"client_instrument_id" "perp:BTC"
                                       "instrument_id" "hl:perp:BTC"}]}
                      body)))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-history-bundle-posts-canonical-target-id-for-proxied-hip3-test
  ;; Regression guard for the "0 usable shared observations" bug: even for a HIP-3
  ;; market with an approved, default-allowed, stitched trading-calendar (tiingo)
  ;; proxy under approved-proxy-allowed policy, the request identity is the
  ;; canonical backend target id — NEVER the proxy id (external:tiingo:*). The
  ;; backend selects the proxy/stitched lineage itself from `proxy_policy`;
  ;; sending the proxy id made it a bare external identity with a tiny cache and
  ;; collapsed the shared calendar. (API_CONTRACT.md: instrument_id is the only
  ;; accepted request identity.)
  (async done
    (let [calls (atom [])
          fetch-fn (fn [url init]
                     (swap! calls conj [url (js->clj init)])
                     (js/Promise.resolve
                      (json-response
                       200
                       {:contract_version "optimizer-history-api-v2"
                        :request_id "rid-spy"
                        :dataset_version "dv-spy"
                        :status "ok"
                        :series_by_instrument {}
                        :warnings []})))]
      (-> (client/request-history-bundle!
           {:fetch-fn fetch-fn
            :base-url "https://history.test"
            :request-id (fn [] "rid-spy")
            :proxy-policy :approved-proxy-allowed
            :include-aligned-returns? true}
           {:bars 365
            :interval :1d
            :universe [{:instrument-id "perp:xyz:SP500"
                        :market-type :perp
                        :optimizer-history/instrument-id "hl:hip3:xyz:SP500"
                        :optimizer-history/proxy
                        {:mapping-kind :stitched-native-proxy
                         :proxy-instrument-id "external:tiingo:SPY"
                         :provider "tiingo"
                         :optimizer-proxy-policy "default_allowed"}}]})
          (.then
           (fn [_body]
             (let [[_url init] (first @calls)
                   body (js->clj (js/JSON.parse (get init "body")))]
               (is (= [{"client_instrument_id" "perp:xyz:SP500"
                        "instrument_id" "hl:hip3:xyz:SP500"}]
                      (get body "instruments"))))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-history-bundle-keeps-target-id-for-native-only-policy-test
  (async done
    (let [calls (atom [])
          fetch-fn (fn [url init]
                     (swap! calls conj [url (js->clj init)])
                     (js/Promise.resolve
                      (json-response
                       200
                       {:contract_version "optimizer-history-api-v2"
                        :request_id "rid-native"
                        :dataset_version "dv-native"
                        :status "ok"
                        :series_by_instrument {}
                        :warnings []})))]
      (-> (client/request-history-bundle!
           {:fetch-fn fetch-fn
            :base-url "https://history.test"
            :request-id (fn [] "rid-native")
            :proxy-policy :native-only
            :include-aligned-returns? true}
           {:bars 365
            :interval :1d
            :universe [{:instrument-id "perp:xyz:SP500"
                        :market-type :perp
                        :optimizer-history/instrument-id "hl:hip3:xyz:SP500"
                        :optimizer-history/proxy
                        {:mapping-kind :stitched-native-proxy
                         :proxy-instrument-id "external:tiingo:SPY"
                         :provider "tiingo"
                         :optimizer-proxy-policy "default_allowed"}}]})
          (.then
           (fn [_body]
             (let [[_url init] (first @calls)
                   body (js->clj (js/JSON.parse (get init "body")))]
               (is (= [{"client_instrument_id" "perp:xyz:SP500"
                        "instrument_id" "hl:hip3:xyz:SP500"}]
                      (get body "instruments"))))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-history-bundle-chunks-universes-over-backend-limit-test
  (async done
    (let [universe (mapv (fn [i]
                           {:instrument-id (str "perp:A" i)
                            :market-type :perp
                            :optimizer-history/instrument-id (str "hl:perp:A" i)})
                         (range 105))
          calls (atom [])
          chunk-1-payload {:contract_version "optimizer-history-api-v2"
                           :request_id "rid-chunk-1"
                           :dataset_version "dv-chunk"
                           :status "ok"
                           :common_calendar [1000 2000 3000]
                           :return_calendar [2000 3000]
                           :series_by_instrument {"perp:A0" {:points []}}
                           :warnings []}
          chunk-2-payload {:contract_version "optimizer-history-api-v2"
                           :request_id "rid-chunk-2"
                           :dataset_version "dv-chunk"
                           :status "ok"
                           :common_calendar [2000 3000 4000]
                           :return_calendar [3000 4000]
                           :series_by_instrument {"perp:A104" {:points []}}
                           :warnings [{:code "missing_candle_history"
                                       :instrument_id "perp:A104"}]}
          fetch-fn (fn [url init]
                     (swap! calls conj [url (js->clj init)])
                     (js/Promise.resolve
                      (json-response
                       200
                       (if (= 1 (count @calls))
                         chunk-1-payload
                         chunk-2-payload))))]
      (-> (client/request-history-bundle!
           {:fetch-fn fetch-fn
            :base-url "https://history.test"
            :request-id (fn [] "rid-chunk")
            :proxy-policy :approved-proxy-allowed
            :include-aligned-returns? true}
           {:bars 365
            :interval :1d
            :universe universe})
          (.then
           (fn [body]
             (let [bodies (mapv (fn [[_url init]]
                                  (js->clj (js/JSON.parse (get init "body"))))
                                @calls)
                   instrument-ids (fn [request-body]
                                    (mapv #(get % "client_instrument_id")
                                          (get request-body "instruments")))]
               (is (= 2 (count @calls)))
               (is (= 100 (count (instrument-ids (first bodies)))))
               (is (= "perp:A0" (first (instrument-ids (first bodies)))))
               (is (= "perp:A99" (last (instrument-ids (first bodies)))))
               (is (= ["perp:A100" "perp:A101" "perp:A102" "perp:A103" "perp:A104"]
                      (instrument-ids (second bodies))))
               (is (= #{"perp:A0" "perp:A104"}
                      (set (keys (:series-by-instrument body)))))
               (is (= [2000 3000] (:common-calendar body)))
               (is (= [3000] (:return-calendar body)))
               (is (= :ok (:status body)))
               (is (= 1 (count (:warnings body)))))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-history-bundle-reports-chunk-progress-test
  (async done
    (let [universe (mapv (fn [i]
                           {:instrument-id (str "perp:A" i)
                            :market-type :perp
                            :optimizer-history/instrument-id (str "hl:perp:A" i)})
                         (range 105))
          progress-events (atom [])
          chunk-payload {:contract_version "optimizer-history-api-v2"
                         :request_id "rid-chunk-progress"
                         :dataset_version "dv-chunk"
                         :status "ok"
                         :common_calendar [1000 2000]
                         :return_calendar [2000]
                         :series_by_instrument {}
                         :warnings []}
          fetch-fn (fn [_url _init]
                     (js/Promise.resolve (json-response 200 chunk-payload)))]
      (-> (client/request-history-bundle!
           {:fetch-fn fetch-fn
            :base-url "https://history.test"
            :request-id (fn [] "rid-chunk-progress")
            :proxy-policy :approved-proxy-allowed
            :include-aligned-returns? true
            :on-chunk-progress (fn [payload]
                                 (swap! progress-events conj payload))}
           {:bars 365
            :interval :1d
            :universe universe})
          (.then
           (fn [_body]
             (is (= 2 (count @progress-events)))
             (is (= [1 2] (mapv :completed @progress-events)))
             (is (= {:completed 2
                     :total 2
                     :loaded-count 105
                     :requested-count 105}
                    (last @progress-events)))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-history-bundle-rejects-http-400-without-retry-test
  (async done
    (let [calls (atom 0)
          fetch-fn (fn [_url _init]
                     (swap! calls inc)
                     (js/Promise.resolve
                      (json-response
                       400
                       {:contract_version "optimizer-history-api-v2"
                        :request_id "rid-400"
                        :error "invalid_request"
                        :message "bad request"})))]
      (-> (client/request-history-bundle!
           {:fetch-fn fetch-fn
            :base-url "https://history.test"
            :request-id (fn [] "rid-400")}
           {:universe [{:instrument-id "perp:BTC"
                        :optimizer-history/instrument-id "hl:perp:BTC"}]})
          (.then (fn [_]
                   (is false "HTTP 400 should reject")
                   (done)))
          (.catch (fn [err]
                    (is (= 1 @calls))
                    (is (= 400 (.-status err)))
                    (is (= "bad request" (.-message err)))
                    (is (= "rid-400" (.-requestId err)))
                    (done)))))))

(deftest request-history-bundle-error-status-wins-over-partial-across-chunks-test
  ;; A common-window-empty (status "error") chunk must not be masked by a
  ;; :partial sibling chunk, and its per-instrument series stay served (an error
  ;; bundle is usable data plus a loud warning, never an empty response).
  (async done
    (let [universe (mapv (fn [i]
                           {:instrument-id (str "perp:A" i)
                            :market-type :perp
                            :optimizer-history/instrument-id (str "hl:perp:A" i)})
                         (range 105))
          calls (atom [])
          partial-payload {:contract_version "optimizer-history-api-v2"
                           :request_id "rid-partial"
                           :dataset_version "dv"
                           :status "partial"
                           :common_calendar [1000 2000]
                           :return_calendar [2000]
                           :series_by_instrument
                           {"perp:A0" {:points [{:time_ms 1000 :close 1 :return nil}]}}
                           :warnings []}
          error-payload {:contract_version "optimizer-history-api-v2"
                         :request_id "rid-error"
                         :dataset_version "dv"
                         :status "error"
                         :common_calendar []
                         :return_calendar []
                         :series_by_instrument
                         {"perp:A104" {:points [{:time_ms 1000 :close 1 :return nil}]}}
                         :warnings [{:code "common_window_empty"
                                     :severity "error"
                                     :details {:instrument_ids ["hl:perp:A104"]}}]}
          fetch-fn (fn [url init]
                     (swap! calls conj [url (js->clj init)])
                     (js/Promise.resolve
                      (json-response
                       200
                       (if (= 1 (count @calls)) partial-payload error-payload))))]
      (-> (client/request-history-bundle!
           {:fetch-fn fetch-fn
            :base-url "https://history.test"
            :request-id (fn [] "rid")
            :proxy-policy :approved-proxy-allowed
            :include-aligned-returns? true}
           {:bars 365
            :interval :1d
            :universe universe})
          (.then
           (fn [body]
             (is (= :error (:status body))
                 "A single error chunk wins over a partial one.")
             (is (= #{"perp:A0" "perp:A104"}
                    (set (keys (:series-by-instrument body))))
                 "Per-instrument series stay served in an error bundle.")
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-history-bundle-rejects-when-the-request-exceeds-its-timeout-test
  ;; Without a deadline, history-load-state has no path out of :loading — it only
  ;; moves on the fetch promise settling — so a hung request leaves the optimizer's
  ;; Run summary spinning until the page is reloaded. Pre-change this test hangs
  ;; and the async runner times it out.
  (async done
    (let [aborted? (atom false)]
      (-> (client/request-history-bundle!
           {:base-url "https://history.test"
            :request-timeout-ms 10
            :fetch-fn (fn [_url init]
                        (when-let [signal (aget init "signal")]
                          (.addEventListener signal
                                             "abort"
                                             (fn [] (reset! aborted? true))))
                        ;; Never settles.
                        (js/Promise. (fn [_resolve _reject])))}
           {:universe [{:instrument-id "perp:BTC"
                        :optimizer-history/instrument-id "hl:perp:BTC"}]
            :bars 30
            :interval :1d})
          (.then (fn [_bundle]
                   (is false "expected the timed-out request to reject")
                   (done)))
          (.catch (fn [err]
                    (is (= :timeout (aget err "status")))
                    (is (re-find #"timed out" (.-message err)))
                    ;; The in-flight request is cancelled, not merely abandoned.
                    (is (true? @aborted?))
                    (done)))))))

(deftest request-history-bundle-without-a-timeout-does-not-abort-test
  ;; A non-positive timeout disables the deadline; the request must still work
  ;; and must not attach an abort signal that something else could trip.
  (async done
    (-> (client/request-history-bundle!
         {:base-url "https://history.test"
          :request-timeout-ms 0
          :fetch-fn (fn [_url init]
                      (is (nil? (aget init "signal")))
                      (js/Promise.resolve
                       (json-response 200
                                      {:contract_version "optimizer-history-api-v2"
                                       :status "ok"
                                       :series_by_instrument {}
                                       :warnings []})))}
         {:universe [{:instrument-id "perp:BTC"
                      :optimizer-history/instrument-id "hl:perp:BTC"}]
          :bars 30
          :interval :1d})
        (.then (fn [bundle]
                 (is (= :ok (:status bundle)))
                 (done)))
        (.catch (async-support/unexpected-error done)))))
