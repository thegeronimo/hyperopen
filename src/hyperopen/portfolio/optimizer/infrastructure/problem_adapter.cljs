(ns hyperopen.portfolio.optimizer.infrastructure.problem-adapter
  (:require [hyperopen.portfolio.optimizer.domain.math :as math]))

(def ^:private epsilon
  0.00000001)

(defn- supported-l1-constraint?
  [problem constraint]
  (case (:code constraint)
    :gross-exposure true
    :turnover (= (count (:current-weights constraint))
                 (count (:instrument-ids problem)))
    false))

(defn unsupported-l1-constraints
  [problem]
  (seq (remove (partial supported-l1-constraint? problem)
               (:l1-constraints problem))))

(defn unsupported-result
  [reason details]
  {:status :unsupported
   :reason reason
   :details details})

(defn add-diagonal-epsilon
  [matrix]
  (mapv (fn [row row-idx]
          (mapv (fn [value col-idx]
                  (+ value (if (= row-idx col-idx) epsilon 0)))
                row
                (range)))
        matrix
        (range)))

(defn- split-var
  [n idx]
  (if (< idx n)
    {:original-idx idx
     :sign 1}
    (if (< idx (* 2 n))
      {:original-idx (- idx n)
       :sign -1}
      {:original-idx 0
       :sign 0})))

(defn- split-coefficients
  ([coefficients]
   (split-coefficients (* 2 (count coefficients)) coefficients))
  ([var-count coefficients]
   (vec (concat coefficients
                (mapv - coefficients)
                (repeat (- var-count (* 2 (count coefficients))) 0)))))

(defn- split-quadratic
  [quadratic var-count]
  (let [n (count quadratic)]
    (mapv (fn [row-idx]
            (let [{row-original :original-idx row-sign :sign} (split-var n row-idx)]
              (mapv (fn [col-idx]
                      (let [{col-original :original-idx col-sign :sign} (split-var n col-idx)]
                        (* row-sign
                           col-sign
                           (get-in quadratic [row-original col-original]))))
                    (range var-count))))
          (range var-count))))

(defn- split-linear
  [linear var-count]
  (vec (concat linear
               (mapv - linear)
               (repeat (- var-count (* 2 (count linear))) 0))))

(defn- split-equality
  [var-count constraint]
  (update constraint :coefficients (partial split-coefficients var-count)))

(defn- split-inequality
  [var-count constraint]
  (update constraint :coefficients (partial split-coefficients var-count)))

(defn- split-bound-inequality
  [n var-count idx lower upper]
  (let [coefficients (split-coefficients
                      var-count
                      (mapv (fn [i]
                              (if (= i idx) 1 0))
                            (range n)))]
    (cond-> []
      (number? lower)
      (conj {:code :weight-lower-bound
             :instrument-idx idx
             :coefficients coefficients
             :lower lower})

      (number? upper)
      (conj {:code :weight-upper-bound
             :instrument-idx idx
             :coefficients coefficients
             :upper upper}))))

(defn- gross-inequality
  [n var-count constraint]
  {:code :gross-exposure
   :coefficients (vec (concat (repeat (* 2 n) 1)
                              (repeat (- var-count (* 2 n)) 0)))
   :upper (:max constraint)})

(defn- turnover-positive-idx
  [n idx]
  (+ (* 2 n) idx))

(defn- turnover-negative-idx
  [n idx]
  (+ (* 3 n) idx))

(defn- turnover-equality
  [n var-count idx current-weight]
  {:code :turnover-difference
   :instrument-idx idx
   :coefficients (mapv (fn [var-idx]
                         (cond
                           (= var-idx idx) 1
                           (= var-idx (+ idx n)) -1
                           (= var-idx (turnover-positive-idx n idx)) -1
                           (= var-idx (turnover-negative-idx n idx)) 1
                           :else 0))
                       (range var-count))
   :target current-weight})

(defn- turnover-inequality
  [n var-count constraint]
  {:code :turnover
   :coefficients (mapv (fn [idx]
                         (if (>= idx (* 2 n)) 1 0))
                       (range var-count))
   :upper (:max constraint)})

(defn- split-required?
  [problem]
  (seq (filter :requires-split-variables? (:l1-constraints problem))))

(defn- decode-split-weights
  [n solution]
  (mapv (fn [idx]
          (- (nth solution idx)
             (nth solution (+ idx n))))
        (range n)))

(defn- expanded-instrument-ids
  [instrument-ids turnover?]
  (vec (concat (mapv #(str % ":positive") instrument-ids)
               (mapv #(str % ":negative") instrument-ids)
               (when turnover?
                 (concat (mapv #(str % ":turnover-positive") instrument-ids)
                         (mapv #(str % ":turnover-negative") instrument-ids))))))

(defn adapt-problem
  [problem]
  (if (split-required? problem)
    (let [n (count (:instrument-ids problem))
          gross-constraints (filterv #(= :gross-exposure (:code %))
                                     (:l1-constraints problem))
          turnover-constraints (filterv #(= :turnover (:code %))
                                        (:l1-constraints problem))
          turnover? (seq turnover-constraints)
          var-count (+ (* 2 n)
                       (if turnover? (* 2 n) 0))
          bound-inequalities (mapcat (fn [idx lower upper]
                                       (split-bound-inequality n var-count idx lower upper))
                                     (range n)
                                     (:lower-bounds problem)
                                     (:upper-bounds problem))
          turnover-equalities (mapcat (fn [constraint]
                                        (map-indexed (fn [idx current-weight]
                                                       (turnover-equality n
                                                                          var-count
                                                                          idx
                                                                          current-weight))
                                                     (:current-weights constraint)))
                                      turnover-constraints)]
      {:problem (assoc problem
                       :instrument-ids (expanded-instrument-ids (:instrument-ids problem)
                                                                turnover?)
                       :quadratic (split-quadratic (:quadratic problem) var-count)
                       :linear (split-linear (:linear problem) var-count)
                       :equalities (vec (concat (mapv (partial split-equality var-count)
                                                      (:equalities problem))
                                                turnover-equalities))
                       :inequalities (vec (concat (mapv (partial split-inequality var-count)
                                                        (:inequalities problem))
                                                  bound-inequalities
                                                  (mapv (partial gross-inequality n var-count)
                                                        gross-constraints)
                                                  (mapv (partial turnover-inequality n var-count)
                                                        turnover-constraints)))
                       :l1-constraints []
                       :lower-bounds (vec (repeat var-count 0))
                       :upper-bounds (vec (repeat var-count nil)))
       :decode (partial decode-split-weights n)
       :var-count var-count})
    {:problem problem
     :decode identity
     :var-count nil}))

(defn adapt-linear
  "The linear term in the adapted variable layout, for a `var-count` that
  adapt-problem already reported.

  A frontier sweep varies only :linear between its points, so this is the one
  piece infrastructure.osqp has to recompute per solve once it has cached the
  matrices. Splitting the linear term is O(var-count); splitting the quadratic
  is O(var-count^2), which is what the cache exists to skip."
  [problem var-count]
  (if var-count
    (split-linear (:linear problem) var-count)
    (:linear problem)))

(defn objective-value
  [problem weights]
  (+ (* 0.5 (math/portfolio-variance weights (:quadratic problem)))
     (math/dot (:linear problem) weights)))
