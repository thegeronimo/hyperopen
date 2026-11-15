(ns hyperopen.portfolio.optimizer.application.engine-progress-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.portfolio.optimizer.application.engine :as engine]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]))

(def ^:private base-request
  (fixtures/sample-engine-request
   {:draft (fixtures/sample-draft
            {:id "progress-scenario"
             :universe [{:instrument-id "perp:BTC"
                         :market-type :perp
                         :coin "BTC"}
                        {:instrument-id "perp:ETH"
                         :market-type :perp
                         :coin "ETH"}]
             :return-model {:kind :historical-mean}
             :risk-model {:kind :sample-covariance}
             :objective {:kind :minimum-variance}
             :constraints {:long-only? true
                           :max-asset-weight 0.8
                           :rebalance-tolerance 0.001}})
    :current-portfolio (fixtures/sample-current-portfolio
                        {:capital {:nav-usdc 10000}
                         :by-instrument {"perp:BTC" {:weight 0.6}
                                         "perp:ETH" {:weight 0.4}}})
    :history-data {:candle-history-by-coin
                   {"BTC" [{:time-ms 0 :close "100"}
                           {:time-ms 100 :close "101"}
                           {:time-ms 200 :close "103.02"}
                           {:time-ms 300 :close "106.1106"}]
                    "ETH" [{:time-ms 0 :close "100"}
                           {:time-ms 100 :close "102"}
                           {:time-ms 200 :close "103.02"}
                           {:time-ms 300 :close "103.02"}]}
                   :funding-history-by-coin {}}
    :market-cap-by-coin {}
    :as-of-ms 1000}))

(deftest run-optimization-async-reports-step-progress-sequence-test
  (async done
    (let [events (atom [])]
      (-> (engine/run-optimization-async
           base-request
           {:solve-problem (fn [_problem]
                             (js/Promise.resolve
                              {:status :solved
                               :solver :progress-fixture-solver
                               :weights [0.5 0.5]}))
            :on-progress (fn [payload]
                           (swap! events conj payload))})
          (.then (fn [result]
                   (let [events* @events
                         by-step (fn [step]
                                   (filterv #(= step (:step %)) events*))
                         statuses (fn [step]
                                    (mapv :status (by-step step)))
                         non-decreasing? (fn [percents]
                                           (every? (fn [[a b]] (<= a b))
                                                   (partition 2 1 percents)))]
                     (is (= :solved (:status result)))
                     (is (= {:step :risk-model :status :running}
                            (select-keys (first events*) [:step :status])))
                     (is (= [:running :succeeded] (statuses :risk-model)))
                     (is (= [:running :succeeded] (statuses :return-model)))
                     (is (= "encoding constraints"
                            (:detail (first (by-step :solve)))))
                     (is (= :succeeded (last (statuses :solve))))
                     (is (non-decreasing? (mapv :percent (by-step :solve))))
                     (is (some #(re-matches #"\d+/\d+ points" (or (:detail %) ""))
                               (by-step :frontier)))
                     (is (non-decreasing? (mapv :percent (by-step :frontier))))
                     (is (= :succeeded (:status (last (by-step :frontier)))))
                     (is (= [:running :succeeded] (statuses :diagnostics)))
                     (is (= {:step :diagnostics :status :succeeded}
                            (select-keys (last events*) [:step :status]))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "async progress sequence failed: " err))
                    (done)))))))
