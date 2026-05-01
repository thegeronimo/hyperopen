(ns hyperopen.portfolio.optimizer.application.engine.target-selection
  (:require [hyperopen.portfolio.optimizer.domain.frontier :as frontier]
            [hyperopen.portfolio.optimizer.domain.math :as math]))

(defn- sqrt
  [value]
  (js/Math.sqrt (max 0 value)))

(defn- solved?
  [result]
  (= :solved (:status result)))

(defn- portfolio-point
  [expected-returns covariance risk-free-rate idx result]
  (let [weights (:weights result)
        expected-return (math/portfolio-return weights expected-returns)
        variance (math/portfolio-variance weights covariance)
        volatility (sqrt variance)]
    {:id idx
     :return-tilt (get-in result [:problem :return-tilt])
     :weights weights
     :expected-return expected-return
     :volatility volatility
     :sharpe (when (pos? volatility)
               (/ (- expected-return (or risk-free-rate 0))
                  volatility))
     :solver-status (:status result)
     :solver (:solver result)
     :iterations (:iterations result)
     :elapsed-ms (:elapsed-ms result)}))

(defn- solver-failure
  [solver-plan solver-results]
  {:status :infeasible
   :reason :solver-returned-no-solution
   :solver {:strategy (:strategy solver-plan)}
   :solver-results solver-results})

(defn solved-points
  [request solver-results expected-returns covariance]
  (->> solver-results
       (keep-indexed (fn [idx result]
                       (when (solved? result)
                         (portfolio-point expected-returns
                                          covariance
                                          (:risk-free-rate request)
                                          idx
                                          result))))
       vec))

(defn target-selection
  [request solver-plan solver-results expected-returns covariance]
  (let [points (solved-points request solver-results expected-returns covariance)]
    (if (empty? points)
      (solver-failure solver-plan solver-results)
      (let [frontier-points (frontier/efficient-frontier points)
            selected (or (when (= :frontier-sweep (:strategy solver-plan))
                           (frontier/select-frontier-point frontier-points (:objective request)))
                         (first frontier-points)
                         (first points))]
        {:status :solved
         :selected selected
         :target-frontier frontier-points}))))
