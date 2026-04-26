(ns hyperopen.portfolio.optimizer.domain.risk
  (:require [hyperopen.portfolio.optimizer.domain.math :as math]))

(def default-periods-per-year
  365)

(def default-shrinkage
  0.1)

(defn- sorted-instrument-ids
  [history]
  (sort (keys (:return-series-by-instrument history))))

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
    :diagonal-shrink :diagonal-shrink
    :sample-covariance :sample-covariance
    kind))

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

(defn estimate-risk-model
  [{:keys [risk-model periods-per-year history]}]
  (let [risk-model* (or risk-model {:kind :diagonal-shrink})
        model-kind (normalize-risk-model-kind (:kind risk-model*))
        periods-per-year* (or periods-per-year default-periods-per-year)
        instrument-ids (vec (sorted-instrument-ids history))
        series (series-by-id history instrument-ids)
        sample (covariance-matrix series periods-per-year*)
        shrinkage (or (:shrinkage risk-model*) default-shrinkage)
        covariance (case model-kind
                     :diagonal-shrink (diagonal-shrink sample shrinkage)
                     :sample-covariance sample
                     sample)]
    (cond-> {:model model-kind
             :instrument-ids instrument-ids
             :covariance covariance
             :warnings (cond-> []
                         (= :ledoit-wolf (:kind risk-model*))
                         (conj {:code :risk-model-renamed
                                :from :ledoit-wolf
                                :to :diagonal-shrink}))}
      (= :diagonal-shrink model-kind)
      (assoc :shrinkage {:kind :diagonal
                         :shrinkage shrinkage}))))

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
