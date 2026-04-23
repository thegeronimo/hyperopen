(ns hyperopen.portfolio.optimizer.infrastructure.solver-adapter
  (:require ["osqp" :default OSQP]
            ["quadprog" :as quadprog]
            [hyperopen.portfolio.optimizer.domain.math :as math]))

(def ^:private epsilon
  0.00000001)

(def ^:private osqp-infinity
  1.0e20)

(defn- unsupported-split-constraints
  [problem]
  (seq (filter :requires-split-variables? (:l1-constraints problem))))

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
  [problem solved]
  (let [message (.-message ^js solved)]
    (if (seq message)
      {:status :infeasible
       :solver :quadprog
       :reason :solver-message
       :message message}
      (let [solution (vec (js->clj (.slice (.-solution ^js solved) 1)))]
        {:status :solved
         :solver :quadprog
         :weights solution
         :objective-value (objective-value problem solution)
         :iterations (second (js->clj (.-iterations solved)))}))))

(defn solve-with-quadprog
  [problem]
  (if-let [unsupported (unsupported-split-constraints problem)]
    (unsupported-result :split-variable-constraints-not-implemented
                        {:constraints (vec unsupported)})
    (let [n (count (:instrument-ids problem))
          {:keys [columns meq]} (constraint-columns problem)
          dmat (one-indexed-matrix (add-diagonal-epsilon (:quadratic problem)))
          dvec (one-indexed-vector (mapv - (:linear problem)))
          amat (one-indexed-matrix (quadprog-amat n columns))
          bvec (one-indexed-vector (mapv :bound columns))
          solved (.solveQP quadprog dmat dvec amat bvec meq)]
      (normalize-quadprog-solution problem solved))))

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
  [problem solution]
  (let [weights (vec (js->clj solution))]
    {:status :solved
     :solver :osqp
     :weights weights
     :objective-value (objective-value problem weights)}))

(defn solve-with-osqp
  [problem]
  (if-let [unsupported (unsupported-split-constraints problem)]
    (js/Promise.resolve
     (unsupported-result :split-variable-constraints-not-implemented
                         {:constraints (vec unsupported)}))
    (-> (.setup OSQP (osqp-options problem) (osqp-settings))
        (.then (fn [^js solver]
                 (try
                   (let [solution (.solve solver)]
                     (normalize-osqp-solution problem solution))
                   (finally
                     (.cleanup solver)))))
        (.catch (fn [err]
                  {:status :error
                   :solver :osqp
                   :reason :solver-error
                   :message (str err)})))))
