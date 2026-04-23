(ns hyperopen.portfolio.optimizer.domain.math)

(defn finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

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
  [matrix]
  (let [n (count matrix)
        augmented (mapv (fn [row identity-row]
                          (vec (concat row identity-row)))
                        matrix
                        (identity-matrix n))]
    (loop [col 0
           rows augmented]
      (if (= col n)
        (mapv #(subvec % n) rows)
        (let [pivot-row (->> (range col n)
                             (sort-by (fn [row]
                                        (- (js/Math.abs (get-in rows [row col])))))
                             first)
              pivot (get-in rows [pivot-row col])]
          (when-not (and (finite-number? pivot)
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
