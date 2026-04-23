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

(defn estimate-risk-model
  [{:keys [risk-model periods-per-year history]}]
  (let [risk-model* (or risk-model {:kind :ledoit-wolf})
        periods-per-year* (or periods-per-year default-periods-per-year)
        instrument-ids (vec (sorted-instrument-ids history))
        series (series-by-id history instrument-ids)
        sample (covariance-matrix series periods-per-year*)
        shrinkage (or (:shrinkage risk-model*) default-shrinkage)
        covariance (case (:kind risk-model*)
                     :ledoit-wolf (diagonal-shrink sample shrinkage)
                     :sample-covariance sample
                     sample)]
    (cond-> {:model (:kind risk-model*)
             :instrument-ids instrument-ids
             :covariance covariance
             :warnings []}
      (= :ledoit-wolf (:kind risk-model*))
      (assoc :shrinkage {:kind :diagonal
                         :shrinkage shrinkage}))))

(defn covariance-conditioning
  [covariance]
  (let [diagonal (filter math/finite-number? (math/diagonal covariance))
        positive (filter pos? diagonal)
        min-diag (when (seq positive) (apply min positive))
        max-diag (when (seq positive) (apply max positive))
        condition-number (when (and (number? min-diag)
                                    (pos? min-diag))
                           (/ max-diag min-diag))]
    {:condition-number condition-number
     :min-diagonal min-diag
     :max-diagonal max-diag
     :status (cond
               (nil? condition-number) :unknown
               (> condition-number 1000000) :ill-conditioned
               :else :ok)}))
