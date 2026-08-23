(ns hyperopen.portfolio.optimizer.application.engine.solve
  (:require [hyperopen.portfolio.optimizer.application.engine.equal-risk-solve
             :as equal-risk-solve]
            [hyperopen.portfolio.optimizer.domain.closed-form :as closed-form]))

(defn default-solve-problem
  [_problem]
  {:status :error
   :reason :solver-not-configured})

(defn solve-one
  "Solves a single planned problem. Closed-form problems are computed by the
  pure domain solver and never reach the injected QP solver; sequential
  equal-risk problems iterate QP subproblems THROUGH the injected solver and
  report per-iteration progress when `on-progress` is provided."
  ([problem solve-problem]
   (solve-one problem solve-problem nil))
  ([problem solve-problem on-progress]
   (case (:kind problem)
     :closed-form-portfolio (closed-form/solve-portfolio problem)
     :sequential-equal-risk (equal-risk-solve/solve problem solve-problem on-progress)
     (solve-problem problem))))

(defn transportable-solver-results
  "Drops the dense quadratic from each result's attached :problem before the
  results are put on the wire.

  solve-plan attaches the whole problem so target-selection can re-validate the
  returned weights against the constraints that produced them. That re-check
  reads :instrument-ids, the bounds, the equality/inequality/L1 rows and
  :return-tilt -- never :quadratic. But :quadratic is the covariance, and the
  frontier sweep attaches the SAME matrix to every point, so a 100-asset run
  serialized one 100x100 matrix forty times: 8.28 MB of a 8.41 MB result
  payload, structured-cloned across the worker boundary and written to
  IndexedDB, for a value nothing downstream reads."
  [solver-results]
  (mapv (fn [result]
          (if (map? (:problem result))
            (update result :problem dissoc :quadratic)
            result))
        solver-results))

(defn solve-plan
  [solver-plan solve-problem]
  (mapv (fn [problem]
          (let [result (solve-one problem solve-problem)]
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

(defn- solve-problems-async
  ([problems solve-problem on-problem-result!]
   (solve-problems-async problems solve-problem on-problem-result! nil))
  ([problems solve-problem on-problem-result! on-progress]
   (-> (js/Promise.all
        (clj->js
         (mapv
          (fn [problem]
            (-> (js/Promise.resolve (solve-one problem solve-problem on-progress))
                (.then
                 (fn [result]
                   (when on-problem-result!
                     (on-problem-result!))
                   (assoc result :problem problem)))))
          problems)))
       (.then (fn [results]
                (vec (array-seq results)))))))

(def solve-start-percent
  "Solve-step percent reported when the plan is handed to the solver;
  per-problem updates never drop below it."
  5)

(defn solve-plan-async
  ([solver-plan solve-problem]
   (solve-plan-async solver-plan solve-problem nil))
  ([solver-plan solve-problem on-progress]
   (let [problems (vec (:problems solver-plan))
         total (count problems)
         completed (atom 0)]
     (solve-problems-async
      problems
      solve-problem
      (fn []
        (let [done (swap! completed inc)]
          (report-progress!
           on-progress
           {:step :solve
            :status (if (= done total) :succeeded :running)
            :percent (if (pos? total)
                       (max solve-start-percent
                            (* 100 (/ done total)))
                       100)
            :detail (str done "/" total " problems")})))
      ;; Iterative strategies (sequential equal-risk) report per-iteration
      ;; progress from INSIDE solve-one; per-problem completion above still
      ;; caps the step at 100%.
      on-progress))))

(def frontier-sweep-percent-span
  "The :frontier step reserves the tail of its bar for selection and
  result assembly, so per-point sweep progress tops out below 100%."
  75)

(defn solve-display-frontier-plans-async
  ([display-frontier-plans solve-problem]
   (solve-display-frontier-plans-async display-frontier-plans solve-problem nil))
  ([display-frontier-plans solve-problem on-progress]
   (let [entries (vec (keep (fn [[constraint-mode display-frontier-plan]]
                              (when display-frontier-plan
                                [constraint-mode display-frontier-plan]))
                            display-frontier-plans))
         total (reduce + 0 (map #(count (:problems (second %))) entries))
         completed (atom 0)
         report-point! (fn []
                         (let [done (swap! completed inc)]
                           (report-progress!
                            on-progress
                            {:step :frontier
                             :status :running
                             :percent (if (pos? total)
                                        (* frontier-sweep-percent-span (/ done total))
                                        0)
                             :detail (str done "/" total " points")})))]
     (when (pos? total)
       (report-progress!
        on-progress
        {:step :frontier
         :status :running
         :percent 0
         :detail (str "0/" total " points")}))
     (reduce (fn [chain [constraint-mode display-frontier-plan]]
               (.then chain
                      (fn [results-by-mode]
                        (-> (solve-problems-async (vec (:problems display-frontier-plan))
                                                  solve-problem
                                                  report-point!)
                            (.then (fn [results]
                                     (assoc results-by-mode constraint-mode results)))))))
             (js/Promise.resolve {})
             entries))))
