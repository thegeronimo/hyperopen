(ns hyperopen.portfolio.metrics.math
  (:require [hyperopen.portfolio.metrics.parsing :as parsing]))

(defn mean
  [values]
  (when (seq values)
    (/ (reduce + 0 values)
       (count values))))

(defn sample-variance
  [values]
  (let [n (count values)
        avg (mean values)]
    (when (and (number? avg)
               (> n 1))
      (/ (reduce + 0
                 (map (fn [value]
                        (let [delta (- value avg)]
                          (* delta delta)))
                      values))
         (dec n)))))

(defn sample-stddev
  [values]
  (when-let [variance (sample-variance values)]
    (js/Math.sqrt variance)))

(defn pearson-correlation
  [xs ys]
  (let [n (count xs)]
    (when (and (= n (count ys))
               (> n 1))
      (let [mx (mean xs)
            my (mean ys)
            cov (reduce + 0
                        (map (fn [x y]
                               (* (- x mx) (- y my)))
                             xs ys))
            sx (reduce + 0
                       (map (fn [x]
                              (let [delta (- x mx)]
                                (* delta delta)))
                            xs))
            sy (reduce + 0
                       (map (fn [y]
                              (let [delta (- y my)]
                                (* delta delta)))
                            ys))
            denom (js/Math.sqrt (* sx sy))]
        (when (and (parsing/finite-number? denom)
                   (pos? denom))
          (/ cov denom))))))

(defn sample-skewness
  [returns]
  (let [values (vec returns)
        n (count values)]
    (when (> n 2)
      (let [avg (mean values)
            centered (mapv #(- % avg) values)
            m2 (/ (reduce + 0 (map #(* % %) centered)) n)
            m3 (/ (reduce + 0 (map #(* % % %) centered)) n)]
        (when (pos? m2)
          (let [g1 (/ m3 (js/Math.pow m2 1.5))]
            (* (/ (js/Math.sqrt (* n (dec n)))
                  (- n 2))
               g1)))))))

(defn skew
  [returns]
  (sample-skewness returns))

(defn sample-kurtosis-excess
  [returns]
  (let [values (vec returns)
        n (count values)]
    (when (> n 3)
      (let [avg (mean values)
            centered (mapv #(- % avg) values)
            m2 (/ (reduce + 0 (map #(* % %) centered)) n)
            m4 (/ (reduce + 0 (map #(js/Math.pow % 4) centered)) n)]
        (when (pos? m2)
          (let [g2 (- (/ m4 (* m2 m2)) 3)]
            (* (/ (dec n)
                  (* (- n 2) (- n 3)))
               (+ (* (inc n) g2) 6))))))))

(defn kurtosis
  [returns]
  (sample-kurtosis-excess returns))

(defn horner
  [x coeffs]
  (reduce (fn [acc c]
            (+ (* acc x) c))
          0
          coeffs))

(defn erf
  [x]
  (let [sign (if (neg? x) -1 1)
        x* (js/Math.abs x)
        a1 0.254829592
        a2 -0.284496736
        a3 1.421413741
        a4 -1.453152027
        a5 1.061405429
        p 0.3275911
        t (/ 1 (+ 1 (* p x*)))
        poly (horner t [a5 a4 a3 a2 a1])
        y (- 1 (* poly
                  t
                  (js/Math.exp (- (* x* x*)))))]
    (* sign y)))

(defn normal-cdf
  [x]
  (* 0.5 (+ 1 (erf (/ x (js/Math.sqrt 2))))))

(defn inverse-normal-cdf
  [p]
  (let [p* p
        plow 0.02425
        phigh (- 1 plow)
        a [ -39.69683028665376
            220.9460984245205
            -275.9285104469687
            138.357751867269
            -30.66479806614716
            2.506628277459239]
        b [ -54.47609879822406
            161.5858368580409
            -155.6989798598866
            66.80131188771972
            -13.28068155288572]
        c [ -0.007784894002430293
            -0.3223964580411365
            -2.400758277161838
            -2.549732539343734
            4.374664141464968
            2.938163982698783]
        d [0.007784695709041462
           0.3224671290700398
           2.445134137142996
           3.754408661907416]]
    (cond
      (<= p* 0) js/Number.NEGATIVE_INFINITY
      (>= p* 1) js/Number.POSITIVE_INFINITY
      (< p* plow)
      (let [q (js/Math.sqrt (* -2 (js/Math.log p*)))]
        (/ (horner q c)
           (horner q (conj d 1))))
      (> p* phigh)
      (let [q (js/Math.sqrt (* -2 (js/Math.log (- 1 p*))))]
        (- (/ (horner q c)
              (horner q (conj d 1)))))
      :else
      (let [q (- p* 0.5)
            r (* q q)]
        (/ (* (horner r a) q)
           (horner r (conj b 1)))))))

(defn quantile
  [values q]
  (let [sorted-values (vec (sort values))
        n (count sorted-values)]
    (when (pos? n)
      (if (= n 1)
        (first sorted-values)
        (let [position (* (dec n) q)
              lower-idx (int (js/Math.floor position))
              upper-idx (int (js/Math.ceil position))
              lower (nth sorted-values lower-idx)
              upper (nth sorted-values upper-idx)
              weight (- position lower-idx)]
          (+ lower (* weight (- upper lower))))))))