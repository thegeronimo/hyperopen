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
  ;; The two L1 codes problem-adapter rewrites into split rows of its own
  ;; invention -- sum(p_i + n_i) <= G and sum(tp_i + tn_i) <= T. See
  ;; `worst-row-violation` for why they are not checked there. Guarding on
  ;; `split?` as well as the code is belt and braces: neither code can reach
  ;; `rows` any other way, because every problem builder
  ;; (domain.objectives/l1-constraints, domain.equal-risk-plan) puts them on the
  ;; :l1-constraints channel, which `rows` never reads.
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
                               ;; p_i - n_i - tp_i + tn_i = current_i is invented
                               ;; by adapt-problem; no downstream counterpart.
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
                           {;; Named for what the row IS: on the split path
                            ;; these are p_i >= 0, not weight bounds, which
                            ;; arrive separately from split-bound-inequality.
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
        constraint-rows (rows adapted (some? var-count))]
    {:P (dense->csc (problem-adapter/add-diagonal-epsilon (:quadratic adapted))
                    {:upper-triangle? true})
     :A (dense->csc (mapv :coefficients constraint-rows)
                    {:upper-triangle? false})
     :l (float64-array (mapv :lower constraint-rows))
     :u (float64-array (mapv :upper constraint-rows))
     ;; Row labels, parallel to A/l/u, so the boundary check below can name the
     ;; constraint that a returned point missed instead of printing a row index.
     ;; They are derived from exactly the constraint maps `structural-key`
     ;; already compares, so the cache key needs no change.
     :row-codes (mapv :code constraint-rows)
     ;; Indices of the rows adapt-problem invented, which no downstream check
     ;; re-examines -- see `worst-row-violation`. Empty on the unsplit path.
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
  ;; :eps_prim_inf used to be silently dropped -- the published wrapper passes
  ;; :eps_dual_inf twice into the sixth and seventh slots of its
  ;; _create_settings call. The vendored copy fixes that, so setting it now
  ;; works; before, it was a silent no-op. Nothing here sets either tolerance,
  ;; so both still take their shared 1.0e-4 default.
  ;;
  ;; :adaptive_rho_interval is still not in the wrapper's settings list at all, so
  ;; OSQP's default of 0 applies, which means "choose the interval from elapsed
  ;; time". That makes solve counts and, occasionally, solutions vary run to run
  ;; on identical input. Measured: one point of a 36-point sweep at N=100 lands
  ;; on either of two answers 7.7e-3 apart depending on machine timing. It is
  ;; pre-existing and unrelated to the vendored heap fix, but it is the reason
  ;; any solver comparison here has to allow for run-to-run drift.
  #js {:verbose false
       :eps_abs 0.00000001
       :eps_rel 0.00000001
       :polish true
       :max_iter 10000})

;; --- Why the returned point has to be checked here -------------------------
;;
;; The osqp npm wrapper's solve() reads `work->solution->x` off the WebAssembly
;; heap and returns that primal vector and NOTHING else -- never
;; `info.status_val`, never the residuals, never the status string. So at this
;; boundary a converged optimum and a problem OSQP diagnosed as infeasible come
;; back as the same shape, and this file used to stamp both {:status :solved}.
;;
;; That is not a cosmetic gap. On a primal-infeasible problem OSQP's
;; store_solution fills solution->x with OSQP_NAN, and problem-adapter's split
;; decode is w_i = x_i - x_(i+n), so the sentinel CANCELS WITH ITSELF and a
;; declined solve decodes to exact zeros. A correct infeasibility diagnosis
;; therefore reached the run as a plausible all-cash portfolio, and the only
;; thing that noticed was the post-solve validator in
;; application.engine.target-selection -- which reported it as "gross-floor
;; expected at least 3.9950 but solver returned 0.0000", blaming the solver for
;; a request it had read correctly.
;;
;; The fix is to classify the returned point before normalising it. There are
;; two detectors and they are NOT peers:
;;
;;   1. PRIMARY -- the OSQP_NAN sentinel. Structurally sound: OSQP writes it
;;      into every element of solution->x exactly when it declined to produce a
;;      point. Nothing about the problem shape can weaken it.
;;   2. SECONDARY -- a residual check of the returned point against the
;;      l <= Ax <= u rows that were handed to the solver, restricted to the rows
;;      that have an exact downstream counterpart. This catches an unconverged
;;      iterate, which the sentinel does not cover.
;;
;; Both branches return NO :weights, matching infrastructure.quadprog, which has
;; always returned {:status :infeasible} with no weights. Fabricating a weight
;; vector for a solve that produced none is what caused the bug.
;;
;; Reading the real status is possible -- info sits at workspace byte 104 and
;; status_val at info byte 36, verified stable across 86 solves from n=1 to
;; n=400 -- but it needs a sixth text-anchored edit in tools/optimizer/
;; patch_osqp.mjs guarding two struct offsets no anchor test can protect, and
;; status_val alone is not a sufficient classifier anyway (MAX_ITER can carry
;; either garbage or a usable point).

