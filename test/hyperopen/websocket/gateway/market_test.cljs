(ns hyperopen.websocket.gateway.market-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.endpoints.market :as market-endpoints]
            [hyperopen.api.fetch-compat :as fetch-compat]
            [hyperopen.api.gateway.market :as market-gateway]))

(deftest request-candle-snapshot-builds-interval-window-test
  (async done
    (let [calls (atom [])
          deps {:post-info! (fn [body opts]
                              (swap! calls conj {:body body :opts opts})
                              (js/Promise.resolve []))
                :now-ms-fn (fn [] 10000000)}]
      (-> (market-gateway/request-candle-snapshot!
           deps
           "BTC"
           {:interval :1m
            :bars 2
            :priority :low})
          (.then (fn [_]
                   (is (= {"type" "candleSnapshot"
                           "req" {"coin" "BTC"
                                  "interval" "1m"
                                  "startTime" 9880000
                                  "endTime" 10000000}}
                          (get-in @calls [0 :body])))
                   (is (= {:priority :low
                           :dedupe-key [:candle-snapshot "BTC" "1m" 2]
                           :cache-key [:candle-snapshot "BTC" "1m" 2]
                           :cache-ttl-ms 4000}
                          (get-in @calls [0 :opts])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-candle-snapshot-skips-nil-asset-test
  (async done
    (let [calls (atom 0)
          deps {:post-info! (fn [_body _opts]
                              (swap! calls inc)
                              (js/Promise.resolve []))
                :now-ms-fn (fn [] 10000000)}]
      (-> (market-gateway/request-candle-snapshot! deps nil {:interval :1m :bars 2})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-perp-dexs-rejects-non-canonical-payload-test
  (async done
    (with-redefs [market-endpoints/request-perp-dexs! (fn [_post-info! _opts]
                                                        (js/Promise.resolve {:dex-names ["vault"]
                                                                             :fee-config-by-name {"scaled" {:deployer-fee-scale 0.5}}}))]
      (-> (market-gateway/request-perp-dexs! {:post-info! (fn [& _] nil)} {:priority :high})
          (.then (fn [_]
                   (is false "Expected request-perp-dexs! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"perp DEX metadata contract validation failed" (str err)))
                    (done)))))))

(deftest request-asset-selector-markets-delegates-to-loader-test
  (async done
    (let [deps {:active-asset nil
                :opts {:phase :bootstrap}
                :ensure-perp-dexs-data! (fn [_opts] (js/Promise.resolve []))
                :ensure-spot-meta-data! (fn [_opts] (js/Promise.resolve {:tokens [] :universe []}))
                :ensure-public-webdata2! (fn [_opts] (js/Promise.resolve {:spotAssetCtxs []}))
                :request-meta-and-asset-ctxs! (fn [_dex _opts] (js/Promise.resolve [nil nil]))
                :build-market-state (fn [_active-asset phase _dexs _spot-meta _spot-asset-ctxs _perp-results]
                                      {:phase phase
                                       :markets []})
                :log-fn (fn [& _] nil)}]
      (-> (market-gateway/request-asset-selector-markets! deps)
          (.then (fn [result]
                   (is (= :bootstrap (:phase result)))
                   (is (= {:phase :bootstrap :markets []}
                          (:market-state result)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-perp-dexs-rejects-invalid-payload-before-success-projection-test
  (async done
    (let [store (atom {})
          success-calls (atom 0)
          error-calls (atom 0)]
      (-> (market-gateway/fetch-perp-dexs!
           {:log-fn (fn [& _] nil)
            :request-perp-dexs! (fn [_opts]
                                  (js/Promise.resolve {:dex-names [""]
                                                       :fee-config-by-name {}}))
            :apply-perp-dexs-success (fn [state payload]
                                       (swap! success-calls inc)
                                       (assoc state :perp-dexs payload))
            :apply-perp-dexs-error (fn [state err]
                                     (swap! error-calls inc)
                                     (assoc state :perp-dexs-error (.-message err)))}
           store
           {:priority :high})
          (.then (fn [_]
                   (is false "Expected fetch-perp-dexs! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"perp DEX metadata contract validation failed" (str err)))
                    (is (= 0 @success-calls))
                    (is (= 1 @error-calls))
                    (done)))))))

(deftest ensure-perp-dexs-rejects-invalid-payload-before-success-projection-test
  (async done
    (let [store (atom {})
          success-calls (atom 0)
          error-calls (atom 0)]
      (-> (market-gateway/ensure-perp-dexs!
           {:ensure-perp-dexs-data! (fn [_store _opts]
                                      (js/Promise.resolve {:dex-names ["vault"]
                                                           :fee-config-by-name {"vault" {:deployer-fee-scale "0.2"}}}))
            :apply-perp-dexs-success (fn [state payload]
                                       (swap! success-calls inc)
                                       (assoc state :perp-dexs payload))
            :apply-perp-dexs-error (fn [state err]
                                     (swap! error-calls inc)
                                     (assoc state :perp-dexs-error (.-message err)))}
           store
           {:priority :low})
          (.then (fn [_]
                   (is false "Expected ensure-perp-dexs! to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"perp DEX metadata contract validation failed" (str err)))
                    (is (= 0 @success-calls))
                    (is (= 1 @error-calls))
                    (done)))))))

(deftest market-gateway-wrapper-delegation-coverage-test
  (let [called (atom [])
        post-info! (fn [& _] nil)]
    (with-redefs [market-endpoints/build-market-state (fn [& args]
                                                        (swap! called conj [:build args])
                                                        {:ok :build})
                  market-endpoints/request-asset-contexts! (fn [& args]
                                                             (swap! called conj [:asset-ctx args])
                                                             {:ok :asset-ctx})
                  market-endpoints/request-meta-and-asset-ctxs! (fn [& args]
                                                                  (swap! called conj [:meta-ctx args])
                                                                  {:ok :meta-ctx})
                  market-endpoints/request-perp-dexs! (fn [& args]
                                                        (swap! called conj [:perp-dexs args])
                                                        (js/Promise.resolve {:dex-names ["dex-a"]
                                                                             :fee-config-by-name {}}))
                  market-endpoints/request-spot-meta! (fn [& args]
                                                        (swap! called conj [:spot-meta args])
                                                        {:ok :spot-meta})
                  market-endpoints/request-public-webdata2! (fn [& args]
                                                              (swap! called conj [:webdata2 args])
                                                              {:ok :webdata2})
                  market-endpoints/request-predicted-fundings! (fn [& args]
                                                                 (swap! called conj [:predicted-fundings args])
                                                                 {:ok :predicted-fundings})
                  fetch-compat/fetch-asset-contexts! (fn [& args]
                                                      (swap! called conj [:fetch-asset-ctx args])
                                                      {:ok :fetch-asset-ctx})
                  fetch-compat/fetch-perp-dexs! (fn [& args]
                                                 (swap! called conj [:fetch-perp-dexs args])
                                                 {:ok :fetch-perp-dexs})
                  fetch-compat/fetch-candle-snapshot! (fn [& args]
                                                       (swap! called conj [:fetch-candle args])
                                                       {:ok :fetch-candle})
                  fetch-compat/fetch-spot-meta! (fn [& args]
                                                 (swap! called conj [:fetch-spot-meta args])
                                                 {:ok :fetch-spot-meta})
                  fetch-compat/ensure-perp-dexs! (fn [& args]
                                                  (swap! called conj [:ensure-perp args])
                                                  {:ok :ensure-perp})
                  fetch-compat/ensure-spot-meta! (fn [& args]
                                                  (swap! called conj [:ensure-spot args])
                                                  {:ok :ensure-spot})
                  fetch-compat/fetch-asset-selector-markets! (fn [& args]
                                                               (swap! called conj [:fetch-selector args])
                                                               {:ok :fetch-selector})]
      (is (= {:ok :build}
             (market-gateway/build-market-state (fn [] 1) "BTC" :bootstrap [] {} {} [])))
      (is (= {:ok :asset-ctx}
             (market-gateway/request-asset-contexts! {:post-info! post-info!} {:priority :low})))
      (is (= {:ok :fetch-asset-ctx}
             (market-gateway/fetch-asset-contexts! {:log-fn identity
                                                    :request-asset-contexts! identity
                                                    :apply-asset-contexts-success identity
                                                    :apply-asset-contexts-error identity}
                                                   nil
                                                   {:priority :low})))
      (is (= {:ok :meta-ctx}
             (market-gateway/request-meta-and-asset-ctxs! {:post-info! post-info!}
                                                          "dex-a"
                                                          {:priority :low})))
      (is (some? (market-gateway/request-perp-dexs! {:post-info! post-info!} {:priority :high})))
      (is (= {:ok :fetch-perp-dexs}
             (market-gateway/fetch-perp-dexs! {:log-fn identity
                                               :request-perp-dexs! identity
                                               :apply-perp-dexs-success identity
                                               :apply-perp-dexs-error identity}
                                              nil
                                              {:priority :low})))
      (is (= {:ok :fetch-candle}
             (market-gateway/fetch-candle-snapshot! {:log-fn identity
                                                     :request-candle-snapshot! identity
                                                     :apply-candle-snapshot-success identity
                                                     :apply-candle-snapshot-error identity}
                                                    nil
                                                    {:priority :low})))
      (is (= {:ok :spot-meta}
             (market-gateway/request-spot-meta! {:post-info! post-info!} {:priority :high})))
      (is (= {:ok :fetch-spot-meta}
             (market-gateway/fetch-spot-meta! {:log-fn identity
                                               :request-spot-meta! identity
                                               :begin-spot-meta-load identity
                                               :apply-spot-meta-success identity
                                               :apply-spot-meta-error identity}
                                              nil
                                              {:priority :low})))
      (is (= {:ok :spot-meta}
             (market-gateway/request-spot-meta-raw! {:request-spot-meta! (fn [opts]
                                                                            (swap! called conj [:spot-raw opts])
                                                                            {:ok :spot-meta})}
                                                    {:priority :low})))
      (is (= {:ok :spot-meta}
             (market-gateway/fetch-spot-meta-raw! {:request-spot-meta! (fn [opts]
                                                                          (swap! called conj [:spot-raw-fetch opts])
                                                                          {:ok :spot-meta})}
                                                  {:priority :low})))
      (is (= {:ok :webdata2}
             (market-gateway/request-public-webdata2! {:post-info! post-info!} {:priority :high})))
      (is (= {:ok :predicted-fundings}
             (market-gateway/request-predicted-fundings! {:post-info! post-info!} {:priority :high})))
      (is (= {:ok :webdata2}
             (market-gateway/fetch-public-webdata2! {:request-public-webdata2! (fn [opts]
                                                                                  (swap! called conj [:webdata-fetch opts])
                                                                                  {:ok :webdata2})}
                                                    {:priority :low})))
      (is (= {:ok :ensure-perp}
             (market-gateway/ensure-perp-dexs! {:ensure-perp-dexs-data! identity
                                                :apply-perp-dexs-success identity
                                                :apply-perp-dexs-error identity}
                                               nil
                                               {:priority :low})))
      (is (= {:ok :ensure-spot}
             (market-gateway/ensure-spot-meta! {:ensure-spot-meta-data! identity
                                                :apply-spot-meta-success identity
                                                :apply-spot-meta-error identity}
                                               nil
                                               {:priority :low})))
      (is (= {:ok :fetch-selector}
             (market-gateway/fetch-asset-selector-markets! {:log-fn identity
                                                            :request-asset-selector-markets! identity
                                                            :begin-asset-selector-load identity
                                                            :apply-spot-meta-success identity
                                                            :apply-asset-selector-success identity
                                                            :apply-asset-selector-error identity}
                                                           nil
                                                           {:priority :low})))
      (is (some #(= :build (first %)) @called))
      (is (some #(= :fetch-spot-meta (first %)) @called))
      (is (some #(= :predicted-fundings (first %)) @called))
      (is (some #(= :fetch-selector (first %)) @called)))))
