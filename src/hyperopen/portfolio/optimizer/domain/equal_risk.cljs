(ns hyperopen.portfolio.optimizer.domain.equal-risk
  "Constrained Equal Risk (:equal-risk) core policy: the centralized numeric
  tolerances, exposure targets, book split, deterministic initializer seeds,
  quality classification, and the damped-BFGS model-Hessian updates.

  The user has already fixed every directional decision (assets, sides, gross
  target G). Equal Risk ignores stored net policy and only sizes positions
  inside one signed-gross equality:

      long book  = assets whose encoded bounds keep w_i >= 0
      short book = assets whose encoded bounds keep w_i <= 0
      G = sum(long weights) + sum(abs(short weights))

  Gross targets come from the canonical exposure-policy midpoint semantics via
  the :exposure-targets the constraint encoder carries; long-only requests use
  that gross target when present and otherwise keep the legacy fully-invested
  fallback.

  Companions: contribution math and gradients in domain.risk-contributions,
  presolve feasibility in domain.equal-risk-presolve, the solver plan and QP
  subproblem shapes in domain.equal-risk-plan, and the iterative
  orchestration in application.engine.equal-risk-solve."
  (:require [hyperopen.portfolio.optimizer.domain.risk-contributions :as risk-contributions]))

(def tolerances
  "Every numeric tolerance the Equal Risk path uses, in one place.

  :exposure-feasibility  weight-units slack for gross equality and
                         presolve capacity comparisons.
  :bound-feasibility     weight-units slack for per-asset bounds and locks when
                         validating solver iterates (QP solvers land ~1e-9
                         outside bounds; 1e-6 is far below display precision).
  :variance-degeneracy   relative q floor (see domain.risk-contributions).
  :armijo-c1             sufficient-decrease constant for the backtracking
                         line search F(w+ad) <= F(w) + c1*a*g'd.
  :min-line-search-alpha smallest step tried before declaring the search
                         exhausted (~20 halvings from 1).
  :max-line-search-steps hard cap on backtracking evaluations per iteration.
  :step-tolerance-scale  convergence when ||d||inf <= scale * max(1, G).
  :objective-improvement convergence when F improves by less than this on two
                         consecutive accepted steps (F is dimensionless).
  :exactness-ratio       :exact quality requires max and rms |RRC - 1/n| to be
                         within this fraction OF the 1/n target share, so the
                         label stays honest at any universe size.
  :max-iterations        SQP iterations per initializer.
  :max-initializations   deterministic starts (see `seed-specs`).
  :bisection-steps       fixed bisection depth for the bounded-book projection."
  {:exposure-feasibility 1e-6
   :bound-feasibility 1e-6
   :variance-degeneracy risk-contributions/variance-degeneracy-factor
   :armijo-c1 1e-4
   :min-line-search-alpha 1e-6
   :max-line-search-steps 25
   :step-tolerance-scale 1e-8
   :objective-improvement 1e-12
   :exactness-ratio 0.01
   :max-iterations 80
   :max-initializations 4
   :bisection-steps 100})

(defn exactness-tolerance
  "Absolute RRC-error threshold for the :exact label at universe size n."
  [n]
  (when (pos? n)
    (* (:exactness-ratio tolerances) (/ 1 n))))

(defn classify-quality
  "Truthful contribution-balance status from the FINAL published weights:
  :exact only when the solver converged AND both realized errors clear the
  documented tolerance; :approximate for a converged solve with material
  error; :not-converged whenever the solver stopped without satisfying its
  convergence criteria (even if the realized error happens to be small)."
  [{:keys [max-absolute-error rms-error]} n converged?]
  (let [tolerance (exactness-tolerance n)]
    (cond
      (not converged?) :not-converged
      (and tolerance
           (<= max-absolute-error tolerance)
           (<= rms-error tolerance)) :exact
      :else :approximate)))

