(ns hyperopen.portfolio.optimizer.infrastructure.osqp-solution-classification-test
  "The osqp npm wrapper's solve() returns only the primal vector -- never
  info.status_val -- so infrastructure.osqp used to stamp {:status :solved} on
  every result it got back, including the ones OSQP had declined to solve.

  On a primal-infeasible problem OSQP fills solution->x with OSQP_NAN, and
  problem-adapter's split decode is w_i = x_i - x_(i+n), so that sentinel
  cancels with itself and a declined solve decoded to a vector of exact ZEROS.
  A correct infeasibility diagnosis therefore reached the run as an all-cash
  portfolio, and the post-solve validator reported it as
  'gross-floor expected at least 3.9950 but solver returned 0.0000' -- blaming
  the solver for a request it had read perfectly.

  These are the regressions for the boundary classification that replaced that:
  the pure row check on hand-built arrays, the scoping that keeps that check to
  rows target-selection also re-checks, and an end-to-end pair against the real
  WebAssembly solver on the shape that produced the bug report."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.portfolio.optimizer.infrastructure.osqp :as osqp]))

;; --- the pure row check ----------------------------------------------------

(defn- csc
  "A CSC matrix in the shape .setup is handed, from a dense row-major matrix."
  [matrix]
  (let [n-cols (if (seq matrix) (count (first matrix)) 0)]
    (loop [col 0
           data []
           row-indices []
           column-pointers [0]]
      (if (= col n-cols)
        #js {:data (js/Float64Array.from (clj->js data))
             :row_indices (js/Int32Array.from (clj->js row-indices))
             :column_pointers (js/Int32Array.from (clj->js column-pointers))}
        (let [entries (keep (fn [row]
                              (let [value (get-in matrix [row col])]
                                (when-not (zero? value)
                                  {:row row :value value})))
                            (range (count matrix)))
              data* (into data (map :value entries))]
          (recur (inc col)
                 data*
                 (into row-indices (map :row entries))
                 (conj column-pointers (count data*))))))))

(defn- f64 [values] (js/Float64Array.from (clj->js values)))

(def ^:private osqp-nan
  ;; OSQP's constants.h: `#define OSQP_NAN ((c_float)0x7fc00000)`. A cast, not a
  ;; reinterpretation, so the sentinel is the finite double 2143289344.0.
  2143289344.0)

(deftest a-satisfying-point-reports-no-row-violation-test
  ;; One row, 1 <= x <= 2. Anything inside must come back clean, or every
  ;; feasible solve in the app turns into a failure.
  (let [A (csc [[1]])]
    (doseq [x [1.0 1.5 2.0]]
      (is (nil? (osqp/worst-row-violation A (f64 [x]) (f64 [1.0]) (f64 [2.0]) [:net-band]))
          (str "x=" x " is inside [1, 2] and must not be flagged")))))

(deftest the-tolerance-matches-the-downstream-one-on-checked-rows-test
  ;; The safety argument for the boundary tolerance is that on the rows this
  ;; check actually looks at -- the ones with an exact downstream counterpart --
  ;; it is the SAME 1.0e-5 that
  ;; application.engine.target-selection/solution-tolerance applies, evaluating
  ;; the same linear form against the same constant. (Only summation ORDER
  ;; differs, so the two agree to within rounding rather than bit-for-bit; that
  ;; caveat is why the comment there does not claim a structural identity.) If
  ;; someone tightens the boundary value independently, this is what goes red.
  (let [A (csc [[1]])
        l (f64 [1.0])
        u (f64 [2.0])]
    (testing "just inside the downstream tolerance"
      (is (nil? (osqp/worst-row-violation A (f64 [(+ 2.0 9.0e-6)]) l u [:net-band]))
          "a 9e-6 overshoot is accepted downstream and must be accepted here"))
    (testing "past the downstream tolerance"
      (let [violation (osqp/worst-row-violation A (f64 [(+ 2.0 2.0e-5)]) l u [:net-band])]
        (is (some? violation) "a 2e-5 overshoot is rejected downstream and must be flagged here")
        (is (= :net-band (:constraint-code violation))
            "the row label must survive so the banner can name the control")
        (is (< 1.0e-5 (:amount violation) 3.0e-5))))))

