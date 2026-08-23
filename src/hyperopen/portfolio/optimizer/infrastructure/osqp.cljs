(ns hyperopen.portfolio.optimizer.infrastructure.osqp
  (:require ["osqp" :default OSQP]
            [hyperopen.portfolio.optimizer.infrastructure.fallback :as fallback]
            [hyperopen.portfolio.optimizer.infrastructure.problem-adapter :as problem-adapter]
            [hyperopen.portfolio.optimizer.infrastructure.quadprog :as quadprog]))

(def ^:private osqp-infinity
  1.0e20)

(defn- float64-array
  [values]
  (js/Float64Array.from (clj->js values)))

(defn- int32-array
  [values]
  (js/Int32Array.from (clj->js values)))

(defn dense->csc
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

(defn- rows
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

(def ^:private static-cache-limit
  ;; A run plans at most a constrained and an unconstrained sweep, so two
  ;; entries cover it. The worker is torn down and recreated per run
  ;; (infrastructure/run_bridge.cljs), so nothing here outlives a run.
  2)

(defonce ^:private static-cache
  ;; P, A, l and u are rebuilt identically for every solve in a frontier sweep
  ;; -- measured at one distinct P and two distinct A across 56 solves -- and
  ;; building them is most of the cost of a working OSQP solve: dense->csc
  ;; walks the whole dense matrix with get-in, and `rows` allocates a full
  ;; unit-row per bound. Only the linear term actually varies between points.
  (atom []))

(defn- structural-key
  "Everything that determines P, A, l and u. A sweep varies only :linear and
  :return-tilt, and neither reaches any of them.

  Compared by value, but the expensive members -- the covariance and the bound
  vectors -- are the same objects across a sweep, so the equality check
  short-circuits on identity. The constraint rows are rebuilt per point but are
  small, so walking them is far cheaper than rebuilding the matrices."
  [problem]
  [(:quadratic problem)
   (:instrument-ids problem)
   (:lower-bounds problem)
   (:upper-bounds problem)
   (:equalities problem)
   (:inequalities problem)
   (:l1-constraints problem)])

(defn- build-static-parts
  [problem]
  (let [{adapted :problem decode :decode var-count :var-count}
        (problem-adapter/adapt-problem problem)
        constraint-rows (rows adapted)]
    {:P (dense->csc (problem-adapter/add-diagonal-epsilon (:quadratic adapted))
                    {:upper-triangle? true})
     :A (dense->csc (mapv :coefficients constraint-rows)
                    {:upper-triangle? false})
     :l (float64-array (mapv :lower constraint-rows))
     :u (float64-array (mapv :upper constraint-rows))
     :decode decode
     :var-count var-count}))

(defn static-parts
  "Cached P/A/l/u plus the decode fn and variable layout for `problem`.
  Public so a test can assert the cached result is identical to a freshly
  built one."
  [problem]
  (let [cache-key (structural-key problem)]
    (or (some (fn [[k v]] (when (= k cache-key) v)) @static-cache)
        (let [built (build-static-parts problem)]
          (swap! static-cache
                 (fn [entries]
                   (vec (take static-cache-limit (cons [cache-key built] entries)))))
          built))))

(defn build-static-parts-uncached
  "Escape hatch for tests that need an unmemoized baseline to compare against."
  [problem]
  (build-static-parts problem))

(defn- settings
  []
  #js {:verbose false
       :eps_abs 0.00000001
       :eps_rel 0.00000001
       :polish true
       :max_iter 10000})

(defn- normalize-solution
  [problem solution decode]
  (let [weights (decode (vec (js->clj solution)))]
    {:status :solved
     :solver :osqp
     :weights weights
     :objective-value (problem-adapter/objective-value problem weights)}))

(defonce ^:private solve-chain
  ;; The osqp npm package backs every OSQP instance with one shared WASM
  ;; module and heap. The display-frontier sweep solves its points through
  ;; js/Promise.all, so without coordination several .setup/.solve/.cleanup
  ;; cycles race on that one shared heap. That race leaves the module in a
  ;; state where unrelated later solves throw and silently fall back to
  ;; quadprog. Serialize every OSQP solve through a single promise chain so
  ;; only one runs against the shared module at a time.
  (atom (js/Promise.resolve)))

(defn- run-serialized
  [work]
  (let [result (.then @solve-chain work work)]
    ;; Keep the chain alive whether this solve fulfils or rejects, so one bad
    ;; solve never blocks the queue.
    (reset! solve-chain (.then result (fn [_] nil) (fn [_] nil)))
    result))

(defn- solve-on-shared-module
  [problem]
  (let [{:keys [P A l u decode var-count]} (static-parts problem)
        options #js {:P P
                     :A A
                     :q (float64-array (problem-adapter/adapt-linear problem var-count))
                     :l l
                     :u u}]
    (-> (.setup OSQP options (settings))
        (.then (fn [^js solver]
                 (try
                   (let [solution (.solve solver)]
                     (normalize-solution problem solution decode))
                   (finally
                     (.cleanup solver)))))
        (.catch (fn [err]
                  (fallback/recover-osqp-error problem err quadprog/solve))))))

(defn solve
  [problem]
  (if-let [unsupported (problem-adapter/unsupported-l1-constraints problem)]
    (js/Promise.resolve
     (problem-adapter/unsupported-result :invalid-l1-constraints
                                         {:constraints (vec unsupported)}))
    (run-serialized (fn [_] (solve-on-shared-module problem)))))
