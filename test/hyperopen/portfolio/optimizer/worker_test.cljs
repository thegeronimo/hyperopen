(ns hyperopen.portfolio.optimizer.worker-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.portfolio.optimizer.worker :as worker]))

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
          captured (atom nil)
          request {:scenario-id "scenario-1"
                   :universe [{:instrument-id "perp:BTC"
                               :market-type :perp
                               :coin "BTC"}]
                   :current-portfolio {:by-instrument {decoded-id {:weight 1}}}
                   :history {:return-series-by-instrument {decoded-id [0.01 0.02]}
                             :price-series-by-instrument {decoded-id [{:close 100}
                                                                      {:close 101}]}
                             :funding-by-instrument {decoded-id {:annualized-carry 0.01}}}
                   :black-litterman-prior {:weights-by-instrument {decoded-id 1}}
                   :constraints {:per-asset-overrides {decoded-id {:max-weight 0.5}}
                                 :per-perp-leverage-caps {decoded-id {:max-weight 0.4}}}
                   :execution-assumptions {:prices-by-id {decoded-id 100}
                                           :fee-bps-by-id {decoded-id 4}}}]
      (with-redefs [worker/run-optimization-async
                    (fn [request* _opts]
                      (reset! captured request*)
                      (js/Promise.resolve {:status :solved}))]
        (-> (worker/optimizer-result-payload request)
            (.then (fn [_]
                     (is (= {"perp:BTC" {:weight 1}}
                            (get-in @captured [:current-portfolio :by-instrument])))
                     (is (= {"perp:BTC" [0.01 0.02]}
                            (get-in @captured [:history :return-series-by-instrument])))
                     (is (= {"perp:BTC" {:annualized-carry 0.01}}
                            (get-in @captured [:history :funding-by-instrument])))
                     (is (= {"perp:BTC" 1}
                            (get-in @captured [:black-litterman-prior :weights-by-instrument])))
                     (is (= {"perp:BTC" {:max-weight 0.5}}
                            (get-in @captured [:constraints :per-asset-overrides])))
                     (is (= {"perp:BTC" 100}
                            (get-in @captured [:execution-assumptions :prices-by-id])))
                     (done)))
            (.catch (fn [err]
                      (is false (str "worker payload normalization failed: " err))
                      (done))))))))
