(ns hyperopen.portfolio.optimizer.domain.frontier)

(defn- sharpe
  [risk-free-rate point]
  (let [vol (:volatility point)]
    (when (and (number? vol)
               (pos? vol))
      (/ (- (:expected-return point) risk-free-rate)
         vol))))

(defn efficient-frontier
  [points]
  (->> points
       (sort-by (juxt :volatility (comp - :expected-return)))
       (reduce (fn [acc point]
                 (let [best-return (if (seq acc)
                                     (apply max (map :expected-return acc))
                                     js/Number.NEGATIVE_INFINITY)]
                   (if (> (:expected-return point) best-return)
                     (conj acc point)
                     acc)))
               [])
       vec))

(defn select-frontier-point
  [points objective]
  (let [points* (vec (or points []))]
    (case (:kind objective)
      :minimum-variance
      (first (sort-by :volatility points*))

      :max-sharpe
      (last (sort-by #(or (sharpe (or (:risk-free-rate objective) 0) %)
                          js/Number.NEGATIVE_INFINITY)
                     points*))

      :target-return
      (or (first (sort-by :volatility
                          (filter #(>= (:expected-return %) (:target-return objective))
                                  points*)))
          (last (sort-by :expected-return points*)))

      :target-volatility
      (first (sort-by #(js/Math.abs (- (:volatility %)
                                       (:target-volatility objective)))
                      points*))

      nil)))