(defn exposure-targets
  "Gross TARGET for Equal Risk. Stored net policy is deliberately omitted:
  :equal-risk treats net as a solver output, not an input constraint."
  [{:keys [long-only? exposure-targets]}]
  (let [{:keys [gross-target]} exposure-targets]
    {:gross (if long-only?
              (or gross-target 1)
              gross-target)}))

(defn book-of-bounds
  "Book membership from the ENCODED bounds (the authoritative feasible region):
  lower >= 0 keeps the weight long-signed, upper <= 0 keeps it short-signed,
  a [0,0] lock counts as long (its contribution is zero either way), and a
  bound pair straddling zero has no fixed side."
  [lower upper]
  (cond
    (and (number? lower) (>= lower 0)) :long
    (and (number? upper) (<= upper 0)) :short
    :else :two-sided))

(defn book-split
  [{:keys [lower-bounds upper-bounds]}]
  (reduce (fn [acc idx]
            (let [book (book-of-bounds (nth lower-bounds idx)
                                       (nth upper-bounds idx))]
              (update acc (case book :long :long :short :short :two-sided) conj idx)))
          {:long [] :short [] :two-sided []}
          (range (count lower-bounds))))

(defn magnitude-bounds
  "Per-asset [lower upper] of |w_i| inside its book. For a long asset that is
  the bound pair itself; for a short asset the magnitudes mirror: lower |w| is
  -upper (upper <= 0) and upper |w| is -lower. Public: the presolve namespace
  aggregates these into book minimum/capacity."
  [book lower upper]
  (case book
    :long [(max 0 lower) upper]
    :short [(max 0 (- upper)) (- lower)]))

(defn project-book-magnitudes
  "Deterministic bisection projection of raw magnitudes v onto one bounded
  book simplex: x_i = clip(v_i - lambda, lower_i, upper_i) with lambda chosen
  so sum(x) = target. sum(clip(v - lambda)) is nonincreasing in lambda, so a
  fixed-depth bisection converges; targets outside the box capacity clamp to
  the nearest attainable bound (presolve rejects those requests anyway)."
  [values magnitude-lowers magnitude-uppers target]
  (let [clip-sum (fn [lambda]
                   (reduce + 0 (map (fn [v lo hi]
                                      (-> (- v lambda) (max lo) (min hi)))
                                    values magnitude-lowers magnitude-uppers)))
        lambda-low (reduce min 0 (map - values magnitude-uppers))
        lambda-high (reduce max 0 (map - values magnitude-lowers))
        lambda (loop [low lambda-low
                      high lambda-high
                      step 0]
                 (if (>= step (:bisection-steps tolerances))
                   (* 0.5 (+ low high))
                   (let [mid (* 0.5 (+ low high))]
                     (if (> (clip-sum mid) target)
                       (recur mid high (inc step))
                       (recur low mid (inc step))))))]
    (mapv (fn [v lo hi]
            (-> (- v lambda) (max lo) (min hi)))
          values magnitude-lowers magnitude-uppers)))

(defn- book-by-index
  [books]
  (merge (zipmap (:long books) (repeat :long))
         (zipmap (:short books) (repeat :short))))

