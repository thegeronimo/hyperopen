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

(def ^:private split-only-inequality-codes
  #{:gross-exposure :turnover})

(defn- rows
  "The l <= Ax <= u rows for an ALREADY-ADAPTED problem.

  `split?` says whether adapt-problem took the split-variable path, which is
  what decides `:downstream-checked?` -- see `worst-row-violation`. It cannot be
  inferred from the rows themselves: on the split path the bound rows come from
  all-zero lower bounds and are the sign constraints p_i >= 0, while on the
  unsplit path the identical shape is the caller's real weight box."
  [problem split?]
  (let [n (count (:instrument-ids problem))
        equality-rows (mapv (fn [constraint]
                              {:code (:code constraint)
                               :coefficients (:coefficients constraint)
                               :lower (:target constraint)
                               :upper (:target constraint)
                               :downstream-checked?
                               (not (and split?
                                         (= :turnover-difference (:code constraint))))})
                            (:equalities problem))
        inequality-rows (mapcat (fn [constraint]
                                  (let [checked? (not (and split?
                                                           (contains? split-only-inequality-codes
                                                                      (:code constraint))))]
                                    (concat
                                     (when (number? (:lower constraint))
                                       [{:code (:code constraint)
                                         :coefficients (:coefficients constraint)
                                         :lower (:lower constraint)
                                         :upper osqp-infinity
                                         :downstream-checked? checked?}])
                                     (when (number? (:upper constraint))
                                       [{:code (:code constraint)
                                         :coefficients (:coefficients constraint)
                                         :lower (- osqp-infinity)
                                         :upper (:upper constraint)
                                         :downstream-checked? checked?}]))))
                                (:inequalities problem))
        bound-rows (mapv (fn [idx lower upper]
                           {
                            :code (if split? :split-variable-sign :weight-bound)
                            :coefficients (unit-row n idx)
                            :lower (if (number? lower) lower (- osqp-infinity))
                            :upper (if (number? upper) upper osqp-infinity)
                            :downstream-checked? (not split?)})
                         (range n)
                         (:lower-bounds problem)
                         (:upper-bounds problem))]
    (vec (concat equality-rows inequality-rows bound-rows))))

(def ^:private static-cache-limit
  2)

(defonce ^:private static-cache
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
        constraint-rows (rows adapted (some? var-count))]
    {:P (dense->csc (problem-adapter/add-diagonal-epsilon (:quadratic adapted))
                    {:upper-triangle? true})
     :A (dense->csc (mapv :coefficients constraint-rows)
                    {:upper-triangle? false})
     :l (float64-array (mapv :lower constraint-rows))
     :u (float64-array (mapv :upper constraint-rows))
     :row-codes (mapv :code constraint-rows)
     :unchecked-rows (into #{}
                           (keep-indexed (fn [idx row]
                                           (when-not (:downstream-checked? row) idx)))
                           constraint-rows)
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

(def ^:private osqp-nan
  2143289344.0)

(def ^:private row-residual-tolerance
  1.0e-5)

(defn- declined-solution?
  "True when every element of the raw primal vector is OSQP's OSQP_NAN
  sentinel, which is how the solver reports that it produced no point."
  [^js solution]
  (let [n (.-length solution)]
    (and (pos? n)
         (loop [idx 0]
           (cond
             (= idx n) true
             (== osqp-nan (aget solution idx)) (recur (inc idx))
             :else false)))))

(defn- row-values
  "A*x, in row order, for the CSC A that was actually handed to the solver.
  One pass over the nonzeros: 1800 of them and 0.015 ms at N=100 with a
  turnover cap, so this is free next to a solve."
  [^js A ^js solution row-count]
  (let [data (.-data A)
        row-indices (.-row_indices A)
        column-pointers (.-column_pointers A)
        values (js/Float64Array. row-count)]
    (dotimes [col (dec (.-length column-pointers))]
      (let [x (aget solution col)]
        (when-not (zero? x)
          (let [stop (aget column-pointers (inc col))]
            (loop [k (aget column-pointers col)]
              (when (< k stop)
                (let [row (aget row-indices k)]
                  (aset values row (+ (aget values row) (* (aget data k) x))))
                (recur (inc k))))))))
    values))

(defn worst-row-violation
  "How far the worst CHECKED row of A*x falls outside [l, u], or nil when every
  checked row is inside to within `row-residual-tolerance`. `unchecked-rows` is
  a set of row indices to skip; the 5-arity checks every row.

  Public so a unit test can drive it with hand-built arrays and no solver.

  Three things this must get right.

  It runs on the RAW primal vector, before `decode`: A, l and u live in the
  adapted (split) variable space and so does what .solve returns, so checking
  decoded weights here would silently check the wrong thing on every split-path
  problem -- which is every production problem, since a finite gross max always
  attaches a split L1 row.

  It skips the rows adapt-problem INVENTED, which have no downstream
  counterpart and so no shared definition of an acceptable point. Three
  families qualify, all split-path only: the sign rows p_i >= 0 (and n_i, tp_i,
  tn_i), from problem-adapter setting :lower-bounds to (repeat var-count 0);
  the turnover difference equalities; and the two L1 rows named in
  `split-only-inequality-codes`, which are strictly tighter than the |.|-sums
  target-selection re-checks, since |p_i - n_i| <= p_i + n_i. Checking those
  could reject a point the pipeline as a whole considers valid -- an
  unconverged iterate with both legs of one asset non-zero can breach sum(p+n)
  while sum|w| stays inside the cap. Every other row -- the split images of the
  caller's own equalities and inequalities, and the :weight-lower-bound /
  :weight-upper-bound rows, whose value p_i - n_i IS the decoded w_i -- has an
  exact counterpart and is checked. What this gives up is a corrupt iterate
  that misses ONLY an invented row; the sentinel above is the primary detector,
  and target-selection re-validates every original constraint regardless.

  And `osqp-infinity` marks an ABSENT bound rather than a number: a side set to
  +/-1e20 is skipped outright, not compared, so a row with no real constraint
  on it can never be reported -- the sentinel drives A*x to ~8.6e11 at
  var-count 400, and comparing that to a marker is not a constraint check."
  ([^js A ^js solution ^js l ^js u row-codes]
   (worst-row-violation A solution l u row-codes #{}))
  ([^js A ^js solution ^js l ^js u row-codes unchecked-rows]
   (let [row-count (.-length l)
         values (row-values A solution row-count)]
     (loop [idx 0
            worst nil]
       (if (= idx row-count)
         worst
         (let [value (aget values idx)
               lower (aget l idx)
               upper (aget u idx)
               amount (cond
                        (contains? unchecked-rows idx) 0
                        (js/Number.isFinite value)
                        (max (if (> lower (- osqp-infinity)) (- lower value) 0)
                             (if (< upper osqp-infinity) (- value upper) 0))
                        :else js/Number.POSITIVE_INFINITY)]
           (recur (inc idx)
                  (if (and (> amount row-residual-tolerance)
                           (or (nil? worst) (> amount (:amount worst))))
                    {:index idx
                     :constraint-code (get row-codes idx)
                     :value value
                     :lower lower
                     :upper upper
                     :amount amount}
                    worst))))))))

(defn- declined-result
  []
  {:status :infeasible
   :solver :osqp
   :reason :solver-primal-infeasible
   :message "No portfolio can satisfy every constraint in this request at the same time."
   :details {:violations []}})

(defn- outside-constraints-result
  [violation]
  {:status :infeasible
   :solver :osqp
   :reason :solver-solution-outside-constraints
   :message "The solver returned a point that does not satisfy the constraints it was given."
   :details {:violations
             [{:code :solver-boundary-row-violation
               :constraint-code (:constraint-code violation)
               :lower (:lower violation)
               :upper (:upper violation)
               :value (:value violation)
               :message (str (name (or (:constraint-code violation) :constraint))
                             " left its bounds by "
                             (.toExponential (:amount violation) 2)
                             ".")}]}})

(defn- normalize-solution
  "Classifies the raw primal vector before decoding it. Only the last arm ever
  produces :weights: a solve that returned no usable point must not be handed
  downstream wearing a fabricated one."
  [problem solution decode {:keys [A l u row-codes unchecked-rows]}]
  (if (declined-solution? solution)
    (declined-result)
    (if-let [violation (worst-row-violation A solution l u row-codes unchecked-rows)]
      (outside-constraints-result violation)
      (let [weights (decode (vec (js->clj solution)))]
        {:status :solved
         :solver :osqp
         :weights weights
         :objective-value (problem-adapter/objective-value problem weights)}))))

(defonce ^:private solve-chain
  (atom (js/Promise.resolve)))

(defn- run-serialized
  [work]
  (let [result (.then @solve-chain work work)]
    (reset! solve-chain (.then result (fn [_] nil) (fn [_] nil)))
    result))

(defn- solve-on-shared-module
  [problem]
  (let [{:keys [P A l u decode var-count] :as static} (static-parts problem)
        options #js {:P P
                     :A A
                     :q (float64-array (problem-adapter/adapt-linear problem var-count))
                     :l l
                     :u u}]
    (-> (.setup OSQP options (settings))
        (.then (fn [^js solver]
                 (try
                   (let [solution (.solve solver)]
                     (normalize-solution problem solution decode static))
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
