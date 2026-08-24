(ns hyperopen.portfolio.optimizer.domain.risk-covariance-parity-test
  "Bit-exact parity oracle for the pairwise sample covariance in domain/risk.

  `covariance-matrix` was ported from a nested `mapv` over `math/sample-
  covariance` to hoisted means plus flat Float64Array loops. Two of the four
  user-selectable risk models route through it unconditionally, and the product
  default routes through it whenever Ledoit-Wolf reports ragged input, so it is
  a live estimator rather than a fallback -- an answer that shifts in the last
  bits changes the covariance the optimizer solves against.

  The whole risk of this port is in the degenerate cases, because the estimator
  treats holes ASYMMETRICALLY and it does so on purpose:

    - `math/mean` filters non-finite values out and divides by the count of
      what survived.
    - The covariance sum does not filter, and divides by the FULL length minus
      one.
    - Unequal-length series fail the guard, yield nil, and are turned into an
      exact 0 off-diagonal by risk.cljs -- while both diagonals stay correct.
    - `(or ... 0)` catches nil but NOT NaN, because NaN is truthy, so a NaN
      cell reaches the matrix verbatim.

  Every one of those is easy to 'clean up' during a port and each would change
  real results, so this namespace compares bitwise against a verbatim copy of
  the pre-port implementation rather than by tolerance, and spends most of its
  fixtures on holes and ragged input. It lives apart from risk_test.cljs
  because that namespace is at 476 lines against a 500-line cap."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.bit-parity :as bit-parity]
            [hyperopen.portfolio.optimizer.domain.math :as math]
            [hyperopen.portfolio.optimizer.domain.risk :as risk]))

;; ---------------------------------------------------------------------------
;; The oracle: domain/risk.cljs `covariance-matrix` exactly as it stood before
;; the port. Do not tidy it -- being a frozen copy is the whole point.
;; ---------------------------------------------------------------------------

(defn- reference-covariance-matrix
  [series periods-per-year]
  (mapv (fn [xs]
          (mapv (fn [ys]
                  (* periods-per-year
                     (or (math/sample-covariance xs ys) 0)))
                series))
        series))

(defn- parity!
  [label series]
  (doseq [periods-per-year [365 252 1]]
    (let [expected (reference-covariance-matrix series periods-per-year)
          actual (risk/covariance-matrix series periods-per-year)]
      ;; bit=, not =, because this estimator propagates NaN by design and `=`
      ;; reports two NaNs as different -- it fails comparing the reference to
      ;; itself. See the bit-parity namespace.
      (is (bit-parity/bit= expected actual)
          (str "covariance-matrix diverged from the pre-port reference on "
               label " at periods-per-year=" periods-per-year ": "
               (bit-parity/first-difference expected actual))))))

(defn- lcg
  [seed]
  (let [state (atom seed)]
    (fn []
      (swap! state (fn [s] (mod (+ (* 1664525 s) 1013904223) 4294967296)))
      (- (/ @state 4294967296) 0.5))))

(defn- returns
  [n observations seed]
  (let [rand (lcg seed)]
    (mapv (fn [_] (mapv (fn [_] (* 0.05 (rand))) (range observations)))
          (range n))))

(deftest covariance-matches-the-reference-on-rectangular-series-test
  (doseq [[n observations] [[1 30] [2 30] [5 120] [12 260] [30 60]]]
    (parity! (str n " series x " observations " observations")
             (returns n observations (+ 3 n observations)))))

(deftest covariance-matches-the-reference-on-ragged-series-test
  ;; Unequal lengths must produce an exact 0 off-diagonal while both diagonals
  ;; stay correct. This is the shape the volatility-integrity work made common:
  ;; return_plausibility now REJECTS implausible bars at ingestion rather than
  ;; winsorizing them, so series genuinely arrive at different lengths.
  (parity! "one short series" [[0.1 0.2 0.3 0.4] [0.1 0.2 0.3] [0.2 0.1 0.4 0.3]])
  (parity! "all different lengths" [[0.1 0.2] [0.1 0.2 0.3] [0.1 0.2 0.3 0.4]])
  (parity! "one empty series" [[0.1 0.2 0.3] [] [0.3 0.2 0.1]])
  (parity! "all empty" [[] [] []])
  (parity! "single observation" [[0.1] [0.2]])
  (parity! "single observation against a longer one" [[0.1] [0.2 0.3]])
  (parity! "no series at all" []))

(deftest covariance-matches-the-reference-on-non-finite-holes-test
  ;; The asymmetry lives here. `mean` drops these and shrinks its divisor; the
  ;; covariance sum keeps them and divides by the full length. A port that
  ;; computes the mean off the packed array, or that guards the sum, changes
  ;; the answer on exactly this data.
  (parity! "nil hole" [[0.1 nil 0.3 0.2] [0.2 0.1 0.4 0.3]])
  (parity! "nil holes in both" [[0.1 nil 0.3 0.2] [nil 0.1 0.4 0.3]])
  (parity! "NaN hole" [[0.1 js/NaN 0.3 0.2] [0.2 0.1 0.4 0.3]])
  (parity! "infinite hole" [[0.1 js/Infinity 0.3 0.2] [0.2 0.1 0.4 0.3]])
  (parity! "negative infinite hole" [[0.1 js/-Infinity 0.3 0.2] [0.2 0.1 0.4 0.3]])
  (parity! "all holes in one series" [[nil nil nil] [0.2 0.1 0.4]])
  (parity! "all NaN in one series" [[js/NaN js/NaN js/NaN] [0.2 0.1 0.4]])
  (parity! "mixed hole kinds" [[nil js/NaN js/Infinity 0.2] [0.2 0.1 0.4 0.3]]))

(deftest covariance-matches-the-reference-on-degenerate-values-test
  (parity! "zero variance" [[0.2 0.2 0.2 0.2] [0.1 0.3 0.2 0.4]])
  (parity! "all zeros" [[0 0 0] [0 0 0]])
  (parity! "identical series" [[0.1 0.2 0.3] [0.1 0.2 0.3]])
  (parity! "perfectly anticorrelated" [[0.1 0.2 0.3] [-0.1 -0.2 -0.3]])
  (parity! "very large magnitudes" [[1e150 2e150 3e150] [1e150 3e150 2e150]])
  (parity! "very small magnitudes" [[1e-200 2e-200 3e-200] [3e-200 1e-200 2e-200]]))

(deftest covariance-matrix-is-symmetric-test
  ;; The port computes only the upper triangle and mirrors it, which is exact
  ;; because each pair's terms are the same two doubles multiplied in the other
  ;; order and summed in the same order. If that ever stops holding, this fails
  ;; independently of the parity oracle.
  (let [series (returns 8 90 42)
        matrix (risk/covariance-matrix series 365)]
    (is (every? true?
                (for [i (range 8) j (range 8)]
                  (bit-parity/bit= (get-in matrix [i j]) (get-in matrix [j i]))))
        "the mirrored triangle should be bit-identical in both directions")))
