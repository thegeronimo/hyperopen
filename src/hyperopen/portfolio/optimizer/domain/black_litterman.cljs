(ns hyperopen.portfolio.optimizer.domain.black-litterman
  (:require [hyperopen.portfolio.optimizer.domain.math :as math]))

(def default-risk-aversion
  1)

(def default-tau
  0.05)

(def ^:private minimum-view-variance
  0.000000001)

(def ^:private minimum-confidence
  0.000001)

(def ^:private maximum-confidence
  0.999999)

(defn implied-equilibrium-returns
  [{:keys [risk-aversion covariance prior-weights]}]
  (math/scalar-vec (or risk-aversion default-risk-aversion)
                   (math/mat-vec covariance prior-weights)))

(defn- clamp
  [value low high]
  (-> value
      (max low)
      (min high)))

(defn- valid-prior-returns?
  [instrument-ids prior-returns]
  (and (= (count instrument-ids) (count prior-returns))
       (every? math/finite-number? prior-returns)))

(defn- prior-return-vector
  [{:keys [instrument-ids risk-aversion covariance prior-weights prior-returns]}]
  (if (valid-prior-returns? instrument-ids prior-returns)
    {:returns (vec prior-returns)
     :source :provided}
    {:returns (implied-equilibrium-returns
               {:risk-aversion (or risk-aversion default-risk-aversion)
                :covariance covariance
                :prior-weights prior-weights})
     :source :implied-equilibrium}))

(defn- view-row
  [instrument-ids view]
  (mapv #(or (get-in view [:weights %]) 0)
        instrument-ids))

(defn- view-rows
  [instrument-ids views]
  (mapv (partial view-row instrument-ids) views))

(defn- zero-view-row?
  [row]
  (every? zero? row))

(defn- view-instrument-ids
  [view]
  (mapv (fn [instrument-id]
          (if (keyword? instrument-id)
            (subs (str instrument-id) 1)
            (str instrument-id)))
        (keys (:weights view))))

(defn- invalid-view-row-warning
  [view]
  {:code :black-litterman-view-has-no-matching-instrument
   :view-id (:id view)
   :instrument-ids (view-instrument-ids view)})

(defn- invalid-view-row-warnings
  [views rows]
  (->> (map vector views rows)
       (keep (fn [[view row]]
               (when (zero-view-row? row)
                 (invalid-view-row-warning view))))
       vec))

(defn- view-returns
  [views]
  (mapv :return views))

(defn- legacy-confidence
  [view]
  (when (math/finite-number? (:confidence-variance view))
    (- 1 (:confidence-variance view))))

(defn- view-confidence
  [view]
  (clamp (or (when (math/finite-number? (:confidence view))
               (:confidence view))
             (legacy-confidence view)
             0.5)
         minimum-confidence
         maximum-confidence))

(defn- view-variance
  [tau-sigma view-row]
  (max minimum-view-variance
       (or (let [variance (math/dot view-row
                                    (math/mat-vec tau-sigma view-row))]
             (when (math/finite-number? variance)
               variance))
           0)))

(defn- view-omega
  [tau-sigma view view-row]
  (let [confidence (view-confidence view)]
    (* (view-variance tau-sigma view-row)
       (/ (- 1 confidence)
          confidence))))

(defn- omega
  [tau-sigma views view-rows]
  (math/diagonal-matrix
   (mapv #(view-omega tau-sigma %1 %2)
         views
         view-rows)))

(defn posterior-returns
  [{:keys [instrument-ids
           covariance
           prior-weights
           prior-returns
           risk-aversion
           tau
           views
           prior-source]}]
  (let [tau* (or tau default-tau)
        views* (vec (or views []))
        prior-return (prior-return-vector
                      {:instrument-ids instrument-ids
                       :risk-aversion (or risk-aversion default-risk-aversion)
                       :covariance covariance
                       :prior-weights prior-weights
                       :prior-returns prior-returns})
        pi (:returns prior-return)]
    (if (empty? views*)
      {:model :black-litterman
       :instrument-ids instrument-ids
       :expected-returns pi
       :expected-returns-by-instrument (zipmap instrument-ids pi)
       :diagnostics {:prior-source (or prior-source :market-cap)
                       :prior-return-source (:source prior-return)
                       :view-count 0
                       :tau tau*}}
      (let [p (view-rows instrument-ids views*)
            invalid-warnings (invalid-view-row-warnings views* p)]
        (if (seq invalid-warnings)
          {:status :invalid
           :model :black-litterman
           :instrument-ids instrument-ids
           :expected-returns pi
           :expected-returns-by-instrument (zipmap instrument-ids pi)
           :diagnostics {:prior-source (or prior-source :market-cap)
                         :prior-return-source (:source prior-return)
                         :view-count (count views*)
                         :tau tau*}
           :warnings invalid-warnings}
          (let [pt (math/transpose p)
                q (view-returns views*)
                tau-sigma (math/scalar-matrix tau* covariance)
                tau-sigma-inv (math/inverse tau-sigma)
                omega-inv (math/inverse (omega tau-sigma views* p))
                ;; P'omega^-1 appears in both the precision and the mean term.
                ;; It was evaluated twice from identical inputs.
                pt-omega-inv (math/mat-mul pt omega-inv)
                left (math/matrix-add tau-sigma-inv
                                      (math/mat-mul pt-omega-inv p))
                right (math/vec-add (math/mat-vec tau-sigma-inv pi)
                                    (math/mat-vec pt-omega-inv q))
                posterior (math/mat-vec (math/inverse left) right)]
            {:model :black-litterman
             :instrument-ids instrument-ids
             :expected-returns posterior
             :expected-returns-by-instrument (zipmap instrument-ids posterior)
             :diagnostics {:prior-source (or prior-source :market-cap)
                           :prior-return-source (:source prior-return)
                           :view-count (count views*)
                           :tau tau*}}))))))
