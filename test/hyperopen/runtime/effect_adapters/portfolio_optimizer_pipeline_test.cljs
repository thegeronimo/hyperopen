(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-pipeline-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer-pipeline :as pipeline]
            [hyperopen.test-support.async :as async-support]))

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
