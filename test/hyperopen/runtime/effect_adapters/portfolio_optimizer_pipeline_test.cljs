(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-pipeline-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer-pipeline :as pipeline]
            [hyperopen.test-support.async :as async-support]))

(def day-ms
  (* 24 60 60 1000))

(defn- day-start-ms
  [day]
  (.getTime (js/Date. (str day "T00:00:00.000Z"))))

(defn- summary-from-points
  [points]
  {:accountValueHistory (mapv (fn [[time-ms account-value _pnl-value]]
                                [time-ms account-value])
                              points)
   :pnlHistory (mapv (fn [[time-ms _account-value pnl-value]]
                       [time-ms pnl-value])
                     points)})

(deftest run-portfolio-optimizer-pipeline-loads-history-before-worker-run-test
  (async done
    (let [calls (atom [])
          now-values (atom [1000 1100 1200])
          now-ms (fn []
                   (let [value (or (first @now-values) 1300)]
                     (swap! now-values #(vec (rest %)))
                     value))
          store (atom {:portfolio {:optimizer
                                    {:draft {:id "draft-pipeline"
                                             :universe [{:instrument-id "perp:BTC"
                                                         :market-type :perp
                                                         :coin "BTC"}]
                                             :objective {:kind :minimum-variance}
                                             :return-model {:kind :historical-mean}
                                             :risk-model {:kind :diagonal-shrink}
                                             :constraints {:long-only? true
                                                           :max-asset-weight 1.0}}
                                     :history-data {:candle-history-by-coin {}
                                                    :funding-history-by-coin {}}
                                     :runtime {:as-of-ms 3000
                                               :stale-after-ms 60000}}}
                       :webdata2 {:clearinghouseState
                                  {:marginSummary {:accountValue "1000"}
                                   :assetPositions []}}})
          bundle {:candle-history-by-coin {"BTC" [{:time 1000 :close "100"}
                                                  {:time 2000 :close "110"}]}
                  :funding-history-by-coin {"BTC" [{:time-ms 1000
                                                    :funding-rate-raw 0}]}
                  :warnings []
                  :request-plan {:candle-requests [{:coin "BTC"}]}}
          env {:now-ms now-ms
               :next-run-id (constantly "pipeline-run-1")
               :load-history! (fn [store* opts]
                                ((:on-progress opts) {:completed 1
                                                      :total 1
                                                      :percent 100})
                                (swap! calls conj [:history])
                                (swap! store*
                                       assoc-in
                                       [:portfolio :optimizer :history-data]
                                       (assoc bundle :loaded-at-ms (now-ms)))
                                (swap! store*
                                       assoc-in
                                       [:portfolio :optimizer :history-load-state]
                                       {:status :succeeded
                                        :request-signature {:test true}
                                        :started-at-ms 1100
                                        :completed-at-ms 1200
                                        :error nil
                                        :warnings []})
                                (js/Promise.resolve bundle))
               :request-run! (fn [payload]
                               (swap! calls conj [:run
                                                  (:run-id payload)
                                                  (:request payload)])
                               (:run-id payload))}]
      (-> (pipeline/run-portfolio-optimizer-pipeline-effect env nil store)
          (.then (fn [run-id]
                   (is (= "pipeline-run-1" run-id))
                   (is (= [:history :run] (mapv first @calls)))
                   (is (= run-id (second (second @calls))))
                   (is (= ["perp:BTC"]
                          (mapv :instrument-id (get-in @calls [1 2 :universe]))))
                   (is (= :running
                          (get-in @store
                                  [:portfolio :optimizer :optimization-progress :status])))
                   (is (= 100
                          (get-in @store
                                  [:portfolio
                                   :optimizer
                                   :optimization-progress
                                   :steps
                                   0
                                   :percent])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest run-portfolio-optimizer-pipeline-uses-common-vault-window-fallback-after-history-load-test
  (async done
    (let [hlp-address "0xdfc24b077bc1425ad1dea75bcb6f8158e10df303"
          growi-address "0x1e37a337ed460039d1b15bd3bc489de789768d5e"
          systemic-address "0xd6e56265890b76413d1d527eb9b75e334c0c5b42"
          hlp-id (str "vault:" hlp-address)
          growi-id (str "vault:" growi-address)
          systemic-id (str "vault:" systemic-address)
          h0 (day-start-ms "2025-05-03")
          h1 (day-start-ms "2025-10-30")
          m0 (day-start-ms "2026-04-02")
          m1 (day-start-ms "2026-04-12")
          m2 (day-start-ms "2026-04-23")
          m3 (day-start-ms "2026-05-03")
          month-summary (summary-from-points [[m0 100 0]
                                              [m1 105 5]
                                              [m2 110 10]
                                              [m3 115 15]])
          sparse-derived-summary (summary-from-points [[h0 90 -10]
                                                       [h1 100 0]
                                                       [m3 115 15]])
          calls (atom [])
          store (atom {:portfolio {:optimizer
                                    {:draft {:id "draft-three-vaults"
                                             :universe [{:instrument-id hlp-id
                                                         :market-type :vault
                                                         :coin hlp-id
                                                         :vault-address hlp-address
                                                         :name "Hyperliquidity Provider (HLP)"}
                                                        {:instrument-id growi-id
                                                         :market-type :vault
                                                         :coin growi-id
                                                         :vault-address growi-address
                                                         :name "Growi HF"}
                                                        {:instrument-id systemic-id
                                                         :market-type :vault
                                                         :coin systemic-id
                                                         :vault-address systemic-address
                                                         :name "[ Systemic Strategies ] HyperGrowth"}]
                                             :objective {:kind :minimum-variance}
                                             :return-model {:kind :historical-mean}
                                             :risk-model {:kind :diagonal-shrink}
                                             :constraints {:long-only? true
                                                           :max-asset-weight 1.0}}
                                     :history-data {:vault-details-by-address {}}
                                     :runtime {:as-of-ms (+ m3 day-ms)
                                               :stale-after-ms (* 2 day-ms)}}}
                       :webdata2 {:clearinghouseState
                                  {:marginSummary {:accountValue "1000"}
                                   :assetPositions []}}})
          bundle {:vault-details-by-address
                  {hlp-address {:portfolio {:all-time sparse-derived-summary
                                             :month month-summary}}
                   growi-address {:portfolio {:all-time sparse-derived-summary
                                               :month month-summary}}
                   systemic-address {:portfolio {:all-time (summary-from-points [[m3 115 15]])
                                                  :month month-summary}}}
                  :warnings []
                  :request-plan {:vault-detail-requests [{:vault-address hlp-address}
                                                         {:vault-address growi-address}
                                                         {:vault-address systemic-address}]}}
          env {:now-ms (constantly 1000)
               :next-run-id (constantly "pipeline-run-three-vaults")
               :load-history! (fn [store* opts]
                                ((:on-progress opts) {:completed 3
                                                      :total 3
                                                      :percent 100})
                                (swap! calls conj [:history])
                                (swap! store*
                                       assoc-in
                                       [:portfolio :optimizer :history-data]
                                       bundle)
                                (swap! store*
                                       assoc-in
                                       [:portfolio :optimizer :history-load-state]
                                       {:status :succeeded
                                        :warnings []})
                                (js/Promise.resolve bundle))
               :request-run! (fn [payload]
                               (swap! calls conj [:run (:request payload)])
                               (:run-id payload))}]
      (-> (pipeline/run-portfolio-optimizer-pipeline-effect env nil store)
          (.then (fn [run-id]
                   (let [request (second (second @calls))]
                     (is (= "pipeline-run-three-vaults" run-id))
                     (is (= [:history :run] (mapv first @calls)))
                     (is (= [hlp-id growi-id systemic-id]
                            (mapv :instrument-id (:universe request))))
                     (is (= {:kind :common-vault-window
                             :window :month
                             :observations 4}
                            (get-in request [:history :alignment-source])))
                     (is (not-any? #(= :insufficient-common-history (:code %))
                                   (:warnings request)))
                     (is (= :running
                            (get-in @store
                                    [:portfolio :optimizer :optimization-progress :status]))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest run-portfolio-optimizer-pipeline-waits-for-active-selection-prefetch-test
  (async done
    (let [calls (atom [])
          btc-instrument {:instrument-id "perp:BTC"
                          :market-type :perp
                          :coin "BTC"}
          store (atom {:portfolio {:optimizer
                                    {:draft {:id "draft-prefetch"
                                             :universe [btc-instrument]
                                             :objective {:kind :minimum-variance}
                                             :return-model {:kind :historical-mean}
                                             :risk-model {:kind :diagonal-shrink}
                                             :constraints {:long-only? true
                                                           :max-asset-weight 1.0}}
                                     :history-data {:candle-history-by-coin {}
                                                    :funding-history-by-coin {}}
                                     :history-load-state
                                     {:status :loading
                                      :request-signature {:universe [btc-instrument]
                                                          :source :selection-prefetch}}
                                     :history-prefetch
                                     {:queue []
                                      :active-instrument-id "perp:BTC"
                                      :by-instrument-id
                                      {"perp:BTC" {:status :loading
                                                   :started-at-ms 900
                                                   :completed-at-ms nil
                                                   :error nil
                                                   :warnings []}}}
                                     :runtime {:as-of-ms 3000
                                               :stale-after-ms 60000}}}
                       :webdata2 {:clearinghouseState
                                  {:marginSummary {:accountValue "1000"}
                                   :assetPositions []}}})
          env {:now-ms (constantly 1000)
               :next-run-id (constantly "pipeline-run-prefetch")
               :history-idle-poll-ms 1
               :history-idle-timeout-ms 100
               :load-history! (fn [_store _opts]
                                (swap! calls conj [:history])
                                (js/Promise.resolve nil))
               :request-run! (fn [payload]
                               (swap! calls conj [:run
                                                  (:run-id payload)
                                                  (:request payload)])
                               (:run-id payload))}]
      (js/setTimeout
       (fn []
         (swap! store
                (fn [state]
                  (-> state
                      (assoc-in [:portfolio :optimizer :history-data]
                                {:candle-history-by-coin
                                 {"BTC" [{:time 1000 :close "100"}
                                         {:time 2000 :close "110"}]}
                                 :funding-history-by-coin
                                 {"BTC" [{:time-ms 1000
                                          :funding-rate-raw 0}]}
                                 :loaded-at-ms 1005})
                      (assoc-in [:portfolio :optimizer :history-load-state]
                                {:status :succeeded
                                 :request-signature {:universe [btc-instrument]
                                                     :source :selection-prefetch}
                                 :warnings []})
                      (assoc-in [:portfolio :optimizer :history-prefetch :active-instrument-id]
                                nil)))))
       5)
      (-> (pipeline/run-portfolio-optimizer-pipeline-effect env nil store)
          (.then (fn [run-id]
                   (is (= "pipeline-run-prefetch" run-id))
                   (is (= [:run] (mapv first @calls)))
                   (is (= ["perp:BTC"]
                          (mapv :instrument-id (get-in @calls [0 2 :universe]))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest run-portfolio-optimizer-pipeline-fails-without-universe-test
  (async done
    (let [store (atom {:portfolio {:optimizer {:draft {:id "draft-empty"
                                                       :universe []}}}})
          env {:now-ms (constantly 1000)
               :next-run-id (constantly "pipeline-run-empty")
               :load-history! (fn [_store _opts]
                                (is false "history should not load without a universe")
                                (js/Promise.resolve nil))
               :request-run! (fn [_payload]
                               (is false "worker should not run without a universe"))}]
      (-> (pipeline/run-portfolio-optimizer-pipeline-effect env nil store)
          (.then (fn [_]
                   (is (= :failed
                          (get-in @store
                                  [:portfolio :optimizer :optimization-progress :status])))
                   (is (= :missing-universe
                          (get-in @store
                                  [:portfolio
                                   :optimizer
                                   :optimization-progress
                                   :error
                                   :code])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest run-portfolio-optimizer-pipeline-failure-names-incomplete-vault-history-test
  (async done
    (let [loaded-vault-address "0x1111111111111111111111111111111111111111"
          missing-vault-address "0x2222222222222222222222222222222222222222"
          loaded-vault-id (str "vault:" loaded-vault-address)
          missing-vault-id (str "vault:" missing-vault-address)
          calls (atom [])
          store (atom {:portfolio {:optimizer
                                    {:draft {:id "draft-vaults"
                                             :universe [{:instrument-id loaded-vault-id
                                                         :market-type :vault
                                                         :coin loaded-vault-id
                                                         :vault-address loaded-vault-address
                                                         :name "Loaded Vault"}
                                                        {:instrument-id missing-vault-id
                                                         :market-type :vault
                                                         :coin missing-vault-id
                                                         :vault-address missing-vault-address
                                                         :name "pmaIt"}]
                                             :objective {:kind :minimum-variance}
                                             :return-model {:kind :historical-mean}
                                             :risk-model {:kind :diagonal-shrink}
                                             :constraints {:long-only? true}}
                                     :history-data {:vault-details-by-address {}}
                                     :runtime {:as-of-ms 4000
                                               :stale-after-ms 60000}}}})
          bundle {:vault-details-by-address
                  {loaded-vault-address
                   {:portfolio
                    {:all-time
                     {:accountValueHistory [[1000 100]
                                            [2000 110]
                                            [3000 121]]
                      :pnlHistory [[1000 0]
                                   [2000 10]
                                   [3000 21]]}}}}
                  :warnings []}
          env {:now-ms (constantly 1000)
               :next-run-id (constantly "pipeline-run-vaults")
               :load-history! (fn [store* opts]
                                ((:on-progress opts) {:completed 2
                                                      :total 2
                                                      :percent 100})
                                (swap! calls conj [:history])
                                (swap! store*
                                       assoc-in
                                       [:portfolio :optimizer :history-data]
                                       bundle)
                                (swap! store*
                                       assoc-in
                                       [:portfolio :optimizer :history-load-state]
                                       {:status :succeeded
                                        :warnings []})
                                (js/Promise.resolve bundle))
               :request-run! (fn [_payload]
                               (is false "worker should not run when vault history remains incomplete"))}]
      (-> (pipeline/run-portfolio-optimizer-pipeline-effect env nil store)
          (.then (fn [run-id]
                   (is (nil? run-id))
                   (is (= [[:history]] @calls))
                   (is (= :failed
                          (get-in @store
                                  [:portfolio :optimizer :optimization-progress :status])))
                   (is (= :pipeline-failed
                          (get-in @store
                                  [:portfolio
                                   :optimizer
                                   :optimization-progress
                                   :error
                                   :code])))
                   (is (= "History is incomplete: pmaIt: vault details returned no usable return history."
                          (get-in @store
                                  [:portfolio
                                   :optimizer
                                   :optimization-progress
                                   :error
                                   :message])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))
