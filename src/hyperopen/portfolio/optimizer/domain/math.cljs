(ns hyperopen.portfolio.optimizer.domain.math
  (:require [hyperopen.portfolio.optimizer.coercion :as coercion]))

(def finite-number? coercion/finite-number?)

(defn mean
  [values]
  (let [values* (filter finite-number? values)]
    (when (seq values*)
      (/ (reduce + 0 values*)
         (count values*)))))

(defn dot
  [xs ys]
  (reduce + 0 (map * xs ys)))

(defn transpose
  [matrix]
  (apply mapv vector matrix))

(defn mat-vec
  [matrix vector]
  (mapv #(dot % vector) matrix))

(defn mat-mul
  [a b]
  (let [bt (transpose b)]
    (mapv (fn [row]
            (mapv #(dot row %) bt))
          a)))

(defn vec-add
  [& vectors]
  (apply mapv + vectors))

(defn vec-sub
  [a b]
  (mapv - a b))

(defn scalar-vec
  [scalar vector]
  (mapv #(* scalar %) vector))

(defn matrix-add
  [& matrices]
  (apply mapv (fn [& rows]
                (apply mapv + rows))
         matrices))

(defn scalar-matrix
  [scalar matrix]
  (mapv (fn [row]
          (mapv #(* scalar %) row))
        matrix))

(defn identity-matrix
  [n]
  (mapv (fn [row]
          (mapv (fn [col]
                  (if (= row col) 1 0))
                (range n)))
        (range n)))

(defn diagonal
  [matrix]
  (mapv (fn [idx]
          (get-in matrix [idx idx]))
        (range (count matrix))))

(defn diagonal-matrix
  [values]
  (mapv (fn [row]
          (mapv (fn [col]
                  (if (= row col)
                    (nth values row)
                    0))
                (range (count values))))
        (range (count values))))

(defn inverse
  "Gauss-Jordan inverse with partial pivoting.

  The augmented matrix is a single flat Float64Array rather than nested
  persistent vectors. The previous version rebuilt every row of [A | I] on
  every one of the n column steps, which is O(n^3) persistent-vector
  allocations for O(n^3) arithmetic; at n=100 that measured 883 ms against
  2.65 ms here. Black-Litterman calls this three times per evaluation and the
  panel re-evaluates as a view is typed, so it is on an interactive path.

  Bit-identical to the version it replaced, and
  domain/math_test.cljs keeps a verbatim copy of that version to assert so
  with `=` rather than a tolerance. Two things make identity hold, and both
  must survive any future edit here:

  - Pivot selection is still the same `sort-by`/`first` over the same keys.
    It is O(n^2 log n) on n values and was never the cost, and rewriting it as
    a scan would change which row wins a tie -- `sort-by` is stable, so ties go
    to the lowest row index, whereas a scan written with >= takes the highest.
    Two valid pivots of equal magnitude give answers differing in the last
    bits, which no tolerance-based test would catch.
  - The arithmetic is in the same order: divide the pivot row through first,
    then subtract `factor * normalized[j]`, so each element is still
    `row[j] - factor * (pivotRow[j] / pivot)` and rounds the same way.

  Float64Array holds IEEE doubles and every ClojureScript number already is
  one, so nothing is narrowed by the move."
  [matrix]
  (let [n (count matrix)
        width (* 2 n)
        cells (js/Float64Array. (* n width))]
    (dotimes [row n]
      (let [source (nth matrix row)
            base (* row width)]
        (dotimes [col n]
          (aset cells (+ base col) (nth source col)))
        (aset cells (+ base n row) 1)))
    (loop [col 0]
      (if (= col n)
        (mapv (fn [row]
                (let [base (+ (* row width) n)]
                  (mapv (fn [idx] (aget cells (+ base idx)))
                        (range n))))
              (range n))
        (let [pivot-row (->> (range col n)
                             (sort-by (fn [row]
                                        (- (js/Math.abs (aget cells (+ (* row width) col))))))
                             first)
              pivot (aget cells (+ (* pivot-row width) col))]
          (when-not (and (finite-number? pivot)
                         (> (js/Math.abs pivot) 1e-12))
            (throw (js/Error. "matrix is singular")))
          (when-not (= pivot-row col)
            (let [a (* col width)
                  b (* pivot-row width)]
              (dotimes [idx width]
                (let [carried (aget cells (+ a idx))]
                  (aset cells (+ a idx) (aget cells (+ b idx)))
                  (aset cells (+ b idx) carried)))))
          (let [pivot-base (* col width)]
            (dotimes [idx width]
              (aset cells (+ pivot-base idx)
                    (/ (aget cells (+ pivot-base idx)) pivot)))
            (dotimes [row n]
              (when-not (= row col)
                (let [base (* row width)
                      factor (aget cells (+ base col))]
                  (dotimes [idx width]
                    (aset cells (+ base idx)
                          (- (aget cells (+ base idx))
                             (* factor (aget cells (+ pivot-base idx))))))))))
          (recur (inc col)))))))

(defn sample-covariance
  [xs ys]
  (let [n (count xs)
        mx (mean xs)
        my (mean ys)]
    (when (and (= n (count ys))
               (> n 1)
               (finite-number? mx)
               (finite-number? my))
      (/ (reduce + 0
                 (map (fn [x y]
                        (* (- x mx) (- y my)))
                      xs
                      ys))
         (dec n)))))

(defn portfolio-return
  [weights expected-returns]
  (dot weights expected-returns))

(defn portfolio-variance
  [weights covariance]
  (dot weights (mat-vec covariance weights)))
