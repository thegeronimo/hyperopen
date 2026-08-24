(ns hyperopen.portfolio.optimizer.domain.risk-ledoit-wolf-test
  "Bit-exact parity between the loop-based Ledoit-Wolf estimator and the
  original persistent-vector implementation it replaced. The rewrite claims
  identical arithmetic ORDER (not just numerical closeness), so these tests
  assert bit-level identity on the whole arithmetic result, reference
  implementation included inline. The estimator's `:warnings` are reporting
  rather than arithmetic and are pinned by `risk-degeneracy-test`; see
  `arithmetic-only`.

  Comparison goes through `bit-parity/bit=` rather than `=`, because `=` is
  unsound in both directions for exactly this job: it reports two NaNs as
  different, so a fixture whose correct answer contains NaN fails even when
  the two implementations agree perfectly, and it reports 0.0 and -0.0 as the
  same, so a port that flipped a zero's sign would pass while `1/x` flipped
  from Infinity to -Infinity downstream."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.bit-parity :as bit-parity]
            [hyperopen.portfolio.optimizer.domain.math :as math]
            [hyperopen.portfolio.optimizer.domain.risk-ledoit-wolf :as ledoit-wolf]))

;; --- reference: the original persistent-vector implementation, verbatim ----

(defn- zero-matrix
  [size]
  (vec (repeat size (vec (repeat size 0)))))

