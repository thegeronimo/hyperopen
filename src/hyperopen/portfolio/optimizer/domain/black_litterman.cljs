(ns hyperopen.portfolio.optimizer.domain.black-litterman
  (:require [hyperopen.portfolio.optimizer.domain.math :as math]))

(def default-risk-aversion
  1)

(def default-tau
  0.05)

(defn implied-equilibrium-returns
  [{:keys [risk-aversion covariance prior-weights]}]
  (math/scalar-vec (or risk-aversion default-risk-aversion)
                   (math/mat-vec covariance prior-weights)))

(defn- view-row
  [instrument-ids view]
  (mapv #(or (get-in view [:weights %]) 0)
        instrument-ids))

(defn- view-rows
  [instrument-ids views]
  (mapv (partial view-row instrument-ids) views))

(defn- view-returns
  [views]
  (mapv :return views))

(defn- omega
  [views]
  (math/diagonal-matrix
   (mapv #(or (:confidence-variance %) 1)
         views)))

(defn posterior-returns
  [{:keys [instrument-ids
           covariance
           prior-weights
           risk-aversion
           tau
           views
           prior-source]}]
  (let [tau* (or tau default-tau)
        views* (vec (or views []))
        pi (implied-equilibrium-returns
            {:risk-aversion (or risk-aversion default-risk-aversion)
             :covariance covariance
             :prior-weights prior-weights})]
    (if (empty? views*)
      {:model :black-litterman
       :instrument-ids instrument-ids
       :expected-returns pi
       :expected-returns-by-instrument (zipmap instrument-ids pi)
       :diagnostics {:prior-source (or prior-source :market-cap)
                     :view-count 0
                     :tau tau*}}
      (let [p (view-rows instrument-ids views*)
            pt (math/transpose p)
            q (view-returns views*)
            tau-sigma (math/scalar-matrix tau* covariance)
            tau-sigma-inv (math/inverse tau-sigma)
            omega-inv (math/inverse (omega views*))
            left (math/matrix-add tau-sigma-inv
                                  (math/mat-mul
                                   (math/mat-mul pt omega-inv)
                                   p))
            right (math/vec-add (math/mat-vec tau-sigma-inv pi)
                                (math/mat-vec
                                 (math/mat-mul pt omega-inv)
                                 q))
            posterior (math/mat-vec (math/inverse left) right)]
        {:model :black-litterman
         :instrument-ids instrument-ids
         :expected-returns posterior
         :expected-returns-by-instrument (zipmap instrument-ids posterior)
         :diagnostics {:prior-source (or prior-source :market-cap)
                       :view-count (count views*)
                       :tau tau*}}))))
