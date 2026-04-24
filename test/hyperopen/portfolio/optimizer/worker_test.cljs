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
