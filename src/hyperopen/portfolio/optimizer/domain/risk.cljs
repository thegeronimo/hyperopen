(ns hyperopen.portfolio.optimizer.domain.risk
  (:require [hyperopen.portfolio.optimizer.domain.math :as math]
            [hyperopen.portfolio.optimizer.domain.return-plausibility :as plausibility]
            [hyperopen.portfolio.optimizer.domain.risk-ledoit-wolf :as risk-ledoit-wolf]
            [hyperopen.portfolio.optimizer.domain.risk-mixed-frequency :as mixed-frequency]))

(def default-periods-per-year
  365)

(def default-shrinkage
  0.1)

(def ^:private psd-epsilon
  1e-8)

(defn- sorted-instrument-ids
  [history]
  (->> [(keys (:return-series-by-instrument history))
        (keys (:raw-price-series-by-instrument history))]
       (apply concat)
       set
       sort))

(defn- series-by-id
  [history instrument-ids]
  (mapv #(vec (get-in history [:return-series-by-instrument %])) instrument-ids))

(defn- covariance-matrix
  [series periods-per-year]
  (mapv (fn [xs]
          (mapv (fn [ys]
                  (* periods-per-year
                     (or (math/sample-covariance xs ys) 0)))
                series))
        series))

(defn- diagonal-shrink
  [matrix shrinkage]
  (mapv (fn [row row-idx]
          (mapv (fn [value col-idx]
                  (if (= row-idx col-idx)
                    value
                    (* (- 1 shrinkage) value)))
                row
                (range)))
        matrix
        (range)))

(defn- normalize-risk-model-kind
  [kind]
  (case kind
    :ledoit-wolf :diagonal-shrink
    :ledoit-wolf-dense :ledoit-wolf-dense
    :diagonal-shrink :diagonal-shrink
    :sample-covariance :sample-covariance
    :mixed-frequency :mixed-frequency
    kind))

(defn missing-native-risk-history-warnings
  [{:keys [risk-model history]}]
  (let [risk-model* (or risk-model {:kind :diagonal-shrink})
        model-kind (normalize-risk-model-kind (:kind risk-model*))
        instrument-ids (vec (sorted-instrument-ids history))]
    (if (mixed-frequency/mixed-frequency? model-kind
                                           history
                                           instrument-ids)
      (vec (mixed-frequency/missing-native-risk-history-warnings
            history
            instrument-ids
            (mixed-frequency/instrument-ids history instrument-ids)))
      [])))

(defn- matrix->mutable-array
  [matrix]
  (let [n (count matrix)
        result (js/Array. n)]
    (doseq [row-idx (range n)]
      (let [source-row (nth matrix row-idx)
            row (js/Array. n)]
        (doseq [col-idx (range n)]
          (aset row col-idx (double (or (nth source-row col-idx) 0))))
        (aset result row-idx row)))
    result))

(defn- array-matrix-get
  [matrix row col]
  (aget (aget matrix row) col))

(defn- array-matrix-set!
  [matrix row col value]
  (aset (aget matrix row) col value))

(defn- mutable-diagonal
  [matrix]
  (mapv #(array-matrix-get matrix % %) (range (.-length matrix))))

(defn- symmetric-eigenvalues
  [matrix]
  (let [n (count matrix)
        mutable (matrix->mutable-array matrix)
        tolerance 1e-10
        max-sweeps (max 8 (min 16 n))]
    (loop [sweep 0]
      (if (>= sweep max-sweeps)
        (mutable-diagonal mutable)
        (let [rotated? (volatile! false)]
          (doseq [row (range n)
                  col (range (inc row) n)]
            (let [apq (array-matrix-get mutable row col)]
              (when (> (js/Math.abs apq) tolerance)
                (let [app (array-matrix-get mutable row row)
                      aqq (array-matrix-get mutable col col)
                      tau (/ (- aqq app) (* 2 apq))
                      signed (/ (if (neg? tau) -1 1)
                                (+ (js/Math.abs tau)
                                   (js/Math.sqrt (+ 1 (* tau tau)))))
                      cosine (/ 1 (js/Math.sqrt (+ 1 (* signed signed))))
                      sine (* signed cosine)]
                  (vreset! rotated? true)
                  (doseq [idx (range n)
                          :when (and (not= idx row)
                                     (not= idx col))]
                    (let [aip (array-matrix-get mutable idx row)
                          aiq (array-matrix-get mutable idx col)
                          aip* (- (* cosine aip) (* sine aiq))
                          aiq* (+ (* sine aip) (* cosine aiq))]
                      (array-matrix-set! mutable idx row aip*)
                      (array-matrix-set! mutable row idx aip*)
                      (array-matrix-set! mutable idx col aiq*)
                      (array-matrix-set! mutable col idx aiq*)))
                  (array-matrix-set! mutable row row (- app (* signed apq)))
                  (array-matrix-set! mutable col col (+ aqq (* signed apq)))
                  (array-matrix-set! mutable row col 0)
                  (array-matrix-set! mutable col row 0)))))
          (if @rotated?
            (recur (inc sweep))
            (mutable-diagonal mutable)))))))

(defn covariance-plausibility
  "Absolute magnitude check over the covariance DIAGONAL, reported alongside
  `covariance-conditioning`.

  Conditioning cannot answer this question, by construction: it is a ratio of
  eigenvalues, so a scaled identity - the exact matrix a saturated Ledoit-Wolf
  shrinkage produces - scores a perfect condition number of 1 and reports :ok no
  matter how large its entries are. That is why a book reporting 8,697.7%
  annualized volatility sat next to the word `Healthy` on 2026-08-23.

  `instrument-ids` is positional with the matrix and may be nil; when supplied,
  the offending assets are named so the rail can point at them."
  ([covariance]
   (covariance-plausibility covariance nil))
  ([covariance instrument-ids]
   (let [n (count covariance)
         vols (mapv (fn [idx]
                      (let [variance (get-in covariance [idx idx])]
                        (when (and (math/finite-number? variance)
                                   (not (neg? variance)))
                          (js/Math.sqrt variance))))
                    (range n))
         finite-vols (filterv math/finite-number? vols)
         max-volatility (when (seq finite-vols) (apply max finite-vols))
         implausible-idxs (filterv #(plausibility/implausible-volatility?
                                     (nth vols % nil))
                                   (range n))]
     {:max-volatility max-volatility
      :bound plausibility/implausible-volatility-bound
      :implausible-instrument-ids (mapv #(nth instrument-ids % %) implausible-idxs)
      :status (cond
                (empty? finite-vols) :unknown
                (seq implausible-idxs) :implausible
                :else :ok)})))

(defn covariance-conditioning
  [covariance]
  (let [eigenvalues (filter math/finite-number? (symmetric-eigenvalues covariance))
        min-eigenvalue (when (seq eigenvalues) (apply min eigenvalues))
        max-eigenvalue (when (seq eigenvalues) (apply max eigenvalues))
        positive (filter #(> % 1e-12) eigenvalues)
        min-positive (when (seq positive) (apply min positive))
        condition-number (when (and (math/finite-number? min-positive)
                                    (math/finite-number? max-eigenvalue)
                                    (pos? min-positive))
                           (/ max-eigenvalue min-positive))]
    {:condition-number condition-number
     :min-eigenvalue min-eigenvalue
     :max-eigenvalue max-eigenvalue
     :status (cond
               (and (math/finite-number? min-eigenvalue)
                    (< min-eigenvalue -1e-8)) :not-positive-semidefinite
               (nil? condition-number) :unknown
               (> condition-number 1000000) :ill-conditioned
               (> condition-number 10000) :watch
               :else :ok)}))

(defn- diagonal-load
  [matrix amount]
  (mapv (fn [row idx]
          (update row idx #(+ (or % 0) amount)))
        matrix
        (range)))

(defn repair-psd
  [covariance]
  (let [conditioning (covariance-conditioning covariance)
        min-eigenvalue (:min-eigenvalue conditioning)]
    (if (and (math/finite-number? min-eigenvalue)
             (< min-eigenvalue 0))
      (let [loading (+ (- min-eigenvalue) psd-epsilon)]
        {:covariance (diagonal-load covariance loading)
         :warning {:code :psd-repair-applied
                   :diagonal-loading loading
                   :min-eigenvalue min-eigenvalue
                   :message "Covariance matrix was repaired with diagonal loading to keep it positive semidefinite."}})
      {:covariance covariance
       :warning nil})))

(defn estimate-risk-model
  [{:keys [risk-model periods-per-year history]}]
  (let [risk-model* (or risk-model {:kind :diagonal-shrink})
        requested-kind (:kind risk-model*)
        model-kind (normalize-risk-model-kind requested-kind)
        periods-per-year* (or periods-per-year default-periods-per-year)
        instrument-ids (vec (sorted-instrument-ids history))
        cadence-by-instrument (mixed-frequency/cadence-by-instrument
                               history
                               instrument-ids)
        warnings* (mixed-frequency/warnings requested-kind
                                            cadence-by-instrument)
        mixed-frequency? (mixed-frequency/mixed-frequency? model-kind
                                                            history
                                                            instrument-ids)]
    (if mixed-frequency?
      (let [risk-instrument-ids (mixed-frequency/instrument-ids history
                                                                instrument-ids)
            missing-native-warnings (mixed-frequency/missing-native-risk-history-warnings
                                     history
                                     instrument-ids
                                     risk-instrument-ids)
            override-warning (mixed-frequency/override-warning model-kind)
            {:keys [covariance pair-metadata warnings dense-block]}
            (mixed-frequency/matrix
             history
             risk-instrument-ids
             {:dense-block-estimator (when (= :ledoit-wolf-dense model-kind)
                                       :ledoit-wolf-dense)
              :periods-per-year periods-per-year*})
            shrinkage (or (:shrinkage risk-model*) default-shrinkage)
            {covariance* :covariance psd-warning :warning}
            (repair-psd covariance)
            covariance** (if (= :diagonal-shrink model-kind)
                           (diagonal-shrink covariance* shrinkage)
                           covariance*)
            warnings** (vec (concat warnings*
                                    missing-native-warnings
                                    (when override-warning [override-warning])
                                    warnings
                                    (when psd-warning [psd-warning])))]
        (cond-> {:model :mixed-frequency
                 :requested-model model-kind
                 :instrument-ids risk-instrument-ids
                 :covariance covariance**
                 :pair-metadata pair-metadata
                 :risk-estimation (cond-> (mixed-frequency/risk-estimation
                                            history)
                                    dense-block
                                    (assoc :dense-block-estimator :ledoit-wolf-dense
                                           :dense-block-instrument-ids
                                           (:instrument-ids dense-block)
                                           :dense-block-shrinkage
                                           (:shrinkage dense-block)
                                           :dense-block-sample-count
                                           (:sample-count dense-block)
                                           :dense-block-feature-count
                                           (:feature-count dense-block))
                                    (and dense-block psd-warning)
                                    (assoc :dense-block-post-repair-diagonal-loading
                                           (:diagonal-loading psd-warning)))
                 :warnings warnings**}
          (= :diagonal-shrink model-kind)
          (assoc :shrinkage {:kind :diagonal
                             :shrinkage shrinkage})))
      (let [series (series-by-id history instrument-ids)
            ;; The pairwise sample covariance re-derives per-pair means over the
            ;; full series, so it is expensive at scale - and the Ledoit-Wolf
            ;; branch never reads it. Deferred so :ledoit-wolf-dense skips it.
            sample (delay (covariance-matrix series periods-per-year*))
            shrinkage (or (:shrinkage risk-model*) default-shrinkage)
            ledoit-wolf-result (when (= :ledoit-wolf-dense model-kind)
                                 (risk-ledoit-wolf/estimate
                                  {:series series
                                   :periods-per-year periods-per-year*}))
            ;; The Ledoit-Wolf estimator reports that it could not run on ragged
            ;; series; deciding what to use instead is this function's job. The
            ;; pairwise sample covariance gets every DIAGONAL right (each series
            ;; against itself) and zeroes only the unequal-length off-diagonals,
            ;; so it understates diversification - conservative, and honest.
            ;; Publishing its all-zero matrix instead reported 0% volatility for
            ;; a real book, which is the more believable failure.
            ledoit-wolf-unavailable? (boolean
                                      (some #(= :ragged-return-series (:code %))
                                            (:warnings ledoit-wolf-result)))
            covariance (case model-kind
                         :diagonal-shrink (diagonal-shrink @sample shrinkage)
                         :ledoit-wolf-dense (if ledoit-wolf-unavailable?
                                              @sample
                                              (:covariance ledoit-wolf-result))
                         :sample-covariance @sample
                         @sample)]
        (cond-> {:model model-kind
                 :instrument-ids instrument-ids
                 :covariance covariance
                 ;; concat, never merge: `select-keys` below runs through
                 ;; `merge`, so folding :warnings into it would OVERWRITE the
                 ;; cadence and :risk-model-renamed warnings in warnings*.
                 :warnings (vec (concat warnings*
                                        (:warnings ledoit-wolf-result)))}
          (= :ledoit-wolf-dense model-kind)
          (merge (select-keys ledoit-wolf-result
                              [:shrinkage :sample-count :feature-count]))

          (= :diagonal-shrink model-kind)
          (assoc :shrinkage {:kind :diagonal
                             :shrinkage shrinkage}))))))

(defn- positive-number?
  [value]
  (and (math/finite-number? value)
       (pos? value)))

(defn- diagonal-volatility
  [covariance idx]
  (let [variance (get-in covariance [idx idx])]
    (when (and (math/finite-number? variance)
               (pos? variance))
      (js/Math.sqrt variance))))

(defn augment-risk-result-with-assumptions
  "Override or append covariance rows for assets that carry a synthetic annualized
  volatility and a correlation floor against every other asset (conservative
  history assumptions). For an asset already present its realized row/column is
  replaced; for a no-history asset a new row/column is appended. The matrix is
  re-repaired to stay positive semidefinite.

  `assumptions-by-id` maps instrument-id -> {:volatility v :correlation-floor f}.
  Entries lacking a positive volatility or a finite floor are ignored."
  [risk-result assumptions-by-id]
  (let [assumptions (into {}
                          (filter (fn [[_ a]]
                                    (and (positive-number? (:volatility a))
                                         (math/finite-number? (:correlation-floor a)))))
                          assumptions-by-id)]
    (if (empty? assumptions)
      risk-result
      (let [base-ids (vec (:instrument-ids risk-result))
            base-cov (:covariance risk-result)
            base-id-set (set base-ids)
            assumption? (set (keys assumptions))
            new-ids (vec (remove base-id-set (keys assumptions)))
            ids (into base-ids new-ids)
            n (count ids)
            vol-by-id (into {}
                            (concat
                             (map-indexed (fn [idx id]
                                            [id (if (assumption? id)
                                                  (:volatility (get assumptions id))
                                                  (or (diagonal-volatility base-cov idx) 0))])
                                          base-ids)
                             (map (fn [id]
                                    [id (:volatility (get assumptions id))])
                                  new-ids)))
            floor-of (fn [id]
                       (when (assumption? id)
                         (:correlation-floor (get assumptions id))))
            covariance (mapv (fn [r]
                               (let [id-r (nth ids r)]
                                 (mapv (fn [c]
                                         (let [id-c (nth ids c)]
                                           (cond
                                             (= r c)
                                             (let [v (vol-by-id id-r)]
                                               (* v v))

                                             (or (assumption? id-r)
                                                 (assumption? id-c))
                                             (let [floor (max (or (floor-of id-r) 0)
                                                              (or (floor-of id-c) 0))]
                                               (* floor
                                                  (vol-by-id id-r)
                                                  (vol-by-id id-c)))

                                             :else
                                             (or (get-in base-cov [r c]) 0))))
                                       (range n))))
                             (range n))
            ;; Keep the repair warning. The sibling call site in
            ;; `estimate-risk-model` threads it through; this one used to
            ;; destructure only :covariance, so a matrix that a history
            ;; assumption pushed indefinite was diagonally loaded back into
            ;; shape and the user was never told.
            {repaired :covariance psd-warning :warning} (repair-psd covariance)]
        (cond-> (assoc risk-result
                       :instrument-ids ids
                       :covariance repaired)
          psd-warning
          (update :warnings #(vec (concat (or % []) [psd-warning]))))))))
