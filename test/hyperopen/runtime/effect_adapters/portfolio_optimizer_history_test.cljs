(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-history-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer :as portfolio-optimizer-adapters]
            [hyperopen.test-support.async :as async-support]))

(deftest load-portfolio-optimizer-history-effect-persists-success-for-current-request-test
  (async done
    (let [calls (atom [])
          store (atom {:portfolio {:optimizer
                                    {:draft {:universe [{:instrument-id "perp:BTC"
                                                         :market-type :perp
                                                         :coin "BTC"}]}
                                     :runtime {:as-of-ms 3000
                                               :stale-after-ms 60000}}}})
          bundle {:candle-history-by-coin {"BTC" [{:time 1000 :close "100"}
                                                  {:time 2000 :close "110"}]}
                  :funding-history-by-coin {"BTC" [{:time-ms 1000
                                                    :funding-rate-raw 0.001}]}
                  :warnings [{:code :funding-partial}]
                  :request-plan {:candle-requests [{:coin "BTC"}]}}]
      (with-redefs [portfolio-optimizer-adapters/*request-history-bundle!*
                    (fn [deps request]
                      (swap! calls conj {:deps deps
                                         :request request})
                      (js/Promise.resolve bundle))
                    portfolio-optimizer-adapters/*now-ms* (fn [] 12345)]
        (let [promise (portfolio-optimizer-adapters/load-portfolio-optimizer-history-effect
                       nil
                       store
                       {:bars 90})]
          (is (= :loading
                 (get-in @store [:portfolio :optimizer :history-load-state :status])))
          (-> promise
              (.then (fn [result]
                       (is (= bundle result))
                       (is (= 1 (count @calls)))
                       (is (= [{:instrument-id "perp:BTC"
                                :market-type :perp
                                :coin "BTC"}]
                              (get-in @calls [0 :request :universe])))
                       (is (= 90 (get-in @calls [0 :request :bars])))
                       (is (fn? (get-in @calls [0 :deps :request-candle-snapshot!])))
                       (is (fn? (get-in @calls [0 :deps :request-market-funding-history!])))
                       (is (= {"BTC" [{:time 1000 :close "100"}
                                      {:time 2000 :close "110"}]}
                              (get-in @store
                                      [:portfolio :optimizer :history-data :candle-history-by-coin])))
                       (is (= {"BTC" [{:time-ms 1000
                                       :funding-rate-raw 0.001}]}
                              (get-in @store
                                      [:portfolio :optimizer :history-data :funding-history-by-coin])))
                       (is (= {:status :succeeded
                               :request-signature (get-in @store
                                                          [:portfolio
                                                           :optimizer
                                                           :history-load-state
                                                           :request-signature])
                               :started-at-ms 12345
                               :completed-at-ms 12345
                               :error nil
                               :warnings [{:code :funding-partial}]}
                              (get-in @store [:portfolio :optimizer :history-load-state])))
                       (done)))
              (.catch (async-support/unexpected-error done))))))))

(deftest load-portfolio-optimizer-history-effect-passes-api-v2-deps-through-test
  (async done
    (let [captured (atom nil)
          api-config {:enabled? true
                      :base-url "https://history.test"
                      :proxy-policy :native-only
                      :include-aligned-returns? true
                      :fallback-to-legacy? false}
          fetch-fn (fn [& _] nil)
          store (atom {:portfolio {:optimizer
                                    {:draft {:universe [{:instrument-id "perp:BTC"
                                                         :market-type :perp
                                                         :coin "BTC"
                                                         :optimizer-history/instrument-id "hl:perp:BTC"}]}
                                     :runtime {:as-of-ms 3000}}}})]
      (with-redefs [portfolio-optimizer-adapters/*optimizer-history-api-config*
                    api-config
                    portfolio-optimizer-adapters/*optimizer-history-api-fetch*
                    fetch-fn
                    portfolio-optimizer-adapters/*optimizer-history-api-request-id*
                    (fn [] "rid-runtime")
                    portfolio-optimizer-adapters/*request-history-bundle!*
                    (fn [deps _request]
                      (reset! captured deps)
                      (js/Promise.resolve
                       {:api-v2-history {:status :ok
                                         :series-by-instrument {}}
                        :warnings []}))
                    portfolio-optimizer-adapters/*now-ms* (fn [] 12345)]
        (-> (portfolio-optimizer-adapters/load-portfolio-optimizer-history-effect
             nil
             store
             {:bars 90})
            (.then
             (fn [_]
               (is (= api-config (:optimizer-history-api @captured)))
               (is (identical? fetch-fn (:fetch-fn @captured)))
               (is (= "rid-runtime" ((:request-id @captured))))
               (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest load-portfolio-optimizer-history-effect-stores-current-portfolio-history-separately-test
  (async done
    (let [calls (atom [])
          selected-bundle {:api-v2-history
                           {:status :ok
                            :series-by-instrument
                            {"perp:BTC" {:instrument-id "hl:perp:BTC"}}}
                           :warnings []}
          current-bundle {:api-v2-history
                          {:status :ok
                           :series-by-instrument
                           {"perp:HYPE" {:instrument-id "hl:perp:HYPE"}}}
                          :warnings []}
          store (atom {:portfolio {:optimizer
                                    {:draft {:universe [{:instrument-id "perp:BTC"
                                                         :market-type :perp
                                                         :coin "BTC"}]}
                                     :runtime {:as-of-ms 3000}}}})]
      (with-redefs [portfolio-optimizer-adapters/*request-history-bundle!*
                    (fn [_deps request]
                      (swap! calls conj request)
                      (js/Promise.resolve
                       (if (= ["perp:HYPE"]
                              (mapv :instrument-id (:universe request)))
                         current-bundle
                         selected-bundle)))
                    portfolio-optimizer-adapters/*now-ms* (fn [] 12345)]
        (-> (portfolio-optimizer-adapters/load-portfolio-optimizer-history-effect
             nil
             store
             {:current-portfolio-universe [{:instrument-id "perp:HYPE"
                                            :market-type :perp
                                            :coin "HYPE"}]})
            (.then
             (fn [result]
               (is (= selected-bundle
                      (dissoc result :current-portfolio-history-data)))
               (is (= (assoc current-bundle
                             :requested-instrument-ids ["perp:HYPE"])
                      (:current-portfolio-history-data result)))
               (is (= [["perp:BTC"] ["perp:HYPE"]]
                      (mapv (fn [request]
                              (mapv :instrument-id (:universe request)))
                            @calls)))
               (is (false? (get-in @calls [1 :allow-legacy-fallback?])))
               (is (= #{"perp:BTC"}
                      (set (keys (get-in @store
                                         [:portfolio
                                          :optimizer
                                          :history-data
                                          :api-v2-history
                                          :series-by-instrument])))))
               (is (= #{"perp:HYPE"}
                      (set (keys (get-in @store
                                         [:portfolio
                                          :optimizer
                                          :history-data
                                          :current-portfolio-history-data
                                          :api-v2-history
                                          :series-by-instrument])))))
               (done)))
            (.catch (async-support/unexpected-error done)))))))

(defn- discovery-response
  [payload]
  #js {:ok true
       :status 200
       :json (fn []
               (js/Promise.resolve (clj->js payload)))})

(deftest load-portfolio-optimizer-history-discovery-effect-populates-optimizer-state-test
  (async done
    (let [calls (atom [])
          store (atom {:portfolio {:optimizer {:history-discovery {:status :idle}}}})
          fetch-fn (fn [url init]
                     (swap! calls conj [url (js->clj init)])
                     (js/Promise.resolve
                      (discovery-response
                       {:contract_version "optimizer-history-api-v2"
                        :request_id "rid-discovery"
                        :dataset_version "dv-1"
                        :status "ok"
                        :instruments [{:instrument_id "hl:perp:BTC"
                                       :display_symbol "BTC"
                                       :instrument_kind "hl_perp"
                                       :aliases {:hyperopen_market_key "perp:BTC"}
                                       :history {:status "available"
                                                 :quality_status "passed"}}]
                        :warnings []})))]
      (with-redefs [portfolio-optimizer-adapters/*optimizer-history-api-config*
                    {:enabled? true
                     :base-url "https://history.test"}
                    portfolio-optimizer-adapters/*optimizer-history-api-fetch*
                    fetch-fn
                    portfolio-optimizer-adapters/*optimizer-history-api-request-id*
                    (fn [] "rid-discovery")
                    portfolio-optimizer-adapters/*now-ms* (fn [] 5000)]
        (-> (portfolio-optimizer-adapters/load-portfolio-optimizer-history-discovery-effect
             nil
             store)
            (.then
             (fn [discovery]
               ;; The configured :request-timeout-ms reaches the discovery
               ;; request, so its init now carries an AbortSignal.
               (is (= [["https://history.test/v1/optimizer/instruments"
                        {"method" "GET"
                         "headers" {"x-request-id" "rid-discovery"}}]]
                      (mapv (fn [[url init]] [url (dissoc init "signal")])
                            @calls)))
               (is (some? (get-in (vec @calls) [0 1 "signal"]))
                   "discovery must be bounded by a deadline")
               (is (= :ok (:status discovery)))
               (is (= "hl:perp:BTC"
                      (get-in @store
                              [:portfolio
                               :optimizer
                               :history-discovery
                               :backend-id-by-local-id
                               "perp:BTC"])))
               (is (= 5000
                      (get-in @store
                              [:portfolio
                               :optimizer
                               :history-discovery
                               :loaded-at-ms])))
               (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest load-portfolio-optimizer-history-selection-prefetch-drains-queue-and-merges-test
  (async done
    (let [btc-instrument {:instrument-id "perp:BTC"
                          :market-type :perp
                          :coin "BTC"}
          eth-instrument {:instrument-id "perp:ETH"
                          :market-type :perp
                          :coin "ETH"}
          calls (atom [])
          now-values (atom [1000 1100 1200 1300])
          now-ms (fn []
                   (let [value (or (first @now-values) 1400)]
                     (swap! now-values #(vec (rest %)))
                     value))
          bundle-by-coin {"BTC" {:candle-history-by-coin
                                 {"BTC" [{:time 1000 :close "100"}
                                         {:time 2000 :close "110"}]}
                                 :funding-history-by-coin
                                 {"BTC" [{:time-ms 1000
                                          :funding-rate-raw 0}]}
                                 :warnings []
                                 :request-plan {:candle-requests [{:coin "BTC"}]}}
                          "ETH" {:candle-history-by-coin
                                 {"ETH" [{:time 1000 :close "50"}
                                         {:time 2000 :close "55"}]}
                                 :funding-history-by-coin
                                 {"ETH" [{:time-ms 1000
                                          :funding-rate-raw 0}]}
                                 :warnings [{:code :funding-partial}]
                                 :request-plan {:candle-requests [{:coin "ETH"}]}}}
          queued-status {:status :queued
                         :started-at-ms nil
                         :completed-at-ms nil
                         :error nil
                         :warnings []}
          store (atom {:portfolio {:optimizer
                                    {:draft {:universe [btc-instrument eth-instrument]}
                                     :history-data {:candle-history-by-coin
                                                    {"SOL" [{:time 1000
                                                             :close "20"}]}
                                                    :funding-history-by-coin
                                                    {"SOL" [{:time-ms 1000
                                                             :funding-rate-raw 0}]}}
                                     :history-prefetch
                                     {:queue [btc-instrument eth-instrument]
                                      :active-instrument-id nil
                                      :by-instrument-id {"perp:BTC" queued-status
                                                         "perp:ETH" queued-status}}}}})]
      (with-redefs [portfolio-optimizer-adapters/*request-history-bundle!*
                    (fn [_deps request]
                      (swap! calls conj request)
                      (let [bundles (keep #(get bundle-by-coin (:coin %))
                                          (:universe request))]
                        (js/Promise.resolve
                         {:candle-history-by-coin (apply merge
                                                         (map :candle-history-by-coin
                                                              bundles))
                          :funding-history-by-coin (apply merge
                                                          (map :funding-history-by-coin
                                                               bundles))
                          :warnings (vec (mapcat :warnings bundles))
                          :request-plan
                          {:candle-requests
                           (vec (mapcat #(get-in % [:request-plan
                                                    :candle-requests])
                                        bundles))}})))
                    portfolio-optimizer-adapters/*now-ms* now-ms]
        (-> (portfolio-optimizer-adapters/load-portfolio-optimizer-history-effect
             nil
             store
             {:source :selection-prefetch
              :queue? true
              :merge? true})
            (.then (fn [_result]
                     (is (= [["perp:BTC" "perp:ETH"]]
                            (mapv (fn [request]
                                    (mapv :instrument-id (:universe request)))
                                  @calls)))
                     (is (= #{"SOL" "BTC" "ETH"}
                            (set (keys (get-in @store
                                               [:portfolio
                                                :optimizer
                                                :history-data
                                                :candle-history-by-coin])))))
                     (is (= #{"SOL" "BTC" "ETH"}
                            (set (keys (get-in @store
                                               [:portfolio
                                                :optimizer
                                                :history-data
                                                :funding-history-by-coin])))))
                     (is (= []
                            (get-in @store
                                    [:portfolio :optimizer :history-prefetch :queue])))
                     (is (nil? (get-in @store
                                       [:portfolio
                                        :optimizer
                                        :history-prefetch
                                        :active-instrument-id])))
                     (is (= :succeeded
                            (get-in @store
                                    [:portfolio
                                     :optimizer
                                     :history-prefetch
                                     :by-instrument-id
                                     "perp:BTC"
                                     :status])))
                     (is (= [{:code :funding-partial}]
                            (get-in @store
                                    [:portfolio
                                     :optimizer
                                     :history-prefetch
                                     :by-instrument-id
                                     "perp:ETH"
                                     :warnings])))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest load-portfolio-optimizer-history-selection-prefetch-settles-removed-active-test
  (async done
    (let [btc-instrument {:instrument-id "perp:BTC"
                          :market-type :perp
                          :coin "BTC"}
          queued-status {:status :queued
                         :started-at-ms nil
                         :completed-at-ms nil
                         :error nil
                         :warnings []}
          resolve-bundle! (atom nil)
          now-values (atom [1000 1100])
          now-ms (fn []
                   (let [value (or (first @now-values) 1200)]
                     (swap! now-values #(vec (rest %)))
                     value))
          store (atom {:portfolio {:optimizer
                                    {:draft {:universe [btc-instrument]}
                                     :history-prefetch
                                     {:queue [btc-instrument]
                                      :active-instrument-id nil
                                      :by-instrument-id {"perp:BTC" queued-status}}}}})]
      (with-redefs [portfolio-optimizer-adapters/*request-history-bundle!*
                    (fn [_deps _request]
                      (js/Promise.
                       (fn [resolve _reject]
                         (reset! resolve-bundle! resolve))))
                    portfolio-optimizer-adapters/*now-ms* now-ms]
        (let [promise (portfolio-optimizer-adapters/load-portfolio-optimizer-history-effect
                       nil
                       store
                       {:source :selection-prefetch
                        :queue? true
                        :merge? true})]
          (is (= :loading
                 (get-in @store [:portfolio :optimizer :history-load-state :status])))
          (swap! store assoc-in [:portfolio :optimizer :draft :universe] [])
          (@resolve-bundle! {:candle-history-by-coin
                             {"BTC" [{:time 1000 :close "100"}]}
                             :funding-history-by-coin
                             {"BTC" [{:time-ms 1000
                                      :funding-rate-raw 0}]}
                             :warnings []
                             :request-plan {:candle-requests [{:coin "BTC"}]}})
          (-> promise
              (.then (fn [_result]
                       (is (nil? (get-in @store
                                         [:portfolio
                                          :optimizer
                                          :history-data
                                          :candle-history-by-coin
                                          "BTC"])))
                       (is (= :succeeded
                              (get-in @store
                                      [:portfolio
                                       :optimizer
                                       :history-load-state
                                       :status])))
                       (is (= []
                              (get-in @store
                                      [:portfolio
                                       :optimizer
                                       :history-prefetch
                                       :queue])))
                       (is (= {}
                              (get-in @store
                                      [:portfolio
                                       :optimizer
                                       :history-prefetch
                                       :by-instrument-id])))
                       (done)))
              (.catch (async-support/unexpected-error done))))))))

(deftest load-portfolio-optimizer-history-effect-preserves-data-on-error-test
  (async done
    (let [store (atom {:portfolio {:optimizer
                                    {:draft {:universe [{:instrument-id "perp:BTC"
                                                         :market-type :perp
                                                         :coin "BTC"}]}
                                     :history-data {:candle-history-by-coin {"BTC" [:old]}}}}})]
      (with-redefs [portfolio-optimizer-adapters/*request-history-bundle!*
                    (fn [_deps _request]
                      (js/Promise.reject (js/Error. "history boom")))
                    portfolio-optimizer-adapters/*now-ms* (fn [] 222)]
        (let [promise (portfolio-optimizer-adapters/load-portfolio-optimizer-history-effect nil store)]
          (-> promise
              (.then (fn [_]
                       (is (= {"BTC" [:old]}
                              (get-in @store
                                      [:portfolio :optimizer :history-data :candle-history-by-coin])))
                       (is (= :failed
                              (get-in @store [:portfolio :optimizer :history-load-state :status])))
                       (is (= "history boom"
                              (get-in @store [:portfolio :optimizer :history-load-state :error :message])))
                       (done)))
              (.catch (async-support/unexpected-error done))))))))

(deftest load-portfolio-optimizer-history-effect-skips-current-portfolio-subset-fetch-test
  ;; When every holdings instrument is already in the selected fetch (the
  ;; default holdings-seeded flow, where the draft universe = holdings plus
  ;; reference proxies), the request builder reads the MAIN bundle — so the
  ;; adapter must not pay a second full backend round trip for a
  ;; current-portfolio bundle nothing will read (the ~6.8s double fetch in the
  ;; 2026-07-08 trace).
  (async done
    (let [calls (atom [])
          selected-bundle {:api-v2-history
                           {:status :ok
                            :series-by-instrument
                            {"perp:BTC" {:instrument-id "hl:perp:BTC"}
                             "perp:HYPE" {:instrument-id "hl:perp:HYPE"}}}
                           :warnings []}
          store (atom {:portfolio {:optimizer
                                    {:draft {:universe [{:instrument-id "perp:BTC"
                                                         :market-type :perp
                                                         :coin "BTC"}
                                                        {:instrument-id "perp:HYPE"
                                                         :market-type :perp
                                                         :coin "HYPE"}]}
                                     :runtime {:as-of-ms 3000}}}})]
      (with-redefs [portfolio-optimizer-adapters/*request-history-bundle!*
                    (fn [_deps request]
                      (swap! calls conj request)
                      (js/Promise.resolve selected-bundle))
                    portfolio-optimizer-adapters/*now-ms* (fn [] 12345)]
        (-> (portfolio-optimizer-adapters/load-portfolio-optimizer-history-effect
             nil
             store
             ;; Holdings are a strict subset of the selected universe.
             {:current-portfolio-universe [{:instrument-id "perp:HYPE"
                                            :market-type :perp
                                            :coin "HYPE"}]})
            (.then
             (fn [result]
               (is (= 1 (count @calls))
                   "One backend request covers holdings and universe alike.")
               (is (= ["perp:BTC" "perp:HYPE"]
                      (mapv :instrument-id (:universe (first @calls)))))
               (is (nil? (:current-portfolio-history-data result))
                   "No separate current-portfolio bundle is fetched or attached.")
               (is (nil? (get-in @store
                                 [:portfolio
                                  :optimizer
                                  :history-data
                                  :current-portfolio-history-data])))
               (done)))
            (.catch (async-support/unexpected-error done)))))))