(def ^:private osqp-nan
  ;; OSQP's constants.h defines `#define OSQP_NAN ((c_float)0x7fc00000)` -- a
  ;; CAST, not a bit reinterpretation, so OSQP_NAN is the perfectly finite
  ;; double 2143289344.0. store_solution writes it into every element of
  ;; solution->x exactly when status_val is OSQP_PRIMAL_INFEASIBLE or
  ;; OSQP_DUAL_INFEASIBLE, i.e. precisely when the solver declined to produce a
  ;; point at all.
  ;;
  ;; This is the PRIMARY detector and the only one whose coverage is
  ;; structural: an all-sentinel x means OSQP declined, full stop. Measured on
  ;; the reported request (18 long and 2 short assets, gross floor 3.995, net
  ;; band [1.25, 1.3906]) every one of the 40 split variables came back as
  ;; exactly this double, and the decode cancelled it to all zeros.
  ;;
  ;; It is coupled to the vendored OSQP version. If a release changes the
  ;; constant, cover falls to the row check below, which on that same request
  ;; still flags :gross-floor (0 against a floor of 3.995). That is a fallback,
  ;; not a guarantee: under a uniform sentinel every split row of an ORIGINAL
  ;; constraint cancels to 0 exactly as the decode does, so the row check
  ;; re-catches a declined solve only when some constraint excludes w = 0. Do
  ;; not weaken this branch on the strength of the one below.
  2143289344.0)

(def ^:private row-residual-tolerance
  ;; Deliberately the SAME 1.0e-5 that
  ;; application.engine.target-selection/solution-tolerance applies to the
  ;; decoded weights, so that on the rows this check actually looks at, the two
  ;; agree by construction rather than by luck.
  ;;
  ;; What that buys, stated exactly. A CHECKED row -- see `worst-row-violation`
  ;; for which those are -- is the split image of an original constraint, so its
  ;; value is sum(c_i*p_i) + sum(-c_i*n_i) = sum(c_i*(p_i - n_i)) =
  ;; sum(c_i*w_i): the same linear form target-selection evaluates, against the
  ;; same constant, with the same strict `miss > tolerance` comparison. The two
  ;; verdicts therefore differ only by floating-point summation ORDER -- this
  ;; walks A's nonzeros column-major, target-selection walks math/dot over
  ;; decoded weights -- so they agree to within rounding, NOT bit-for-bit. A
  ;; point balanced within an ulp of the tolerance could in principle split
  ;; them. That is the honest limit of the claim, not a structural identity.
  ;;
  ;; The measurements do not force the value either. At the settings above,
  ;; across 25 feasible shapes -- the reported 20-asset split shape at four net
  ;; bands, turnover shapes at n = 5/20/50/100 with four caps each, and
  ;; budget-and-box shapes from n=2 to n=400 -- the worst residual on a checked
  ;; row was 2.6e-8 and NONE was rejected, while the failure this exists to
  ;; catch misses :gross-floor by 3.995. That is 390x of headroom, so keep the
  ;; two constants equal: tightening this one independently is what would start
  ;; classifying feasible solves as infeasible.
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
                        ;; A NaN or Infinity in A*x is never a satisfied row, and
                        ;; every comparison against it is false, so name it here.
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
        ;; A classified :infeasible result is RETURNED, not thrown, so it never
        ;; reaches recover-osqp-error and this change suppresses no quadprog
        ;; fallback. That is deliberate. This .catch exists for THROWS -- the
        ;; shared-heap abort the vendored copy fixes -- and routing a
        ;; deterministic infeasibility through it would run a dense
        ;; O(var-count^3) second solve that fails the same way, relabel the run
        ;; :quadprog-fallback and trip the "backup solver used" chip in
        ;; application.engine.solver-health for a request that was never the
        ;; solver's fault. (If insurance against max_iter starvation is ever
        ;; wanted, the discriminator is free: an all-sentinel vector means OSQP
        ;; declined and retrying is pointless, a row violation means it returned
        ;; a real but unconverged iterate.)
        (.catch (fn [err]
                  (fallback/recover-osqp-error problem err quadprog/solve))))))

(defn solve
  [problem]
  (if-let [unsupported (problem-adapter/unsupported-l1-constraints problem)]
    (js/Promise.resolve
     (problem-adapter/unsupported-result :invalid-l1-constraints
                                         {:constraints (vec unsupported)}))
    (run-serialized (fn [_] (solve-on-shared-module problem)))))
