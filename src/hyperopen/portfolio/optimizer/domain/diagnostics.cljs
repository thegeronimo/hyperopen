(ns hyperopen.portfolio.optimizer.domain.diagnostics
  (:require [hyperopen.portfolio.optimizer.domain.math :as math]
            [hyperopen.portfolio.optimizer.domain.risk :as risk]))

(defn- abs-num
  [value]
  (js/Math.abs value))

(defn- gross-exposure
  [weights]
  (reduce + 0 (map abs-num weights)))

(defn- effective-n
  [weights]
  (let [denom (reduce + 0 (map #(* % %) weights))]
    (when (pos? denom)
      (/ 1 denom))))

(defn- turnover
  [current target]
  (* 0.5
     (reduce + 0
             (map (fn [a b]
                    (abs-num (- b a)))
                  current
                  target))))

(defn- binding-constraints
  [{:keys [instrument-ids target-weights lower-bounds upper-bounds]}]
  (->> (map vector instrument-ids target-weights lower-bounds upper-bounds)
       (mapcat (fn [[id weight lower upper]]
                 (concat
                  (when (and (number? lower)
                             (<= (abs-num (- weight lower)) 1e-10))
                    [{:instrument-id id
                      :constraint :lower-bound
                      :weight weight
                      :bound lower}])
                  (when (and (number? upper)
                             (<= (abs-num (- weight upper)) 1e-10))
                    [{:instrument-id id
                      :constraint :upper-bound
                      :weight weight
                      :bound upper}]))))
       vec))

(defn- renormalize
  [weights]
  (let [total (reduce + 0 weights)]
    (if (zero? total)
      weights
      (mapv #(/ % total) weights))))

(defn- perturb
  [weights idx delta]
  (renormalize
   (mapv (fn [i weight]
           (if (= i idx)
             (max 0 (+ weight delta))
             weight))
         (range)
         weights)))

(defn weight-sensitivity
  [{:keys [instrument-ids weights expected-returns shock top-n]}]
  (let [shock* (or shock 0.01)
        base-return (math/portfolio-return weights expected-returns)
        top-indexes (->> (map-indexed vector weights)
                         (sort-by (fn [[_ weight]] (- (abs-num weight))))
                         (take (or top-n 5))
                         (map first))]
    (mapv (fn [idx]
            (let [down (perturb weights idx (- shock*))
                  up (perturb weights idx shock*)]
              {:instrument-id (nth instrument-ids idx)
               :base-expected-return base-return
               :down-expected-return (math/portfolio-return down expected-returns)
               :up-expected-return (math/portfolio-return up expected-returns)
               :shock shock*}))
          top-indexes)))

(defn- weight-sensitivity-by-instrument
  [opts]
  (into {}
        (map (fn [{:keys [instrument-id] :as row}]
               [instrument-id (dissoc row :instrument-id)]))
        (weight-sensitivity opts)))

(defn portfolio-diagnostics
  [{:keys [target-weights current-weights covariance expected-returns] :as opts}]
  (cond-> {:gross-exposure (gross-exposure target-weights)
           :net-exposure (reduce + 0 target-weights)
           :effective-n (effective-n target-weights)
           :max-weight (apply max (map abs-num target-weights))
           :turnover (turnover current-weights target-weights)
           :binding-constraints (binding-constraints opts)
           :covariance-conditioning (risk/covariance-conditioning covariance)}
    (seq expected-returns)
    (assoc :weight-sensitivity-by-instrument
           (weight-sensitivity-by-instrument
            {:instrument-ids (:instrument-ids opts)
             :weights target-weights
             :expected-returns expected-returns
             :shock 0.01
             :top-n 5}))))
