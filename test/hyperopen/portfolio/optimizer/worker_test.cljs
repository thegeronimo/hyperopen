(ns hyperopen.portfolio.optimizer.worker-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.portfolio.optimizer.worker :as worker]))

(defn- now-ms
  []
  (js/Date.now))

(defn- synthetic-return-series
  [instrument-idx observation-count]
  (mapv (fn [observation-idx]
          (+ 0.0001
             (* 0.00001 instrument-idx)
             (* 0.001 (js/Math.sin (+ observation-idx instrument-idx)))
             (* 0.00005 (mod (+ observation-idx (* 3 instrument-idx)) 7))))
        (range observation-count)))

(defn- synthetic-request
  [size]
  (let [instrument-ids (mapv #(str "perp:QA" %) (range size))
        equal-weight (/ 1 size)
        observations 90]
    {:scenario-id (str "perf-" size)
     :universe (mapv (fn [instrument-id idx]
                       {:instrument-id instrument-id
                        :market-type :perp
                        :coin (str "QA" idx)
                        :shortable? true})
                     instrument-ids
                     (range))
     :current-portfolio {:capital {:nav-usdc 100000}
                         :by-instrument (into {}
                                              (map (fn [instrument-id]
                                                     [instrument-id
                                                      {:weight equal-weight}]))
                                              instrument-ids)}
     :return-model {:kind :historical-mean}
     :risk-model {:kind :diagonal-shrink}
     :objective {:kind :minimum-variance}
     :constraints {:long-only? true
                   :max-asset-weight 1
                   :rebalance-tolerance 0.0001}
     :execution-assumptions {:fallback-slippage-bps 25
                             :prices-by-id (into {}
                                                 (map-indexed
                                                  (fn [idx instrument-id]
                                                    [instrument-id (+ 100 idx)]))
                                                 instrument-ids)
                             :fee-bps-by-id (into {}
                                               (map (fn [instrument-id]
                                                      [instrument-id 4]))
                                               instrument-ids)}
     :history {:return-series-by-instrument
               (into {}
                     (map-indexed
                      (fn [idx instrument-id]
                        [instrument-id
                         (synthetic-return-series idx observations)]))
                     instrument-ids)
               :funding-by-instrument
               (into {}
                     (map (fn [instrument-id]
                            [instrument-id {:annualized-carry 0
                                            :source :synthetic-fixture}]))
                     instrument-ids)}
     :warnings []
     :as-of-ms 1777046400000}))

(defn- timed-worker-run
  [request]
  (let [started-at-ms (now-ms)]
    (-> (worker/optimizer-result-payload request)
        (.then (fn [result]
                 {:size (count (:universe request))
                  :elapsed-ms (- (now-ms) started-at-ms)
                  :result result})))))

(defn- run-timed-requests
  [requests]
  (reduce (fn [chain request]
            (.then chain
                   (fn [results]
                     (-> (timed-worker-run request)
                         (.then (fn [result]
                                  (conj results result)))))))
          (js/Promise.resolve [])
          requests))

(deftest optimizer-result-payload-runs-engine-with-worker-solver-test
  (async done
    (let [captured (atom nil)
          request {:scenario-id "scenario-1"}]
      (with-redefs [worker/run-optimization-async
                    (fn [request* opts]
                      (reset! captured {:request request*
                                        :solve-problem (:solve-problem opts)})
                      (js/Promise.resolve {:status :solved
                                           :scenario-id (:scenario-id request*)}))]
        (-> (worker/optimizer-result-payload request)
            (.then (fn [result]
                     (is (= {:status :solved
                             :scenario-id "scenario-1"}
                            result))
                     (is (= request (:request @captured)))
                     (is (fn? (:solve-problem @captured)))
                     (done)))
            (.catch (fn [err]
                      (is false (str "worker payload failed: " err))
                      (done))))))))

(deftest optimizer-result-payload-normalizes-worker-decoded-instrument-key-maps-test
  (async done
    (let [decoded-id (keyword "perp:BTC")
          decoded-spot-id (keyword "spot:PURR/USDC")
          captured (atom nil)
          request {:scenario-id "scenario-1"
                   :universe [{:instrument-id "perp:BTC"
                               :market-type :perp
                               :coin "BTC"}
                              {:instrument-id "spot:PURR/USDC"
                               :market-type :spot
                               :coin "PURR"}]
                   :current-portfolio {:by-instrument {decoded-id {:weight 0.8}
                                                       decoded-spot-id {:weight 0.2}}}
                   :history {:return-series-by-instrument {decoded-id [0.01 0.02]}
                             :price-series-by-instrument {decoded-id [{:close 100}
                                                                      {:close 101}]}
                             :funding-by-instrument {decoded-id {:annualized-carry 0.01}}}
                   :black-litterman-prior {:weights-by-instrument {decoded-id 1}}
                   :constraints {:per-asset-overrides {decoded-id {:max-weight 0.5}}
                                 :per-perp-leverage-caps {decoded-id {:max-weight 0.4}}}
                   :execution-assumptions {:prices-by-id {decoded-id 100
                                                          decoded-spot-id 2}
                                           :fee-bps-by-id {decoded-id 4}}}]
      (with-redefs [worker/run-optimization-async
                    (fn [request* _opts]
                      (reset! captured request*)
                      (js/Promise.resolve {:status :solved}))]
        (-> (worker/optimizer-result-payload request)
            (.then (fn [_]
                     (is (= {"perp:BTC" {:weight 0.8}
                             "spot:PURR/USDC" {:weight 0.2}}
                            (get-in @captured [:current-portfolio :by-instrument])))
                     (is (= {"perp:BTC" [0.01 0.02]}
                            (get-in @captured [:history :return-series-by-instrument])))
                     (is (= {"perp:BTC" {:annualized-carry 0.01}}
                            (get-in @captured [:history :funding-by-instrument])))
                     (is (= {"perp:BTC" 1}
                            (get-in @captured [:black-litterman-prior :weights-by-instrument])))
                     (is (= {"perp:BTC" {:max-weight 0.5}}
                            (get-in @captured [:constraints :per-asset-overrides])))
                     (is (= {"perp:BTC" 100
                             "spot:PURR/USDC" 2}
                            (get-in @captured [:execution-assumptions :prices-by-id])))
                     (done)))
            (.catch (fn [err]
                      (is false (str "worker payload normalization failed: " err))
                      (done))))))))

(deftest optimizer-result-payload-normalizes-worker-decoded-enum-values-test
  (async done
    (let [captured (atom nil)
          request {:scenario-id "scenario-1"
                   :universe [{:instrument-id "perp:BTC"
                               :market-type "perp"
                               :coin "BTC"}]
                   :return-model {:kind "historical-mean"}
                   :risk-model {:kind "diagonal-shrink"}
                   :objective {:kind "minimum-variance"}
                   :history {:funding-by-instrument {"perp:BTC" {:annualized-carry 0.01
                                                                  :source "market-funding-history"}}}
                   :execution-assumptions {:default-order-type "market"
                                           :fee-mode "taker"}}]
      (with-redefs [worker/run-optimization-async
                    (fn [request* _opts]
                      (reset! captured request*)
                      (js/Promise.resolve {:status :solved}))]
        (-> (worker/optimizer-result-payload request)
            (.then (fn [_]
                     (is (= :perp
                            (get-in @captured [:universe 0 :market-type])))
                     (is (= :historical-mean
                            (get-in @captured [:return-model :kind])))
                     (is (= :diagonal-shrink
                            (get-in @captured [:risk-model :kind])))
                     (is (= :minimum-variance
                            (get-in @captured [:objective :kind])))
                     (is (= :market-funding-history
                            (get-in @captured [:history :funding-by-instrument "perp:BTC" :source])))
                     (is (= :market
                            (get-in @captured [:execution-assumptions :default-order-type])))
                     (is (= :taker
                            (get-in @captured [:execution-assumptions :fee-mode])))
                     (done)))
            (.catch (fn [err]
                      (is false (str "worker enum value normalization failed: " err))
                      (done))))))))

(deftest optimizer-result-payload-solves-realistic-universes-within-runaway-budget-test
  (async done
    (let [budgets-by-size {20 3000
                           40 4000
                           60 5000}]
      (-> (run-timed-requests (mapv synthetic-request [20 40 60]))
          (.then (fn [runs]
                   (doseq [{:keys [size elapsed-ms result]} runs]
                     (is (= :solved (:status result))
                         (str "expected solved optimizer result for " size " instruments"))
                     (is (= size (count (:target-weights result)))
                         (str "expected target weights for every instrument in " size " universe"))
                     (is (< elapsed-ms (get budgets-by-size size))
                         (str "optimizer worker run for " size
                              " instruments exceeded runaway budget: "
                              elapsed-ms "ms")))
                   (done)))
          (.catch (fn [err]
                    (is false (str "worker performance guard failed: " err))
                    (done)))))))
