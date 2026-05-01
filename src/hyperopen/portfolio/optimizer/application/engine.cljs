(ns hyperopen.portfolio.optimizer.application.engine
  (:require [hyperopen.portfolio.optimizer.application.engine.context :as context]
            [hyperopen.portfolio.optimizer.application.engine.payload :as payload]
            [hyperopen.portfolio.optimizer.application.engine.solve :as solve]))

(defn run-optimization
  ([request]
   (run-optimization request {}))
  ([request {:keys [solve-problem]}]
   (let [{:keys [risk-result return-result solver-plan] :as optimization-context}
         (context/optimization-context request)]
     (if (= :infeasible (:status solver-plan))
       (payload/infeasible-payload request risk-result return-result solver-plan)
       (let [solve-problem* (or solve-problem solve/default-solve-problem)
             solver-results (solve/solve-plan solver-plan solve-problem*)
             display-frontier-results (solve/solve-display-frontier-plans
                                       (:display-frontier-plans optimization-context)
                                       solve-problem*)]
         (payload/result-from-solver-results request
                                            optimization-context
                                            solver-results
                                            display-frontier-results))))))

(defn run-optimization-async
  ([request]
   (run-optimization-async request {}))
  ([request {:keys [solve-problem on-progress]}]
   (let [{:keys [risk-result return-result solver-plan] :as optimization-context}
         (context/optimization-context request on-progress)
         solve-problem* (or solve-problem solve/default-solve-problem)]
     (if (= :infeasible (:status solver-plan))
       (js/Promise.resolve
        (payload/infeasible-payload request risk-result return-result solver-plan))
       (do
         (context/report-progress!
          on-progress
          {:step :solve
           :status :running
           :percent 0
           :detail (str (count (:problems solver-plan)) " problems")})
         (-> (solve/solve-plan-async solver-plan
                                     solve-problem*
                                     on-progress)
             (.then (fn [solver-results]
                      (let [display-frontier-plans (:display-frontier-plans optimization-context)
                            finish-result (fn [display-frontier-results]
                                            (context/report-progress!
                                             on-progress
                                             {:step :frontier
                                              :status :running
                                              :percent 80
                                              :detail "selecting frontier"})
                                            (let [result (payload/result-from-solver-results
                                                          request
                                                          optimization-context
                                                          solver-results
                                                          display-frontier-results)]
                                              (context/report-progress!
                                               on-progress
                                               {:step :diagnostics
                                                :status :succeeded
                                                :percent 100
                                                :detail "complete"})
                                              (context/report-progress!
                                               on-progress
                                               {:step :frontier
                                                :status :succeeded
                                                :percent 100
                                                :detail (str (count (:frontier result)) " points")})
                                              result))]
                        (if (seq display-frontier-plans)
                          (-> (solve/solve-display-frontier-plans-async
                               display-frontier-plans
                               solve-problem*)
                              (.then finish-result))
                          (finish-result {})))))))))))
