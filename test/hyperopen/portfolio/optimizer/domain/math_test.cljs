(ns hyperopen.portfolio.optimizer.domain.math-test
  "Bit-exact parity oracle for domain/math.

  `inverse` is a Gauss-Jordan elimination that the Black-Litterman posterior
  calls three times per evaluation, and it was ported from nested persistent
  vectors to a flat Float64Array. The port is only safe if it is *identical*,
  not merely close: a view-driven posterior that shifts in the last few bits
  changes recommended weights, and nothing downstream would flag it.

  So this namespace keeps a verbatim copy of the pre-port implementation as
  `reference-inverse` and asserts plain `=` against it -- never a tolerance.
  The same technique pins risk_ledoit_wolf.cljs. The battery deliberately
  includes the cases where a Gauss-Jordan port silently diverges: pivot ties,
  forced row swaps, the exact singularity threshold, and non-finite holes."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.bit-parity :as bit-parity]
            [hyperopen.portfolio.optimizer.domain.math :as math]))

;; ---------------------------------------------------------------------------
;; The oracle: domain/math.cljs `inverse` exactly as it stood before the port.
;; Do not "clean this up" -- its value is that it is a frozen copy.
;; ---------------------------------------------------------------------------

(defn- reference-inverse
  [matrix]
  (let [n (count matrix)
        augmented (mapv (fn [row identity-row]
                          (vec (concat row identity-row)))
                        matrix
                        (math/identity-matrix n))]
    (loop [col 0
           rows augmented]
      (if (= col n)
        (mapv #(subvec % n) rows)
        (let [pivot-row (->> (range col n)
                             (sort-by (fn [row]
                                        (- (js/Math.abs (get-in rows [row col])))))
                             first)
              pivot (get-in rows [pivot-row col])]
          (when-not (and (math/finite-number? pivot)
                         (> (js/Math.abs pivot) 1e-12))
            (throw (js/Error. "matrix is singular")))
          (let [rows* (assoc rows col (nth rows pivot-row)
                             pivot-row (nth rows col))
                normalized-pivot (mapv #(/ % pivot) (nth rows* col))
                eliminated (mapv (fn [row-idx row]
                                   (if (= row-idx col)
                                     normalized-pivot
                                     (let [factor (nth row col)]
                                       (mapv - row (mapv #(* factor %) normalized-pivot)))))
                                 (range n)
                                 rows*)]
            (recur (inc col) eliminated)))))))

(defn- outcome
  "Both implementations either return a matrix or throw. Compare the outcomes
  as data so a case that throws on one side and returns on the other fails
  loudly instead of erroring the run."
  [f matrix]
  (try
    {:result (f matrix)}
    (catch :default e
      {:threw (.-message e)})))

(defn- parity!
  [label matrix]
  (let [expected (outcome reference-inverse matrix)
        actual (outcome math/inverse matrix)]
    ;; bit=, not =, on the :result side. Every non-finite fixture below happens
    ;; to throw today rather than return a NaN-bearing inverse, but that is a
    ;; property of these particular matrices, not of the function -- and under
    ;; plain `=` a future fixture that did return NaN would fail while the two
    ;; implementations agreed. See the bit-parity namespace.
    (is (and (= (:threw expected) (:threw actual))
             (bit-parity/bit= (:result expected) (:result actual)))
        (str "inverse diverged from the pre-port reference on " label ": "
             (or (bit-parity/first-difference (:result expected) (:result actual))
                 (str "threw " (pr-str (:threw expected))
                      " vs " (pr-str (:threw actual))))))))

;; ---------------------------------------------------------------------------
;; Deterministic fixtures
;; ---------------------------------------------------------------------------

(defn- lcg
  [seed]
  (let [state (atom seed)]
    (fn []
      (swap! state (fn [s] (mod (+ (* 1664525 s) 1013904223) 4294967296)))
      (/ @state 4294967296))))

(defn- random-spd
  "A well-conditioned symmetric positive-definite matrix, the shape the
  Black-Litterman path actually inverts."
  [n seed]
  (let [rand (lcg seed)
        factors (mapv (fn [_] (mapv (fn [_] (- (rand) 0.5)) (range n)))
                      (range (* 2 n)))]
    (mapv (fn [i]
            (mapv (fn [j]
                    (+ (reduce + 0 (map (fn [f] (* (nth f i) (nth f j))) factors))
                       (if (= i j) 1.0 0)))
                  (range n)))
          (range n))))

(deftest inverse-matches-the-reference-on-well-conditioned-matrices-test
  (doseq [n [1 2 3 5 8 20 40]]
    (parity! (str "identity " n) (math/identity-matrix n))
    (parity! (str "random SPD " n) (random-spd n (+ 7 n)))))

(deftest inverse-matches-the-reference-when-a-row-swap-is-forced-test
  ;; A zero in the pivot position makes the partial-pivot search pick a later
  ;; row. A port that forgets the swap, or swaps only the left half of the
  ;; augmented matrix, diverges here and nowhere else.
  (parity! "zero leading pivot" [[0 1] [1 0]])
  (parity! "zero pivot mid-elimination" [[1 2 3] [2 4 7] [3 5 3]])
  (parity! "anti-diagonal" [[0 0 1] [0 1 0] [1 0 0]]))

(deftest inverse-matches-the-reference-on-pivot-ties-test
  ;; Two candidate rows with identical |pivot|. The reference resolves this
  ;; with a stable sort-by followed by `first`, so it takes the LOWEST row
  ;; index. A scan written with >= instead of > takes the highest, and the two
  ;; answers differ only in the last bits -- invisible to any tolerance-based
  ;; assertion, which is why this case is here.
  (parity! "equal magnitudes" [[1 2] [1 3]])
  (parity! "opposite signs, equal magnitude" [[1 2] [-1 3]])
  (parity! "three-way tie" [[1 2 3] [1 5 4] [1 7 9]])
  (parity! "tie deeper in the elimination" [[2 1 1] [1 3 2] [1 3.0000001 5]]))

(deftest inverse-matches-the-reference-at-the-singularity-threshold-test
  ;; The guard is `(> (abs pivot) 1e-12)`, strictly. Exactly 1e-12 must throw.
  (parity! "pivot exactly at the threshold" [[1e-12 0] [0 1]])
  (parity! "pivot just below the threshold" [[9e-13 0] [0 1]])
  (parity! "pivot just above the threshold" [[1.1e-12 0] [0 1]])
  (parity! "singular: duplicate rows" [[1 2] [1 2]])
  (parity! "singular: zero row" [[0 0] [1 1]])
  (parity! "singular: all zeros" [[0 0] [0 0]])
  (parity! "singular 3x3" [[1 2 3] [2 4 6] [1 1 1]]))

(deftest inverse-matches-the-reference-on-non-finite-input-test
  ;; Covariance entries can be non-finite on degenerate history, and `inverse`
  ;; is called on matrices derived from them. Whatever today's behaviour is --
  ;; throwing, or propagating NaN into the result -- the port must reproduce
  ;; it exactly rather than "improve" it.
  (parity! "NaN pivot" [[js/NaN 1] [1 1]])
  (parity! "NaN off-diagonal" [[1 js/NaN] [1 1]])
  (parity! "positive infinity" [[js/Infinity 1] [1 1]])
  (parity! "negative infinity" [[1 1] [js/-Infinity 1]])
  (parity! "infinity off the pivot column" [[1 js/Infinity] [0 1]]))

(deftest inverse-matches-the-reference-on-degenerate-shapes-test
  (parity! "empty" [])
  (parity! "1x1" [[4]])
  (parity! "1x1 negative" [[-0.25]])
  (parity! "asymmetric" [[1 2 3] [0 1 4] [5 6 0]]))

(deftest inverse-round-trips-to-the-identity-test
  ;; Parity with the reference is the binding contract, but a reference that
  ;; was itself wrong would satisfy it. This is the independent check that the
  ;; answer is an inverse at all.
  (doseq [n [2 3 8 20]]
    (let [m (random-spd n (+ 100 n))
          product (math/mat-mul m (math/inverse m))]
      (is (every? true?
                  (for [i (range n) j (range n)]
                    (< (js/Math.abs (- (get-in product [i j])
                                       (if (= i j) 1.0 0.0)))
                       1.0e-9)))
          (str "A * inverse(A) should be the identity at n=" n)))))
