(ns hyperopen.portfolio.optimizer.domain.history-assumption-proxy
  "Pure proxy-basket covariance synthesis for engine-backed proxy history
  assumptions.

  Modeling contract (see the 2026-07-05 proxy-history-assumptions ExecPlan):
  the qualitative prior (the user's selected proxy basket) chooses the exposure;
  a multi-proxy ridge regression on the asset's short overlap adjusts it; a
  confidence weight built from sample count and fit quality governs the
  shrinkage between the two; specific (idiosyncratic) risk and the cap keep the
  optimizer honest. R-squared is a confidence signal only - NEVER a source of
  proxy weights - and a handful of observations must never dominate the prior.

  Everything here is deterministic and worker-safe: inputs arrive on the engine
  request (proxy ids, the user's raw prior weights when given, relationship
  strength, and the short-overlap return series the request builder aligned),
  outputs are a new covariance row/column per assumed asset plus diagnostics.
  `model-exposure` is the one pipeline seam - the setup-card preview calls it
  with the same inputs, so the displayed basket is the engine's basket."
  (:require [hyperopen.portfolio.optimizer.domain.history-assumptions :as history-assumptions]
            [hyperopen.portfolio.optimizer.domain.linear-solve :as linear-solve]
            [hyperopen.portfolio.optimizer.domain.math :as math]
            [hyperopen.portfolio.optimizer.domain.risk :as risk]))

(def confidence-observation-scale
  "n0 in q = min(1, n/(n+n0)): the overlap sample count at which the regression
  earns half of the sample-side confidence. 10 observations => q <= 0.077 even
  at a perfect fit."
  120)

(def confidence-r2-target
  "Fit quality that earns full R-squared-side confidence."
  0.5)

(defn min-regression-observations
  "Overlap sample counts below this skip the regression entirely (q = 0) for a
  basket of `proxy-count` proxies: max(10, k + 3). A handful of return points
  must never read as model evidence, however good the in-sample fit looks."
  [proxy-count]
  (max 10 (+ proxy-count 3)))

(def ^:private finite-number? math/finite-number?)

(defn- positive-number?
  [value]
  (and (finite-number? value)
       (pos? value)))

(defn- equal-weights
  [proxy-ids]
  (let [n (count proxy-ids)]
    (into {}
          (map (fn [id] [id (/ 1 n)]))
          proxy-ids)))

(defn build-proxy-prior
  "Source-labeled prior basket over the selected proxies. Priority: valid
  user-specified weights (:source :user), else equal weight (:source :equal).
  User weights are valid when every proxy id carries a finite nonnegative weight
  with a positive total; anything else falls back to equal weight WITH a warning,
  never silently. A future metadata-informed prior (ecosystem/category similarity
  scores) slots in here as a source between the two - do not fake one while the
  app carries no such metadata.

  Returns {:weights {id w} :source :user|:equal :warnings [...]} with weights
  normalized to sum 1; nil when there are no proxy ids at all."
  [proxy-ids user-weights]
  (when (seq proxy-ids)
    (let [selected (select-keys (or user-weights {}) proxy-ids)
          valid? (and (= (count selected) (count proxy-ids))
                      (every? (fn [[_ w]]
                                (and (finite-number? w)
                                     (not (neg? w))))
                              selected)
                      (pos? (reduce + 0 (vals selected))))]
      (cond
        valid?
        (let [total (reduce + 0 (vals selected))]
          {:weights (into {}
                          (map (fn [[id w]] [id (/ w total)]))
                          selected)
           :source :user
           :warnings []})

        (some? user-weights)
        {:weights (equal-weights proxy-ids)
         :source :equal
         :warnings [{:code :proxy-prior-weights-invalid
                     :message "Explicit proxy prior weights were invalid (missing, negative, or zero-total), so the equal-weight fallback was used instead."}]}

        :else
        {:weights (equal-weights proxy-ids)
         :source :equal
         :warnings []}))))

(defn- demean
  [values]
  (let [m (or (math/mean values) 0)]
    (mapv #(- % m) values)))

(defn- regression-rows
  "Aligned, all-finite observation rows from the request's regression series:
  [{:y r-x :x [r-p1 r-p2 ...]} ...] in proxy-ids order. Rows with any missing or
  non-finite value are dropped."
  [regression-series proxy-ids]
  (let [x-returns (vec (or (:x-returns regression-series) []))
        by-proxy (or (:proxy-returns-by-id regression-series) {})
        proxy-series (mapv #(vec (or (get by-proxy %) [])) proxy-ids)
        n (apply min (count x-returns) (map count proxy-series))]
    (when (and (seq proxy-ids) (pos? n))
      (->> (range n)
           (keep (fn [idx]
                   (let [y (nth x-returns idx)
                         xs (mapv #(nth % idx) proxy-series)]
                     (when (and (finite-number? y)
                                (every? finite-number? xs))
                       {:y y :x xs}))))
           vec))))

(defn- xtx-matrix
  [x-cols]
  (mapv (fn [col-a]
          (mapv (fn [col-b] (math/dot col-a col-b)) x-cols))
        x-cols))

(defn- ridge-lambda
  "Scale-aware ridge strength toward the prior: the mean diagonal of X'X scaled
  by n0/n, so the prior dominates exactly when the sample is tiny and cedes
  ground as observations accumulate."
  [xtx n]
  (let [k (count xtx)
        mean-diag (if (pos? k)
                    (/ (reduce + 0 (map-indexed (fn [idx row] (nth row idx)) xtx))
                       k)
                    0)]
    (max 1e-10
         (* mean-diag (/ confidence-observation-scale (max n 1))))))

(defn- clamp01
  [value]
  (-> value (max 0) (min 1)))

(defn- nonnegative-normalized
  "V1 exposure policy: clamp negatives to zero and renormalize to sum 1, so the
  exposure always reads as a long-only proxy basket. Returns nil when nothing
  positive survives."
  [beta-by-id]
  (let [clamped (into {}
                      (map (fn [[id b]] [id (max 0 (if (finite-number? b) b 0))]))
                      beta-by-id)
        total (reduce + 0 (vals clamped))]
    (when (pos? total)
      (into {}
            (map (fn [[id b]] [id (/ b total)]))
            clamped))))

(defn estimate-regression
  "Multi-proxy ridge regression of the asset's demeaned overlap returns on the
  demeaned proxy returns, regularized toward the prior:
  minimize ||y - Xb||^2 + lambda * ||b - b_prior||^2. One joint regression -
  never per-proxy fits - and R-squared is reported as a confidence signal only.
  Returns {:status :estimated ...} or {:status :skipped :reason ...}."
  [{:keys [regression-series proxy-ids prior-weights]}]
  (let [rows (regression-rows regression-series proxy-ids)
        n (count rows)]
    (if (< n (min-regression-observations (count proxy-ids)))
      {:status :skipped
       :reason (if (pos? n) :insufficient-overlap :no-overlap)
       :sample-count n}
      (let [y (demean (mapv :y rows))
            k (count proxy-ids)
            x-cols (mapv (fn [col-idx]
                           (demean (mapv #(nth (:x %) col-idx) rows)))
                         (range k))
            xtx (xtx-matrix x-cols)
            lambda (ridge-lambda xtx n)
            prior-vec (mapv #(get prior-weights % 0) proxy-ids)
            a-matrix (math/matrix-add xtx
                                      (math/scalar-matrix lambda
                                                          (math/identity-matrix k)))
            b-vector (math/vec-add (mapv #(math/dot % y) x-cols)
                                   (math/scalar-vec lambda prior-vec))
            solved (linear-solve/solve-systems a-matrix [b-vector])]
        (if-not (= :solved (:status solved))
          {:status :skipped
           :reason :singular-regression
           :sample-count n}
          (let [beta-raw (first (:solutions solved))
                fitted (mapv (fn [row-idx]
                               (reduce + 0
                                       (map-indexed (fn [col-idx col]
                                                      (* (nth beta-raw col-idx)
                                                         (nth col row-idx)))
                                                    x-cols)))
                             (range n))
                ss-res (reduce + 0 (map (fn [yi fi]
                                          (let [e (- yi fi)] (* e e)))
                                        y fitted))
                ss-tot (reduce + 0 (map #(* % %) y))
                r2 (when (pos? ss-tot)
                     (clamp01 (- 1 (/ ss-res ss-tot))))
                beta-by-id (zipmap proxy-ids beta-raw)
                beta (nonnegative-normalized beta-by-id)
                adjusted? (or (nil? beta)
                              (some (fn [[id b]]
                                      (> (js/Math.abs (- (get beta id 0) b)) 1e-9))
                                    beta-by-id))
                residual-variance (/ ss-res (max 1 (- n k)))]
            (if (nil? r2)
              {:status :skipped
               :reason :degenerate-overlap
               :sample-count n}
              {:status :estimated
               :beta (or beta prior-weights)
               :raw-beta beta-by-id
               :r2 r2
               :sample-count n
               :residual-variance residual-variance
               :adjusted? (boolean adjusted?)})))))))

(defn confidence-q
  "q = min(1, n/(n+n0)) * min(1, r2/target), clamped to [0,1]. nil r2 (no usable
  regression) means zero confidence: the prior stands alone."
  [n r2]
  (if (or (nil? r2) (nil? n) (not (pos? n)))
    0
    (clamp01 (* (min 1 (/ n (+ n confidence-observation-scale)))
                (min 1 (/ (max 0 r2) confidence-r2-target))))))

(defn blend-exposure
  "b_final = q * b_regression + (1 - q) * b_prior over the proxy ids, then
  nonnegative-normalized so the result still reads as a basket. q = 0 returns
  the prior exactly."
  [prior-weights regression-beta q]
  (if (or (zero? q) (nil? regression-beta))
    prior-weights
    (or (nonnegative-normalized
         (into {}
               (map (fn [[id prior-w]]
                      [id (+ (* q (get regression-beta id 0))
                             (* (- 1 q) prior-w))]))
               prior-weights))
        prior-weights)))

(defn model-exposure
  "The full exposure pipeline for one proxy assumption, over the proxy ids that
  actually reach the risk model: source-labeled prior -> ONE multi-proxy ridge
  regression on the short overlap -> confidence q -> blended final basket. This
  is the single seam both covariance synthesis and the setup-card preview call,
  so the basket the user is shown is the basket the engine uses.

  Returns nil without proxy ids; otherwise
  {:prior-weights :prior-source :prior-warnings
   :regression <estimate-regression result> :confidence-q :final-beta}."
  [{:keys [proxy-ids user-prior-weights regression-series]}]
  (when (seq proxy-ids)
    (let [{prior :weights prior-source :source prior-warnings :warnings}
          (build-proxy-prior proxy-ids user-prior-weights)
          regression (estimate-regression {:regression-series regression-series
                                           :proxy-ids proxy-ids
                                           :prior-weights prior})
          estimated? (= :estimated (:status regression))
          q (confidence-q (or (:sample-count regression) 0)
                          (when estimated? (:r2 regression)))]
      {:prior-weights prior
       :prior-source prior-source
       :prior-warnings (vec prior-warnings)
       :regression regression
       :confidence-q q
       :final-beta (blend-exposure prior
                                   (when estimated? (:beta regression))
                                   q)})))

(defn specific-variance
  "Conservative idiosyncratic top-up so the basket is never a perfect clone:
  max(user variance - systematic variance,
      annualized regression residual variance (when available),
      min-share * user variance,
      0)."
  [{:keys [user-variance systematic-variance residual-variance min-share]}]
  (max (- user-variance systematic-variance)
       (if (finite-number? residual-variance) residual-variance 0)
       (* (or min-share 0) user-variance)
       0))

(defn- index-by-id
  [ids]
  (zipmap ids (range)))

(defn- synthesize-asset
  "Fold one proxy assumption into {:ids :covariance}: computes the final
  exposure, builds the synthetic row/column, and replaces (asset already
  present) or appends (asset re-admitted with no realized row) it. Returns
  {:ids :covariance :diagnostics :warnings}."
  [{:keys [ids covariance]} instrument-id entry periods-per-year]
  (let [id-index (index-by-id ids)
        requested-proxies (vec (:proxy-instrument-ids entry))
        present-proxies (filterv #(contains? id-index %) requested-proxies)
        missing-proxies (vec (remove (set present-proxies) requested-proxies))
        volatility (:volatility entry)
        user-variance (* volatility volatility)]
    (if (empty? present-proxies)
      {:ids ids
       :covariance covariance
       :diagnostics nil
       :warnings [{:code :proxy-assumption-skipped
                   :instrument-id instrument-id
                   :proxy-instrument-ids requested-proxies
                   :message "No selected proxy asset reached the risk model, so the proxy assumption could not be synthesized."}]}
      (let [{prior :prior-weights
             prior-source :prior-source
             prior-warnings :prior-warnings
             regression :regression
             q :confidence-q
             beta :final-beta}
            (model-exposure {:proxy-ids present-proxies
                             :user-prior-weights (:proxy-prior-weights entry)
                             :regression-series (:regression-series entry)})
            estimated? (= :estimated (:status regression))
            sample-count (or (:sample-count regression) 0)
            r2 (when estimated? (:r2 regression))
            residual-variance (when estimated?
                                (* (or periods-per-year risk/default-periods-per-year)
                                   (:residual-variance regression)))
            proxy-indexes (mapv id-index present-proxies)
            beta-vec (mapv #(get beta % 0) present-proxies)
            cov-with (fn [row-idx]
                       (reduce + 0
                               (map (fn [b p-idx]
                                      (* b (or (get-in covariance [row-idx p-idx]) 0)))
                                    beta-vec
                                    proxy-indexes)))
            systematic-variance
            (reduce + 0
                    (for [[bj j] (map vector beta-vec proxy-indexes)
                          [bk k] (map vector beta-vec proxy-indexes)]
                      (* bj bk (or (get-in covariance [j k]) 0))))
            min-share (get history-assumptions/proxy-min-specific-risk-share
                           (:relationship-strength entry)
                           (get history-assumptions/proxy-min-specific-risk-share
                                history-assumptions/default-relationship-strength))
            specific (specific-variance {:user-variance user-variance
                                         :systematic-variance systematic-variance
                                         :residual-variance residual-variance
                                         :min-share min-share})
            total-variance (+ systematic-variance specific)
            existing-index (get id-index instrument-id)
            ids* (if existing-index ids (conj ids instrument-id))
            x-index (or existing-index (dec (count ids*)))
            n* (count ids*)
            covariance* (mapv (fn [r]
                                (mapv (fn [c]
                                        (cond
                                          (and (= r x-index) (= c x-index))
                                          total-variance

                                          (= r x-index)
                                          (cov-with c)

                                          (= c x-index)
                                          (cov-with r)

                                          :else
                                          (or (get-in covariance [r c]) 0)))
                                      (range n*)))
                              (range n*))
            warnings (vec
                      (concat
                       (map #(assoc % :instrument-id instrument-id) prior-warnings)
                       (when (seq missing-proxies)
                         [{:code :proxy-asset-unavailable
                           :instrument-id instrument-id
                           :proxy-instrument-ids missing-proxies
                           :message "Some selected proxy assets did not reach the risk model; the basket was renormalized over the rest."}])
                       (when (> systematic-variance user-variance)
                         [{:code :proxy-systematic-variance-exceeds-target
                           :instrument-id instrument-id
                           :systematic-variance systematic-variance
                           :target-variance user-variance
                           :message "The proxy basket implies more variance than the stated volatility; total risk was lifted through specific risk instead of shrinking the basket."}])))]
        {:ids ids*
         :covariance covariance*
         :diagnostics {:behavior :proxy
                       :proxy-instrument-ids present-proxies
                       :prior-weights prior
                       :prior-source prior-source
                       :regression-beta (when estimated? (:beta regression))
                       :regression-beta-raw (when estimated? (:raw-beta regression))
                       :final-beta beta
                       :sample-count sample-count
                       :r2 r2
                       :confidence-q q
                       :regression-status (:status regression)
                       :regression-skip-reason (:reason regression)
                       :regression-adjusted? (boolean (:adjusted? regression))
                       :systematic-variance systematic-variance
                       :specific-variance specific
                       :residual-variance residual-variance
                       :input-volatility volatility
                       :effective-modeled-volatility (js/Math.sqrt total-variance)
                       :max-weight (:max-weight entry)
                       :relationship-strength (:relationship-strength entry)}
         :warnings warnings}))))

(defn augment-risk-result-with-proxy-assumptions
  "Synthesizes a covariance row/column for every complete proxy assumption on top
  of the (already conservative-augmented) base risk result. Assets already
  present keep their position with the row replaced; re-admitted assets are
  appended. Assets are processed in sorted-id order so runs are deterministic;
  the matrix is symmetric by construction and PSD-repaired once at the end.
  Diagnostics land under [:risk-estimation :history-assumptions instrument-id],
  which the engine payload already forwards to results.

  `proxy-inputs-by-id` maps instrument-id -> flattened engine entry (see
  history-assumptions/proxy-engine-inputs). Entries lacking a positive
  volatility or any proxies are ignored."
  ([risk-result proxy-inputs-by-id]
   (augment-risk-result-with-proxy-assumptions risk-result proxy-inputs-by-id nil))
  ([risk-result proxy-inputs-by-id {:keys [periods-per-year]}]
   (let [inputs (into {}
                      (filter (fn [[_ entry]]
                                (and (positive-number? (:volatility entry))
                                     (seq (:proxy-instrument-ids entry)))))
                      proxy-inputs-by-id)]
     (if (empty? inputs)
       risk-result
       (let [initial {:ids (vec (:instrument-ids risk-result))
                      :covariance (:covariance risk-result)}
             folded (reduce (fn [acc [instrument-id entry]]
                              (let [{:keys [ids covariance diagnostics warnings]}
                                    (synthesize-asset acc instrument-id entry periods-per-year)]
                                (-> acc
                                    (assoc :ids ids :covariance covariance)
                                    (update :diagnostics
                                            (fn [d]
                                              (if diagnostics
                                                (assoc d instrument-id diagnostics)
                                                d)))
                                    (update :warnings into warnings))))
                            (assoc initial :diagnostics {} :warnings [])
                            (sort-by first inputs))
             {repaired :covariance psd-warning :warning} (risk/repair-psd (:covariance folded))]
         (-> risk-result
             (assoc :instrument-ids (:ids folded)
                    :covariance repaired)
             (update :warnings
                     (fn [warnings]
                       (vec (concat (or warnings [])
                                    (:warnings folded)
                                    (when psd-warning [psd-warning])))))
             (update-in [:risk-estimation :history-assumptions]
                        (fn [existing]
                          (merge (or existing {}) (:diagnostics folded))))))))))