(defn- seed-magnitudes
  [kind books lower-bounds upper-bounds target covariance current-weights]
  (let [indexes (vec (concat (:long books) (:short books)))
        by-index (book-by-index books)
        k (count indexes)
        raw (case kind
              :equal-notional (vec (repeat k (/ target (max 1 k))))
              :inverse-volatility (let [sigmas (mapv #(js/Math.sqrt
                                                       (max 0 (get-in covariance [% %])))
                                                     indexes)]
                                    (when (every? pos? sigmas)
                                      (let [inverses (mapv #(/ 1 %) sigmas)
                                            total (reduce + 0 inverses)]
                                        (mapv #(* target (/ % total)) inverses))))
              :inverse-variance (let [variances (mapv #(get-in covariance [% %]) indexes)]
                                  (when (every? pos? variances)
                                    (let [inverses (mapv #(/ 1 %) variances)
                                          total (reduce + 0 inverses)]
                                      (mapv #(* target (/ % total)) inverses))))
              :current-weights (mapv (fn [idx]
                                       (let [book (get by-index idx)
                                             weight (or (nth current-weights idx nil) 0)
                                             signed (case book
                                                      :long weight
                                                      :short (- weight))]
                                         (max 0 signed)))
                                     indexes))]
    (when raw
      (let [magnitude-lowers (mapv #(first (magnitude-bounds (get by-index %)
                                                             (nth lower-bounds %)
                                                             (nth upper-bounds %)))
                                   indexes)
            magnitude-uppers (mapv #(second (magnitude-bounds (get by-index %)
                                                              (nth lower-bounds %)
                                                              (nth upper-bounds %)))
                                   indexes)]
        {:indexes indexes
         :books by-index
         :magnitudes (project-book-magnitudes raw
                                              magnitude-lowers
                                              magnitude-uppers
                                              target)}))))

(def seed-kinds
  "Deterministic initializer order. The sequential solver runs them in this
  order and may exit early once a start reaches :exact quality."
  [:equal-notional :inverse-volatility :inverse-variance :current-weights])

(defn seed-weights
  "Signed seed vector for one initializer kind, or nil when the kind cannot be
  built (e.g. inverse-volatility with a zero-variance asset). The one gross
  target is hit exactly (up to bisection depth); turnover couplings are
  handled afterwards by the projection QP in the solver."
  [kind {:keys [lower-bounds upper-bounds current-weights]} books targets covariance]
  (let [n (count lower-bounds)
        projected (seed-magnitudes kind books lower-bounds upper-bounds
                                   (:gross targets)
                                   covariance current-weights)]
    (when projected
      (as-> (vec (repeat n 0)) weights
        (reduce (fn [acc [idx magnitude]]
                  (let [book (get (:books projected) idx)
                        signed (case book
                                 :long magnitude
                                 :short (- magnitude))]
                    (assoc acc idx signed)))
                weights
                (map vector (:indexes projected) (:magnitudes projected)))))))

(defn identity-hessian
  [n]
  (mapv (fn [row]
          (mapv (fn [col] (if (= row col) 1 0)) (range n)))
        (range n)))

(defn rescale-hessian
  "One-time Nocedal-Wright (6.20) rescale applied before the FIRST update:
  B = ((y.y)/(y.s)) * I sizes the model to the objective's local curvature so
  the first quasi-Newton steps are well-scaled. Falls back to B unchanged on
  invalid curvature."
  [b s y]
  (let [ys (reduce + 0 (map * y s))
        yy (reduce + 0 (map * y y))]
    (if (and (pos? ys) (pos? yy) (js/isFinite (/ yy ys)))
      (let [gamma (/ yy ys)]
        (mapv (fn [row-idx row]
                (mapv (fn [col-idx _]
                        (if (= row-idx col-idx) gamma 0))
                      (range)
                      row))
              (range)
              b))
      b)))

(defn bfgs-update
  "Powell-damped BFGS update keeping B positive definite; returns B unchanged
  when curvature information is numerically invalid."
  [b s y]
  (let [bs (mapv (fn [row] (reduce + 0 (map * row s))) b)
        s-b-s (reduce + 0 (map * s bs))
        s-y (reduce + 0 (map * s y))]
    (if (or (not (js/isFinite s-b-s))
            (<= s-b-s 1e-16))
      b
      (let [theta (if (>= s-y (* 0.2 s-b-s))
                    1
                    (/ (* 0.8 s-b-s) (- s-b-s s-y)))
            r (mapv (fn [y-i bs-i] (+ (* theta y-i) (* (- 1 theta) bs-i))) y bs)
            s-r (reduce + 0 (map * s r))]
        (if (or (not (js/isFinite s-r))
                (<= s-r 1e-16))
          b
          (mapv (fn [row-idx row]
                  (mapv (fn [col-idx value]
                          (+ value
                             (- (/ (* (nth bs row-idx) (nth bs col-idx)) s-b-s))
                             (/ (* (nth r row-idx) (nth r col-idx)) s-r)))
                        (range)
                        row))
                (range)
                b))))))
