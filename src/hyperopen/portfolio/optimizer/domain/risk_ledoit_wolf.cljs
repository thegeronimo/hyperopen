(ns hyperopen.portfolio.optimizer.domain.risk-ledoit-wolf
  "Ledoit-Wolf shrinkage toward a scaled-identity target.

  The hot path runs in flat JS loops instead of persistent-vector matrix
  helpers: the original built one n-by-n outer-product matrix PER OBSERVATION
  for the beta estimate, which cost seconds for a mid-size universe. The loop
  order below reproduces the original arithmetic exactly - same operations,
  same left-to-right row-major summation - so results are bit-identical to
  the persistent-vector implementation; only the container types changed.

  `estimate` REPORTS the two states in which its answer is not a risk estimate;
  it does not decide what to do about them. Choosing a replacement covariance is
  a model-dispatch concern and lives in `domain.risk/estimate-risk-model`, which
  already holds the alternatives. Keeping the policy out of here is also what
  lets `risk_ledoit_wolf_test` keep asserting bit-exact parity against an inline
  reference implementation."
  (:require [hyperopen.portfolio.optimizer.domain.math :as math]))

(def saturated-shrinkage-threshold
  "Shrinkage at or above this discards essentially all sample information and
  returns the scaled-identity target: every asset assigned the same variance,
  every correlation zero. Set just below 1 so a near-saturated estimate - nearly
  as degenerate - is caught too.

  Measured on 2026-08-23: clean data never reaches it. Seven real crypto perps
  at 430/120/60/30/15/10/5/3 observations gave 0.020/0.098/0.208/0.387/0.481/
  0.411/0.421/0.218, and synthetic clean data in the regime shrinkage exists for
  (40 assets vs 30 observations, then vs 10) gave 0.894 and 0.806. A single
  corrupt observation reached exactly 1 immediately. Saturation is therefore a
  data-quality alarm, not a normal statistical outcome."
  0.99)

(defn- zero-matrix
  [size]
  (vec (repeat size
               (vec (repeat size 0)))))

