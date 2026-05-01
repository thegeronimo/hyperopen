(ns hyperopen.portfolio.optimizer.application.engine.solve)

(defn default-solve-problem
  [_problem]
  {:status :error
   :reason :solver-not-configured})

(defn solve-plan
  [solver-plan solve-problem]
  (mapv (fn [problem]
          (let [result (solve-problem problem)]
            (assoc result :problem problem)))
        (:problems solver-plan)))

(defn solve-display-frontier-plans
  [display-frontier-plans solve-problem]
  (into {}
        (keep (fn [[constraint-mode display-frontier-plan]]
                (when display-frontier-plan
                  [constraint-mode
                   (solve-plan display-frontier-plan solve-problem)])))
        display-frontier-plans))

(defn- report-progress!
  [on-progress payload]
  (when (fn? on-progress)
    (on-progress payload)))

(defn solve-plan-async
  ([solver-plan solve-problem]
   (solve-plan-async solver-plan solve-problem nil))
  ([solver-plan solve-problem on-progress]
   (let [problems (vec (:problems solver-plan))
         total (count problems)
         completed (atom 0)]
     (-> (js/Promise.all
          (clj->js
           (mapv
            (fn [problem]
              (-> (js/Promise.resolve (solve-problem problem))
                  (.then
                   (fn [result]
                     (let [done (swap! completed inc)]
                       (report-progress!
                        on-progress
                        {:step :solve
                         :status (if (= done total) :succeeded :running)
                         :percent (if (pos? total)
                                    (* 100 (/ done total))
                                    100)
                         :detail (str done "/" total " problems")})
                       (assoc result :problem problem))))))
            problems)))
         (.then (fn [results]
                  (vec (array-seq results))))))))

(defn solve-display-frontier-plans-async
  [display-frontier-plans solve-problem]
  (let [entries (vec (keep (fn [[constraint-mode display-frontier-plan]]
                             (when display-frontier-plan
                               [constraint-mode display-frontier-plan]))
                           display-frontier-plans))]
    (reduce (fn [chain [constraint-mode display-frontier-plan]]
              (.then chain
                     (fn [results-by-mode]
                       (-> (solve-plan-async display-frontier-plan solve-problem)
                           (.then (fn [results]
                                    (assoc results-by-mode constraint-mode results)))))))
            (js/Promise.resolve {})
            entries)))
