(ns hyperopen.portfolio.optimizer.infrastructure.solver-adapter
  (:require ["osqp" :default OSQP]
            ["quadprog" :as quadprog]
            [hyperopen.portfolio.optimizer.domain.math :as math]))

(def ^:private epsilon
  0.00000001)

(def ^:private osqp-infinity
  1.0e20)

(defn- supported-l1-constraint?
  [constraint]
  (= :gross-exposure (:code constraint)))

(defn- unsupported-l1-constraints
  [problem]
  (seq (remove supported-l1-constraint? (:l1-constraints problem))))

(defn- unsupported-result
  [reason details]
  {:status :unsupported
   :reason reason
   :details details})

(defn- add-diagonal-epsilon
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
    {:original-idx (- idx n)
     :sign -1}))

(defn- split-coefficients
  [coefficients]
  (vec (concat coefficients
               (mapv - coefficients))))

(defn- split-quadratic
  [quadratic]
  (let [n (count quadratic)]
    (mapv (fn [row-idx]
            (let [{row-original :original-idx row-sign :sign} (split-var n row-idx)]
              (mapv (fn [col-idx]
                      (let [{col-original :original-idx col-sign :sign} (split-var n col-idx)]
                        (* row-sign
                           col-sign
                           (get-in quadratic [row-original col-original]))))
                    (range (* 2 n)))))
          (range (* 2 n)))))

(defn- split-linear
  [linear]
  (vec (concat linear
               (mapv - linear))))

(defn- split-equality
  [constraint]
  (update constraint :coefficients split-coefficients))

(defn- split-inequality
  [constraint]
  (update constraint :coefficients split-coefficients))

