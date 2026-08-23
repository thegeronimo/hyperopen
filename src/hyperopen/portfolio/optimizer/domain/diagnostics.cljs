(ns hyperopen.portfolio.optimizer.domain.diagnostics
  (:require [hyperopen.portfolio.optimizer.domain.math :as math]
            [hyperopen.portfolio.optimizer.domain.risk :as risk]))

(defn- abs-num
  [value]
  (js/Math.abs value))

(defn exposure-summary
  [weights]
  (reduce (fn [acc weight]
            (let [weight* (or weight 0)]
              (cond
                (pos? weight*)
                (-> acc
                    (update :long-exposure + weight*)
                    (update :gross-exposure + weight*)
                    (update :net-exposure + weight*))

                (neg? weight*)
                (-> acc
                    (update :short-exposure + (- weight*))
                    (update :gross-exposure + (- weight*))
                    (update :net-exposure + weight*))

                :else acc)))
          {:long-exposure 0
           :short-exposure 0
           :gross-exposure 0
           :net-exposure 0}
          weights))

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

(def ^:private binding-tolerance
  ;; Must be looser than the QP solver's convergence noise: live runs land
  ;; ~3e-10 off a bound (sometimes slightly OUTSIDE it), so a 1e-10 epsilon
  ;; made the capped/floored flags flicker run-to-run. 1e-6 of portfolio
  ;; weight is far below both display precision (0.1%) and the dust
  ;; threshold (5e-4), so it cannot mislabel a genuinely interior weight.
  1e-6)

(defn- binding-constraints
  [{:keys [instrument-ids target-weights lower-bounds upper-bounds]}]
  (->> (map vector instrument-ids target-weights lower-bounds upper-bounds)
       (mapcat (fn [[id weight lower upper]]
                 (concat
                  (when (and (number? lower)
                             (<= (abs-num (- weight lower)) binding-tolerance))
                    [{:instrument-id id
                      :constraint :lower-bound
                      :weight weight
                      :bound lower}])
                  (when (and (number? upper)
                             (<= (abs-num (- weight upper)) binding-tolerance))
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
  (let [exposure (exposure-summary target-weights)]
    (cond-> (merge exposure
                   {:effective-n (effective-n target-weights)
                    :max-weight (apply max (map abs-num target-weights))
                    :turnover (turnover current-weights target-weights)
                    :binding-constraints (binding-constraints opts)
                    :covariance-conditioning (risk/covariance-conditioning covariance)
                    ;; Conditioning is an eigenvalue RATIO and so cannot see
                    ;; magnitude: a scaled identity scores a perfect 1 however
                    ;; large its entries are. The plausibility check is the only
                    ;; one that can reject a 7,200% volatility.
                    :covariance-plausibility (risk/covariance-plausibility
                                              covariance
                                              (:instrument-ids opts))})
      (seq expected-returns)
      (assoc :weight-sensitivity-by-instrument
             (weight-sensitivity-by-instrument
              {:instrument-ids (:instrument-ids opts)
               :weights target-weights
               :expected-returns expected-returns
               :shock 0.01
               :top-n 5})))))