(defn- rectangular-series?
  [series]
  (or (empty? series)
      (let [sample-count (count (first series))]
        (every? #(= sample-count (count %)) series))))

(defn- centered-columns
  "JS array of n Float64Arrays (one per instrument, length t-count) holding the
  mean-subtracted observations."
  [series means t-count]
  (let [n (count series)
        columns (js/Array. n)]
    (dotimes [i n]
      (let [xs (vec (nth series i))
            m (nth means i)
            column (js/Float64Array. t-count)]
        (dotimes [t t-count]
          (aset column t (- (nth xs t) m)))
        (aset columns i column)))
    columns))

(defn- sample-covariance-array
  "n*n row-major Float64Array of the (1/T)-scaled sample covariance. Each
  element sums c_i[t]*c_j[t] ascending in t then scales, matching the original
  mat-mul + scalar-matrix order."
  [columns n t-count]
  (let [q (/ 1 t-count)
        s (js/Float64Array. (* n n))]
    (dotimes [i n]
      (let [ci (aget columns i)]
        (dotimes [j n]
          (let [cj (aget columns j)]
            (loop [t 0
                   acc 0]
              (if (< t t-count)
                (recur (inc t) (+ acc (* (aget ci t) (aget cj t))))
                (aset s (+ (* i n) j) (* q acc))))))))
    s))

(defn- beta-sample-terms
  "Per-observation squared Frobenius distance between the observation's outer
  product and the sample covariance, summed row-major like the original
  frobenius-squared over matrix-difference (d = x_i*x_j + (-1 * s_ij))."
  [columns s n t-count]
  (loop [t 0
         terms (transient [])]
    (if (< t t-count)
      (recur (inc t)
             (conj! terms
                    (loop [i 0
                           acc 0]
                      (if (< i n)
                        (let [xi (aget (aget columns i) t)
                              acc* (loop [j 0
                                          acc* acc]
                                     (if (< j n)
                                       (let [d (+ (* xi (aget (aget columns j) t))
                                                  (* -1 (aget s (+ (* i n) j))))]
                                         (recur (inc j) (+ acc* (* d d))))
                                       acc*))]
                          (recur (inc i) acc*))
                        acc))))
      (persistent! terms))))

(defn- delta-hat-value
  "Squared Frobenius distance between the sample and the scaled-identity
  target, summed row-major (d = s_ij + (-1 * t_ij))."
  [s mu n]
  (loop [i 0
         acc 0]
    (if (< i n)
      (recur (inc i)
             (loop [j 0
                    acc* acc]
               (if (< j n)
                 (let [t-ij (if (= i j) (* mu 1) (* mu 0))
                       d (+ (aget s (+ (* i n) j)) (* -1 t-ij))]
                   (recur (inc j) (+ acc* (* d d))))
                 acc*)))
      acc)))

(defn estimate
  [{:keys [series periods-per-year]}]
  (let [feature-count (count series)
        sample-count (if (seq series)
                       (count (first series))
                       0)
        periods-per-year* (or periods-per-year 1)]
    (if (and (pos? feature-count)
             (pos? sample-count)
             (rectangular-series? series))
      (let [n feature-count
            means (mapv #(or (math/mean %) 0) series)
            columns (centered-columns series means sample-count)
            s (sample-covariance-array columns n sample-count)
            trace (loop [i 0
                         acc 0]
                    (if (< i n)
                      (recur (inc i) (+ acc (aget s (+ (* i n) i))))
                      acc))
            mu (if (pos? n) (/ trace n) 0)
            beta-hat (/ (or (math/mean (beta-sample-terms columns s n sample-count)) 0)
                        sample-count)
            delta-hat (delta-hat-value s mu n)
            shrinkage (if (pos? delta-hat)
                        (-> (/ beta-hat delta-hat)
                            (max 0)
                            (min 1))
                        0)
            residual (- 1 shrinkage)
            covariance (mapv (fn [i]
                               (mapv (fn [j]
                                       (let [t-ij (if (= i j) (* mu 1) (* mu 0))
                                             s-ij (aget s (+ (* i n) j))]
                                         (* periods-per-year*
                                            (+ (* shrinkage t-ij)
                                               (* residual s-ij)))))
                                     (range n)))
                             (range n))]
        {:covariance covariance
         :warnings (if (>= shrinkage saturated-shrinkage-threshold)
                     [{:code :risk-shrinkage-saturated
                       :shrinkage shrinkage
                       :sample-count sample-count
                       :feature-count feature-count
                       :message (str "The covariance estimate fell back almost "
                                     "entirely to its scaled-identity target: "
                                     "every asset was assigned the same "
                                     "volatility and every correlation was "
                                     "discarded. This is what one corrupt "
                                     "return observation does to the whole "
                                     "book, so treat the risk numbers as "
                                     "unreliable until the history is checked.")}]
                     [])
         :shrinkage {:kind :ledoit-wolf
                     :target :scaled-identity
                     :shrinkage shrinkage}
         :sample-count sample-count
         :feature-count feature-count})
      ;; The estimator cannot run. It returns the zero matrix as it always has,
      ;; but now SAYS SO: an all-zero covariance renders as 0% portfolio
      ;; volatility, which is a more believable lie than an inflated one.
      ;; `domain.risk/estimate-risk-model` reads this warning and substitutes a
      ;; pairwise sample covariance.
      {:covariance (zero-matrix feature-count)
       :warnings (if (and (pos? feature-count)
                          (not (rectangular-series? series)))
                   [{:code :ragged-return-series
                     :series-lengths (mapv count series)
                     :feature-count feature-count
                     :message (str "Return series have different lengths, so "
                                   "the Ledoit-Wolf estimator could not run. "
                                   "Risk was estimated pairwise instead, which "
                                   "gives each asset its own volatility but no "
                                   "diversification credit between assets of "
                                   "unequal history.")}]
                   [])
       :shrinkage {:kind :ledoit-wolf
                   :target :scaled-identity
                   :shrinkage 0}
       :sample-count sample-count
       :feature-count feature-count})))