(defn- rectangular-series?
  [series]
  (or (empty? series)
      (let [sample-count (count (first series))]
        (every? #(= sample-count (count %)) series))))

(defn- centered-observations
  [series]
  (let [means (mapv #(or (math/mean %) 0) series)]
    (mapv (fn [row]
            (mapv - row means))
          (apply mapv vector series))))

(defn- sample-covariance
  [centered]
  (let [sample-count (count centered)
        feature-count (count (first centered))]
    (if (pos? sample-count)
      (math/scalar-matrix (/ 1 sample-count)
                          (math/mat-mul (math/transpose centered)
                                        centered))
      (zero-matrix feature-count))))

(defn- scaled-identity-target
  [sample]
  (let [feature-count (count sample)
        trace (reduce + 0 (math/diagonal sample))
        mu (if (pos? feature-count)
             (/ trace feature-count)
             0)]
    (math/scalar-matrix mu
                        (math/identity-matrix feature-count))))

(defn- outer-product
  [values]
  (mapv (fn [left]
          (mapv (fn [right]
                  (* left right))
                values))
        values))

(defn- frobenius-squared
  [matrix]
  (reduce + 0
          (mapcat (fn [row]
                    (map #(* % %) row))
                  matrix)))

(defn- matrix-difference
  [left right]
  (math/matrix-add left
                   (math/scalar-matrix -1 right)))

(defn- arithmetic-only
  "The estimator's arithmetic result, without the reporting layer.

  `estimate` also returns `:warnings` describing states in which its answer is
  not a risk estimate (saturated shrinkage, ragged series). That is reporting,
  not arithmetic, and the reference implementation below deliberately does not
  model it - duplicating warning prose here would pin message text in a parity
  test and make it un-editable. The warnings are pinned instead by
  `hyperopen.portfolio.optimizer.domain.risk-degeneracy-test`, which asserts
  both codes and their payloads."
  [result]
  (dissoc result :warnings))

(defn- reference-estimate
  [{:keys [series periods-per-year]}]
  (let [feature-count (count series)
        sample-count (if (seq series)
                       (count (first series))
                       0)
        periods-per-year* (or periods-per-year 1)]
    (if (and (pos? feature-count)
             (pos? sample-count)
             (rectangular-series? series))
      (let [centered (centered-observations series)
            sample (sample-covariance centered)
            target (scaled-identity-target sample)
            beta-sample (mapv #(frobenius-squared
                                (matrix-difference (outer-product %)
                                                   sample))
                              centered)
            beta-hat (/ (or (math/mean beta-sample) 0)
                        sample-count)
            delta-hat (frobenius-squared
                       (matrix-difference sample target))
            shrinkage (if (pos? delta-hat)
                        (-> (/ beta-hat delta-hat)
                            (max 0)
                            (min 1))
                        0)
            covariance (math/matrix-add
                        (math/scalar-matrix shrinkage target)
                        (math/scalar-matrix (- 1 shrinkage) sample))]
        {:covariance (math/scalar-matrix periods-per-year* covariance)
         :shrinkage {:kind :ledoit-wolf
                     :target :scaled-identity
                     :shrinkage shrinkage}
         :sample-count sample-count
         :feature-count feature-count})
      {:covariance (zero-matrix feature-count)
       :shrinkage {:kind :ledoit-wolf
                   :target :scaled-identity
                   :shrinkage 0}
       :sample-count sample-count
       :feature-count feature-count})))

;; --- deterministic fixture data --------------------------------------------

(defn- synthetic-series
  "n instruments x t observations of deterministic, irrational-ish returns."
  [n t]
  (mapv (fn [i]
          (mapv (fn [k]
                  (+ (* 0.01 (js/Math.sin (+ (* 0.7 i) (* 0.13 k))))
                     (* 0.002 (js/Math.cos (* (inc i) (+ 0.31 k))))))
                (range t)))
        (range n)))

(defn- parity?
  [expected actual]
  (or (bit-parity/bit= expected actual)
      ;; `is` prints both sides, which is no help when they render identically.
      (do (println "  bit-parity divergence:"
                   (bit-parity/first-difference expected actual))
          false)))

(deftest loop-estimator-is-bit-identical-to-reference-on-small-universe-test
  (let [series (synthetic-series 5 17)]
    (is (parity? (reference-estimate {:series series :periods-per-year 365})
                 (arithmetic-only (ledoit-wolf/estimate {:series series :periods-per-year 365}))))))

(deftest loop-estimator-is-bit-identical-to-reference-on-mid-universe-test
  (let [series (synthetic-series 12 60)]
    (is (parity? (reference-estimate {:series series :periods-per-year 365})
                 (arithmetic-only (ledoit-wolf/estimate {:series series :periods-per-year 365}))))))

(deftest loop-estimator-is-bit-identical-with-default-periods-test
  (let [series (synthetic-series 3 9)]
    (is (parity? (reference-estimate {:series series})
                 (arithmetic-only (ledoit-wolf/estimate {:series series}))))))

(deftest loop-estimator-preserves-degenerate-fallbacks-test
  (is (parity? (reference-estimate {:series [] :periods-per-year 365})
               (arithmetic-only (ledoit-wolf/estimate {:series [] :periods-per-year 365}))))
  ;; ragged series still produce the zero matrix here; estimate-risk-model
  ;; substitutes a pairwise sample covariance on the :ragged-return-series
  ;; warning (see risk-degeneracy-test)
  (let [ragged [[0.01 0.02 0.03] [0.01 0.02]]]
    (is (parity? (reference-estimate {:series ragged :periods-per-year 365})
                 (arithmetic-only (ledoit-wolf/estimate {:series ragged :periods-per-year 365})))))
  ;; single observation, single instrument
  (let [tiny [[0.01]]]
    (is (parity? (reference-estimate {:series tiny :periods-per-year 12})
                 (arithmetic-only (ledoit-wolf/estimate {:series tiny :periods-per-year 12}))))))

(deftest loop-estimator-is-bit-identical-on-non-finite-observations-test
  ;; The fixture that makes the comparator load-bearing. A NaN observation
  ;; propagates into the covariance -- `math/mean` filters it out of the mean
  ;; but the centering and the accumulation keep it -- so the correct answer
  ;; contains NaN, and this assertion is red under plain `=` even though both
  ;; implementations agree exactly. If the suite is ever moved back onto `=`,
  ;; this test says so immediately instead of the hole staying latent.
  (let [with-nan [[js/NaN 0.01 0.02] [0.01 0.02 0.03] [0.03 0.01 0.02]]]
    (is (parity? (reference-estimate {:series with-nan :periods-per-year 365})
                 (arithmetic-only (ledoit-wolf/estimate {:series with-nan
                                                         :periods-per-year 365})))))
  (let [with-infinity [[js/Infinity 0.01 0.02] [0.01 0.02 0.03] [0.03 0.01 0.02]]]
    (is (parity? (reference-estimate {:series with-infinity :periods-per-year 365})
                 (arithmetic-only (ledoit-wolf/estimate {:series with-infinity
                                                         :periods-per-year 365}))))))