(defn- split-bound-inequality
  [n idx lower upper]
  (let [coefficients (split-coefficients
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
  [n constraint]
  {:code :gross-exposure
   :coefficients (vec (repeat (* 2 n) 1))
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

(defn- adapt-problem
  [problem]
  (if (split-required? problem)
    (let [n (count (:instrument-ids problem))
          gross-constraints (filterv #(= :gross-exposure (:code %))
                                     (:l1-constraints problem))
          bound-inequalities (mapcat (fn [idx lower upper]
                                       (split-bound-inequality n idx lower upper))
                                     (range n)
                                     (:lower-bounds problem)
                                     (:upper-bounds problem))]
      {:problem (assoc problem
                       :instrument-ids (vec (concat (mapv #(str % ":positive")
                                                           (:instrument-ids problem))
                                                   (mapv #(str % ":negative")
                                                         (:instrument-ids problem))))
                       :quadratic (split-quadratic (:quadratic problem))
                       :linear (split-linear (:linear problem))
                       :equalities (mapv split-equality (:equalities problem))
                       :inequalities (vec (concat (mapv split-inequality
                                                        (:inequalities problem))
                                                  bound-inequalities
                                                  (mapv (partial gross-inequality n)
                                                        gross-constraints)))
                       :l1-constraints []
                       :lower-bounds (vec (repeat (* 2 n) 0))
                       :upper-bounds (vec (repeat (* 2 n) nil)))
       :decode (partial decode-split-weights n)})
    {:problem problem
     :decode identity}))

(defn- one-indexed-vector
  [values]
  (clj->js (vec (cons nil values))))

(defn- one-indexed-matrix
  [matrix]
  (clj->js (vec (cons nil
                      (mapv #(vec (cons nil %)) matrix)))))

(defn- constraint-columns
  [problem]
  (let [equalities (or (:equalities problem) [])
        inequalities (or (:inequalities problem) [])
        lower-bounds (:lower-bounds problem)
        upper-bounds (:upper-bounds problem)
        n (count (:instrument-ids problem))
        equality-columns (mapv (fn [constraint]
                                 {:coefficients (:coefficients constraint)
                                  :bound (:target constraint)
                                  :equality? true})
                               equalities)
        inequality-columns (mapcat (fn [constraint]
                                     (concat
                                      (when (number? (:lower constraint))
                                        [{:coefficients (:coefficients constraint)
                                          :bound (:lower constraint)}])
                                      (when (number? (:upper constraint))
                                        [{:coefficients (mapv - (:coefficients constraint))
                                          :bound (- (:upper constraint))}])))
                                   inequalities)
        lower-columns (map-indexed (fn [idx lower]
                                     (when (number? lower)
                                       {:coefficients (mapv (fn [i]
                                                              (if (= i idx) 1 0))
                                                            (range n))
                                        :bound lower}))
                                   lower-bounds)
        upper-columns (map-indexed (fn [idx upper]
                                     (when (number? upper)
                                       {:coefficients (mapv (fn [i]
                                                              (if (= i idx) -1 0))
                                                            (range n))
                                        :bound (- upper)}))
                                   upper-bounds)]
    {:columns (vec (concat equality-columns
                           inequality-columns
                           (keep identity lower-columns)
                           (keep identity upper-columns)))
     :meq (count equality-columns)}))

(defn- quadprog-amat
  [n columns]
  (mapv (fn [row-idx]
          (mapv #(nth (:coefficients %) row-idx) columns))
        (range n)))

(defn- objective-value
  [problem weights]
  (+ (* 0.5 (math/portfolio-variance weights (:quadratic problem)))
     (math/dot (:linear problem) weights)))

(defn- normalize-quadprog-solution
  [problem solved decode]
  (let [message (.-message ^js solved)]
    (if (seq message)
      {:status :infeasible
       :solver :quadprog
       :reason :solver-message
       :message message}
      (let [solution (vec (js->clj (.slice (.-solution ^js solved) 1)))
            weights (decode solution)]
        {:status :solved
         :solver :quadprog
         :weights weights
         :objective-value (objective-value problem weights)
         :iterations (second (js->clj (.-iterations solved)))}))))

(defn solve-with-quadprog
  [problem]
  (if-let [unsupported (unsupported-l1-constraints problem)]
    (unsupported-result :l1-constraints-not-implemented
                        {:constraints (vec unsupported)})
    (let [{adapted-problem :problem decode :decode} (adapt-problem problem)
          n (count (:instrument-ids adapted-problem))
          {:keys [columns meq]} (constraint-columns adapted-problem)
          dmat (one-indexed-matrix (add-diagonal-epsilon (:quadratic adapted-problem)))
          dvec (one-indexed-vector (mapv - (:linear adapted-problem)))
          amat (one-indexed-matrix (quadprog-amat n columns))
          bvec (one-indexed-vector (mapv :bound columns))
          solved (.solveQP quadprog dmat dvec amat bvec meq)]
      (normalize-quadprog-solution problem solved decode))))

(defn- float64-array
  [values]
  (js/Float64Array.from (clj->js values)))

(defn- int32-array
  [values]
  (js/Int32Array.from (clj->js values)))

(defn- dense->csc
  [matrix opts]
  (let [upper-triangle? (:upper-triangle? opts)
        n-cols (if (seq matrix) (count (first matrix)) 0)]
    (loop [col 0
           data []
           row-indices []
           column-pointers [0]]
      (if (= col n-cols)
        #js {:data (float64-array data)
             :row_indices (int32-array row-indices)
             :column_pointers (int32-array column-pointers)}
        (let [entries (->> (range (count matrix))
                           (keep (fn [row]
                                   (let [value (get-in matrix [row col])]
                                     (when (and (number? value)
                                                (not (zero? value))
                                                (or (not upper-triangle?)
                                                    (<= row col)))
                                       {:row row
                                        :value value})))))
              data* (into data (map :value entries))
              rows* (into row-indices (map :row entries))]
          (recur (inc col)
                 data*
                 rows*
                 (conj column-pointers (count data*))))))))

(defn- unit-row
  [n idx]
  (mapv (fn [i]
          (if (= i idx) 1 0))
        (range n)))

(defn- osqp-rows
  [problem]
  (let [n (count (:instrument-ids problem))
        equality-rows (mapv (fn [constraint]
                              {:coefficients (:coefficients constraint)
                               :lower (:target constraint)
                               :upper (:target constraint)})
                            (:equalities problem))
        inequality-rows (mapcat (fn [constraint]
                                  (concat
                                   (when (number? (:lower constraint))
                                     [{:coefficients (:coefficients constraint)
                                       :lower (:lower constraint)
                                       :upper osqp-infinity}])
                                   (when (number? (:upper constraint))
                                     [{:coefficients (:coefficients constraint)
                                       :lower (- osqp-infinity)
                                       :upper (:upper constraint)}])))
                                (:inequalities problem))
        bound-rows (mapv (fn [idx lower upper]
                           {:coefficients (unit-row n idx)
                            :lower (if (number? lower) lower (- osqp-infinity))
                            :upper (if (number? upper) upper osqp-infinity)})
                         (range n)
                         (:lower-bounds problem)
                         (:upper-bounds problem))]
    (vec (concat equality-rows inequality-rows bound-rows))))

(defn- osqp-options
  [problem]
  (let [rows (osqp-rows problem)]
    #js {:P (dense->csc (add-diagonal-epsilon (:quadratic problem))
                        {:upper-triangle? true})
         :A (dense->csc (mapv :coefficients rows)
                        {:upper-triangle? false})
         :q (float64-array (:linear problem))
         :l (float64-array (mapv :lower rows))
         :u (float64-array (mapv :upper rows))}))

(defn- osqp-settings
  []
  #js {:verbose false
       :eps_abs 0.00000001
       :eps_rel 0.00000001
       :polish true
       :max_iter 10000})

(defn- normalize-osqp-solution
  [problem solution decode]
  (let [weights (decode (vec (js->clj solution)))]
    {:status :solved
     :solver :osqp
     :weights weights
     :objective-value (objective-value problem weights)}))

(defn solve-with-osqp
  [problem]
  (if-let [unsupported (unsupported-l1-constraints problem)]
    (js/Promise.resolve
     (unsupported-result :l1-constraints-not-implemented
                         {:constraints (vec unsupported)}))
    (let [{adapted-problem :problem decode :decode} (adapt-problem problem)]
      (-> (.setup OSQP (osqp-options adapted-problem) (osqp-settings))
          (.then (fn [^js solver]
                   (try
                     (let [solution (.solve solver)]
                       (normalize-osqp-solution problem solution decode))
                     (finally
                       (.cleanup solver)))))
          (.catch (fn [err]
                    {:status :error
                     :solver :osqp
                     :reason :solver-error
                     :message (str err)}))))))