(deftest rows-with-no-downstream-counterpart-are-skipped-test
  ;; The scoping fix. Three row families exist ONLY in the adapted problem --
  ;; the split sign rows p_i >= 0, the turnover difference equalities, and the
  ;; L1 rows sum(p_i + n_i) <= G / sum(tp_i + tn_i) <= T -- so nothing
  ;; downstream re-checks them and this boundary must not adjudicate them
  ;; either. `unchecked-rows` carries their indices.
  (let [A (csc [[1 0] [0 1]])
        ;; row 0: a split gross L1 row, badly breached. row 1: an ordinary
        ;; net-band row, satisfied.
        solution (f64 [9.0 1.5])
        l (f64 [(- 1.0e20) 1.0])
        u (f64 [4.0 2.0])
        codes [:gross-exposure :net-band]]
    (testing "checked, it is flagged"
      (is (= :gross-exposure
             (:constraint-code (osqp/worst-row-violation A solution l u codes)))))
    (testing "listed as unchecked, it is invisible"
      (is (nil? (osqp/worst-row-violation A solution l u codes #{0}))
          "a split-only row breach must not fail a point target-selection would accept"))
    (testing "an unchecked row does not mask a checked one"
      (let [violation (osqp/worst-row-violation A (f64 [9.0 0.0]) l u codes #{0})]
        (is (= :net-band (:constraint-code violation))
            "row 1 is 1.0 short of its floor and is still reported")))))

(deftest a-lower-bound-miss-is-flagged-test
  ;; The reported failure was a gross FLOOR miss, i.e. the lower side.
  ;;
  ;; This is also the shape of the row check's FALLBACK cover for a declined
  ;; solve. :gross-floor rides the generic :inequalities channel, so its split
  ;; image is a checked row; and under a uniform OSQP_NAN sentinel that image
  ;; evaluates to sum(c_i*S) + sum(-c_i*S) = 0, exactly as the decode cancels to
  ;; zero weights. Measured against the real solver on the reported request: the
  ;; row lands on 0.0 against a floor of 3.995.
  (let [violation (osqp/worst-row-violation (csc [[1]]) (f64 [0.0])
                                            (f64 [3.995]) (f64 [1.0e20])
                                            [:gross-floor])]
    (is (some? violation))
    (is (= :gross-floor (:constraint-code violation)))
    (is (< 3.99 (:amount violation) 4.0))))

(deftest osqp-infinity-is-an-absent-bound-not-a-number-test
  ;; infrastructure.osqp writes unbounded sides as +/-1e20. With the OSQP_NAN
  ;; sentinel filling x at var-count 400, A*x reaches ~8.6e11, which a naive
  ;; `l <= Ax <= u` comparison against 1e20 would wave straight through.
  (is (nil? (osqp/worst-row-violation (csc [[1]]) (f64 [8.6e11])
                                      (f64 [-1.0e20]) (f64 [1.0e20])
                                      [:unbounded]))
      "a row with no finite bound can never be violated"))

(deftest the-osqp-nan-sentinel-is-flagged-against-a-finite-row-test
  ;; The sentinel is a finite double, so nothing about it is self-evidently
  ;; wrong to a numeric check -- a row is flagged only because the sentinel
  ;; misses a real bound.
  ;;
  ;; Note what this does and does not prove. The row it misses by ten decades in
  ;; production is the split gross L1 row, which is now UNCHECKED, so this
  ;; asserts the arithmetic rather than the production path. The scoped fallback
  ;; for a declined solve is the one in `a-lower-bound-miss-is-flagged-test`,
  ;; and it only bites when some constraint excludes w = 0 -- which is why
  ;; `declined-solution?`, not this, is the primary detector.
  (let [violation (osqp/worst-row-violation (csc [[1]]) (f64 [osqp-nan])
                                            (f64 [-1.0e20]) (f64 [4.0])
                                            [:net-band])]
    (is (some? violation))
    (is (= :net-band (:constraint-code violation)))
    (is (> (:amount violation) 1.0e9))))

(deftest a-non-finite-row-value-is-flagged-test
  ;; Every comparison against NaN is false, so an unguarded check would report
  ;; a NaN point as satisfying its constraints.
  (is (some? (osqp/worst-row-violation (csc [[1]]) (f64 [js/NaN])
                                       (f64 [1.0]) (f64 [2.0]) [:net-band]))
      "a NaN in A*x is never a satisfied row"))

(deftest the-worst-row-wins-test
  ;; Two independent rows, both missed; the message names the bigger miss.
  (let [A (csc [[1 0] [0 1]])
        violation (osqp/worst-row-violation A (f64 [0.0 0.0])
                                            (f64 [0.5 3.995]) (f64 [1.0e20 1.0e20])
                                            [:net-band :gross-floor])]
    (is (= :gross-floor (:constraint-code violation))
        "3.995 short beats 0.5 short")
    (is (= 1 (:index violation)))))

(def ^:private long-count 18)
(def ^:private short-count 2)
(def ^:private universe-size (+ long-count short-count))

(defn- covariance
  [n]
  (mapv (fn [i]
          (mapv (fn [j] (if (= i j) 0.04 (/ 0.01 (inc (js/Math.abs (- i j)))))) (range n)))
        (range n)))

(defn- signs
  []
  (vec (concat (repeat long-count 1) (repeat short-count -1))))

(defn- reported-problem
  "The shape from the bug report, verbatim.

  A fully single-signed universe: 18 long-side assets in [0, 0.35] and 2
  short-side assets in [-0.03, 0], whose history-assumption caps bound the
  entire short book at 0.06 of NAV. On a fixed-sign box net = gross - 2*short,
  so a 3.995 gross floor forces net >= 3.875, while the net band admits at most
  1.3906. Every constraint is individually satisfiable; no portfolio satisfies
  them together.

  `net-band` is the one thing the caller varies, so the same shape can prove
  both that infeasibility is reported and that the check is not over-tight."
  [[net-lower net-upper]]
  {:kind :quadratic-program
   :objective-kind :min-variance
   :instrument-ids (mapv #(str "perp:" %) (range universe-size))
   :quadratic (covariance universe-size)
   :linear (vec (repeat universe-size 0))
   :return-tilt 0
   :equalities []
   :inequalities [{:code :gross-floor
                   :coefficients (signs)
                   :lower 3.995}
                  {:code :net-band
                   :coefficients (vec (repeat universe-size 1))
                   :lower net-lower
                   :upper net-upper}]
   :l1-constraints [{:code :gross-exposure
                     :requires-split-variables? true
                     :max 4.0}]
   :lower-bounds (vec (concat (repeat long-count 0)
                              (repeat short-count -0.03)))
   :upper-bounds (vec (concat (repeat long-count 0.35)
                              (repeat short-count 0)))})

(defn- long-only-problem
  "The plainest production shape there is: a net equality and a long-only box,
  no L1 constraint, so adapt-problem leaves it alone and NO split rows exist."
  [n]
  {:kind :quadratic-program
   :objective-kind :min-variance
   :instrument-ids (mapv #(str "perp:" %) (range n))
   :quadratic (covariance n)
   :linear (vec (repeat n 0))
   :return-tilt 0
   :equalities [{:code :net-exposure
                 :coefficients (vec (repeat n 1))
                 :target 1}]
   :inequalities []
   :l1-constraints []
   :lower-bounds (vec (repeat n 0))
   :upper-bounds (vec (repeat n 0.5))})

;; --- which rows the boundary is allowed to adjudicate ----------------------

(deftest split-only-rows-are-marked-unchecked-test
  ;; `unchecked-rows` is built where `split?` is known, because it cannot be
  ;; recovered from the rows afterwards: on the split path the bound rows come
  ;; from problem-adapter's all-zero :lower-bounds and are the sign constraints
  ;; p_i >= 0, while on the unsplit path the identical shape is the caller's
  ;; real weight box, which target-selection DOES re-check.
  (testing "the split path"
    (let [{:keys [row-codes unchecked-rows]}
          (osqp/build-static-parts-uncached (reported-problem [1.25 1.3906]))
          unchecked-codes (frequencies (map (partial nth row-codes) unchecked-rows))]
      (is (= 84 (count row-codes)) "1 gross floor + 2 net band + 40 weight bounds + 1 L1 + 40 sign rows")
      (is (= 41 (count unchecked-rows)) "40 sign rows and the one split gross L1 row")
      (is (= {:split-variable-sign 40 :gross-exposure 1} unchecked-codes))
      (testing "every row of an original constraint stays checked"
        (is (empty? (filter (fn [idx]
                              (contains? #{:gross-floor :net-band
                                           :weight-lower-bound :weight-upper-bound}
                                         (nth row-codes idx)))
                            unchecked-rows))))))
  (testing "the restricted check still catches the reported failure"
    ;; The reason scoping the check was safe. Feed the REAL row layout of the
    ;; reported request the all-OSQP_NAN vector OSQP actually returns for it,
    ;; with the real `unchecked-rows` applied, and a violation must still come
    ;; back -- so cover does not rest on the sentinel branch alone.
    ;;
    ;; It survives because the split image of an original constraint cancels
    ;; under a uniform sentinel exactly as the decode does: every checked row
    ;; evaluates to 0, and 0 misses the 3.995 gross floor. The split gross L1
    ;; row, which misses by 8.6e10, is the one that is now skipped.
    (let [{:keys [A l u row-codes unchecked-rows]}
          (osqp/build-static-parts-uncached (reported-problem [1.25 1.3906]))
          sentinel (f64 (repeat (* 2 universe-size) osqp-nan))
          violation (osqp/worst-row-violation A sentinel l u row-codes unchecked-rows)]
      (is (some? violation) "a declined solve must still fail the row check")
      (is (= :gross-floor (:constraint-code violation)))
      (is (zero? (:value violation)) "the split gross-floor row cancels to 0 under the sentinel")
      (is (< 3.99 (:amount violation) 4.0))))
  (testing "the unsplit path leaves every row checked"
    (let [{:keys [row-codes unchecked-rows]}
          (osqp/build-static-parts-uncached (long-only-problem 8))]
      (is (= [:net-exposure :weight-bound :weight-bound :weight-bound :weight-bound
              :weight-bound :weight-bound :weight-bound :weight-bound]
             row-codes)
          "no split rows exist, so the bound rows are the real weight box")
      (is (empty? unchecked-rows)))))

;; --- end to end, against the real solver -----------------------------------


(deftest an-infeasible-request-is-reported-as-infeasible-not-solved-test
  (async done
    (-> (osqp/solve (reported-problem [1.25 1.3906]))
        (.then
         (fn [result]
           (is (= :infeasible (:status result))
               (str "OSQP diagnosed this request as primal infeasible; the boundary"
                    " must say so rather than stamping :solved. Got: "
                    (pr-str (select-keys result [:status :solver :reason]))))
           (is (= :osqp (:solver result))
               "a classified infeasibility must not be relabelled as a quadprog fallback")
           (is (= :solver-primal-infeasible (:reason result)))
           ;; THE regression that matters. The old code decoded OSQP's all-NaN
           ;; sentinel through w_i = x_i - x_(i+n), the sentinel cancelled with
           ;; itself, and downstream was handed a fabricated all-zero portfolio
           ;; that got reported as "solver returned 0.0000".
           (is (nil? (:weights result))
               (str "a declined solve must carry NO weight vector, but got: "
                    (pr-str (:weights result))))
           (is (vector? (get-in result [:details :violations]))
               "the shape must match :constraint-presolve so the infeasible panel can read it")
           (done)))
        (.catch (fn [err]
                  (is false (str "the solve threw rather than classifying: " err))
                  (done))))))

(deftest the-same-shape-with-a-reachable-net-band-still-solves-test
  ;; The companion to the test above, and the guard against an over-tight
  ;; boundary check: widen the net band so gross and net are jointly reachable
  ;; and the identical shape must still come back :solved with real weights.
  (async done
    (let [problem (reported-problem [3.8 4.0])]
      (-> (osqp/solve problem)
          (.then
           (fn [result]
             (is (= :solved (:status result))
                 (str "a feasible request must still solve. Got: "
                      (pr-str (select-keys result [:status :reason :message]))))
             (is (= universe-size (count (:weights result))))
             (is (every? #(js/Number.isFinite %) (:weights result))
                 "the sentinel must not be reaching decode on a feasible solve")
             (let [weights (:weights result)
                   gross (reduce + 0 (map * (signs) weights))
                   net (reduce + 0 weights)]
               ;; Measured against the real solver on this exact shape: gross
               ;; lands on its 3.995 floor and net on 3.875, with a worst row
               ;; residual of 6.3e-9 -- three decades inside the boundary
               ;; tolerance, so this is nowhere near a knife edge.
               (is (<= (- 3.995 1.0e-4) gross (+ 4.0 1.0e-4))
                   (str "gross should sit between its 3.995 floor and its 4.0 cap, got " gross))
               (is (<= (- 3.8 1.0e-4) net (+ 4.0 1.0e-4))
                   (str "net should sit inside the widened band, got " net))
               (is (every? (fn [[w lower upper]] (and (<= (- lower 1.0e-5) w)
                                                      (<= w (+ upper 1.0e-5))))
                           (map vector
                                weights
                                (:lower-bounds problem)
                                (:upper-bounds problem)))
                   "every weight should respect its own box"))
             (done)))
          (.catch (fn [err]
                    (is false (str "the solve threw: " err))
                    (done)))))))

(deftest an-ordinary-long-only-request-is-unaffected-test
  ;; Regressions in the classification would show up on the plainest shape
  ;; before they showed up anywhere interesting.
  (async done
    (let [problem (long-only-problem 8)]
      (-> (osqp/solve problem)
          (.then
           (fn [result]
             (is (= :solved (:status result))
                 (str "got " (pr-str (select-keys result [:status :reason :message]))))
             (is (< (js/Math.abs (- 1.0 (reduce + 0 (:weights result)))) 1.0e-5)
                 "the net equality should still be satisfied to solver precision")
             (is (every? #(<= -1.0e-6 % (+ 0.5 1.0e-6)) (:weights result))
                 "every weight should still respect its box")
             (done)))
          (.catch (fn [err]
                    (is false (str "the solve threw: " err))
                    (done)))))))
