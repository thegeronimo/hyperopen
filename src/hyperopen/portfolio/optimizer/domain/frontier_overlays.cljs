(ns hyperopen.portfolio.optimizer.domain.frontier-overlays
  (:require [hyperopen.portfolio.optimizer.domain.math :as math]))

(defn- sqrt
  [value]
  (js/Math.sqrt (max 0 (or value 0))))

(defn- finite-number?
  [value]
  (math/finite-number? value))

(defn asset-overlay-points
  [{:keys [instrument-ids
           target-weights
           expected-returns
           covariance
           labels-by-instrument]}]
  (let [weights (vec (or target-weights []))
        returns (vec (or expected-returns []))
        sigma-w (math/mat-vec covariance weights)
        portfolio-volatility (sqrt (math/portfolio-variance weights covariance))
        contribution-volatility? (and (finite-number? portfolio-volatility)
                                      (pos? portfolio-volatility))]
    (mapv (fn [idx instrument-id]
            (let [target-weight (or (nth weights idx nil) 0)
                  expected-return (or (nth returns idx nil) 0)
                  standalone-volatility (sqrt (get-in covariance [idx idx]))
                  contribution-volatility (when contribution-volatility?
                                            (/ (* target-weight
                                                  (or (nth sigma-w idx nil) 0))
                                               portfolio-volatility))]
              {:instrument-id instrument-id
               :label (or (get labels-by-instrument instrument-id)
                          instrument-id)
               :target-weight target-weight
               :standalone {:expected-return expected-return
                            :volatility standalone-volatility}
               :contribution {:expected-return (* target-weight expected-return)
                              :volatility contribution-volatility}}))
          (range (count (or instrument-ids [])))
          (or instrument-ids []))))

(defn overlay-series
  [opts]
  (let [rows (asset-overlay-points opts)
        flatten-kind (fn [kind]
                       (mapv (fn [row]
                               (let [metrics (get row kind)]
                                 (assoc row
                                        :expected-return (:expected-return metrics)
                                        :volatility (:volatility metrics))))
                             rows))]
    {:standalone (flatten-kind :standalone)
     :contribution (flatten-kind :contribution)}))
