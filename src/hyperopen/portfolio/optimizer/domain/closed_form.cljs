(ns hyperopen.portfolio.optimizer.domain.closed-form
  "Closed-form selected-portfolio solving for equality-only mean-variance
  requests. Eligibility is deliberately conservative: any constraint beyond a
  single net-exposure equality, any numerical doubt, or any post-validation
  failure keeps the request on the existing QP solver path. Closed-form
  solutions are never clamped or renormalized to satisfy constraints.

  Numeric tolerances and post-validation live in
  domain.closed-form-support; the linear algebra lives in
  domain.linear-solve. Both are re-exported here so callers depend only on
  this namespace's public surface (eligible?, solve-portfolio,
  validate-solution)."
  (:require [hyperopen.portfolio.optimizer.domain.closed-form-support :as support]
            [hyperopen.portfolio.optimizer.domain.linear-solve :as linear-solve]
            [hyperopen.portfolio.optimizer.domain.math :as math]))

(def ^:private finite-number? math/finite-number?)
(def ^:private positive-beyond-epsilon? support/positive-beyond-epsilon?)

(def tolerances support/tolerances)
(def validate-solution support/validate-solution)

(def closed-form-objective-kinds
  #{:minimum-variance :target-return :max-sharpe :target-volatility})

(defn- frontier-moments
  "Solves the two systems covariance*u = 1 and covariance*v = mu in one
  elimination pass and derives the classical frontier scalars
  a = 1'u, b = 1'v, c = mu'v, d = a*c - b*b."
  [covariance expected-returns]
  (let [n (count expected-returns)
        ones (vec (repeat n 1))
        outcome (linear-solve/solve-systems covariance [ones expected-returns])]
    (if-not (= :solved (:status outcome))
      outcome
      (let [[u v] (:solutions outcome)]
        (if-not (and (linear-solve/residual-ok? covariance u ones)
                     (linear-solve/residual-ok? covariance v expected-returns))
          {:status :failed
           :reason :unstable-solve}
          (let [a (math/dot ones u)
                b (math/dot ones v)
                c (math/dot expected-returns v)
                d (- (* a c) (* b b))]
            (if (and (positive-beyond-epsilon? a)
                     (finite-number? b)
                     (finite-number? c)
                     (finite-number? d)
                     (>= c (- (:denominator tolerances))))
              {:status :solved
               :u u
               :v v
               :a a
               :b b
               :c c
               :d d}
              {:status :failed
               :reason :indefinite-covariance})))))))

(defn- gmv-weights
  [net-target {:keys [u a]}]
  (if (positive-beyond-epsilon? a)
    {:status :solved
     :weights (mapv #(* (/ net-target a) %) u)}
    {:status :failed
     :reason :indefinite-covariance}))

(defn- frontier-return-weights
  "Minimum-variance weights with both equalities active:
  sum(w) = net-target and mu'w = r-target."
  [net-target r-target {:keys [u v a b c d]}]
  (if-not (positive-beyond-epsilon? d)
    {:status :failed
     :reason :degenerate-frontier}
    (let [alpha (/ (- (* c net-target) (* b r-target)) d)
          beta (/ (- (* a r-target) (* b net-target)) d)]
      {:status :solved
       :weights (mapv (fn [u-value v-value]
                        (+ (* alpha u-value) (* beta v-value)))
                      u
                      v)})))

(defn- target-return-weights
  "The QP encodes the target as a return floor, so the GMV portfolio wins
  whenever its expected return already clears the target."
  [{:keys [objective expected-returns net-target moments]}]
  (let [r-target (:target-return objective)
        gmv (gmv-weights net-target moments)]
    (cond
      (not= :solved (:status gmv))
      gmv

      (>= (math/dot expected-returns (:weights gmv)) r-target)
      gmv

      :else
      (frontier-return-weights net-target r-target moments))))

(defn- max-sharpe-weights
  [{:keys [objective net-target moments]}]
  (let [risk-free-rate (or (:risk-free-rate objective) 0)
        {:keys [u v a b]} moments
        x (mapv (fn [v-value u-value]
                  (- v-value (* risk-free-rate u-value)))
                v
                u)
        denominator (- b (* risk-free-rate a))]
    (if (positive-beyond-epsilon? denominator)
      {:status :solved
       :weights (mapv #(* (/ net-target denominator) %) x)}
      {:status :failed
       :reason :non-positive-sharpe-denominator})))

(defn- target-volatility-weights
  "Maximum-return frontier portfolio at the requested volatility. Targets at
  or below the GMV volatility keep the existing sweep behavior."
  [{:keys [objective net-target moments]}]
  (let [sigma (:target-volatility objective)
        target-variance (* sigma sigma)
        {:keys [a b d]} moments
        discriminant-input (- (* a target-variance)
                              (* net-target net-target))
        clamp-window (* (:sqrt-clamp tolerances)
                        (max 1
                             (js/Math.abs (* a target-variance))
                             (* net-target net-target)))]
    (cond
      (not (positive-beyond-epsilon? a))
      {:status :failed
       :reason :indefinite-covariance}

      (< discriminant-input (- clamp-window))
      {:status :failed
       :reason :target-volatility-below-gmv}

      (not (positive-beyond-epsilon? d))
      {:status :failed
       :reason :degenerate-frontier}

      :else
      (let [r-plus (/ (+ (* b net-target)
                         (js/Math.sqrt (* d (max 0 discriminant-input))))
                      a)]
        (if (finite-number? r-plus)
          (frontier-return-weights net-target r-plus moments)
          {:status :failed
           :reason :non-finite-target-return})))))

(defn- objective-weights
  [{:keys [objective] :as inputs}]
  (case (:kind objective)
    :minimum-variance (gmv-weights (:net-target inputs) (:moments inputs))
    :target-return (target-return-weights inputs)
    :max-sharpe (max-sharpe-weights inputs)
    :target-volatility (target-volatility-weights inputs)
    {:status :failed
     :reason :unsupported-objective}))

(defn- net-equality-target
  "Mirrors the planner's equality encoding: the single net equality comes from
  a finite :net-target or an equal finite :net-exposure min/max."
  [encoded-constraints]
  (let [net-target (:net-target encoded-constraints)
        net-exposure (:net-exposure encoded-constraints)]
    (cond
      (finite-number? net-target)
      net-target

      (and (map? net-exposure)
           (= (:min net-exposure) (:max net-exposure))
           (finite-number? (:min net-exposure)))
      (:min net-exposure))))

(defn- active-net-range?
  "Mirrors the planner's net-exposure inequality encoding."
  [encoded-constraints]
  (let [net-exposure (:net-exposure encoded-constraints)]
    (and (map? net-exposure)
         (not= (:min net-exposure) (:max net-exposure))
         (or (finite-number? (:min net-exposure))
             (finite-number? (:max net-exposure))))))

(defn- objective-params-failure
  [objective]
  (case (:kind objective)
    :target-return
    (when-not (finite-number? (:target-return objective))
      :non-finite-target-return)

    :max-sharpe
    (when-not (or (nil? (:risk-free-rate objective))
                  (finite-number? (:risk-free-rate objective)))
      :non-finite-risk-free-rate)

    :target-volatility
    (when-not (and (finite-number? (:target-volatility objective))
                   (pos? (:target-volatility objective)))
      :non-finite-target-volatility)

    nil))

(defn- square-finite-covariance?
  [covariance n]
  (and (sequential? covariance)
       (= n (count covariance))
       (every? (fn [row]
                 (and (sequential? row)
                      (= n (count row))
                      (every? finite-number? row)))
               covariance)))

(defn- symmetric-covariance?
  [covariance]
  (let [tol (:symmetry tolerances)]
    (every? (fn [[row col]]
              (let [left (get-in covariance [row col])
                    right (get-in covariance [col row])]
                (<= (js/Math.abs (- left right))
                    (* tol (max 1
                                (js/Math.abs left)
                                (js/Math.abs right))))))
            (for [row (range (count covariance))
                  col (range row)]
              [row col]))))

(defn- core-eligibility
  [{:keys [objective instrument-ids expected-returns covariance
           encoded-constraints return-tilts]}]
  (let [n (count instrument-ids)
        net-target (net-equality-target encoded-constraints)
        param-failure (objective-params-failure objective)]
    (cond
      (not (contains? closed-form-objective-kinds (:kind objective)))
      {:eligible? false :reason :unsupported-objective}

      param-failure
      {:eligible? false :reason param-failure}

      (some? return-tilts)
      {:eligible? false :reason :explicit-return-tilts}

      (= :infeasible (:status encoded-constraints))
      {:eligible? false :reason :constraints-infeasible}

      (zero? n)
      {:eligible? false :reason :empty-universe}

      (not (and (sequential? expected-returns)
                (= n (count expected-returns))
                (every? finite-number? expected-returns)))
      {:eligible? false :reason :invalid-expected-returns}

      (not (square-finite-covariance? covariance n))
      {:eligible? false :reason :invalid-covariance}

      (not (symmetric-covariance? covariance))
      {:eligible? false :reason :asymmetric-covariance}

      (active-net-range? encoded-constraints)
      {:eligible? false :reason :net-exposure-range}

      (some? (:net-band-spec encoded-constraints))
      {:eligible? false :reason :net-band-pct}

      (nil? net-target)
      {:eligible? false :reason :missing-net-equality}

      :else
      {:eligible? true :net-target net-target})))

(defn- compute-solution
  "Solves the equality-core candidate and post-validates it against ALL encoded
  constraints. On validation failure the reason is the specific
  rejection-reason so the caller can fall back and report it."
  [{:keys [objective expected-returns covariance encoded-constraints]} net-target]
  (let [moments (frontier-moments covariance expected-returns)]
    (if-not (= :solved (:status moments))
      moments
      (let [weights-outcome (objective-weights {:objective objective
                                                :expected-returns expected-returns
                                                :net-target net-target
                                                :moments moments})]
        (if-not (= :solved (:status weights-outcome))
          weights-outcome
          (let [weights (:weights weights-outcome)
                validation (validate-solution {:weights weights
                                               :net-target net-target
                                               :objective objective
                                               :expected-returns expected-returns
                                               :covariance covariance
                                               :encoded-constraints encoded-constraints})]
            (if (:valid? validation)
              {:status :solved
               :weights weights
               :metrics (:metrics validation)}
              {:status :failed
               :reason (support/rejection-reason (:violations validation))
               :violations (:violations validation)})))))))

(defn eligible?
  "Returns data, not a boolean. {:eligible? true :net-target b} only when the
  closed-form candidate solves and PASSES post-validation against every encoded
  constraint; otherwise {:eligible? false :reason ...} so callers keep the QP
  path. The :reason is either a core precondition or a candidate rejection."
  [opts]
  (let [core (core-eligibility opts)]
    (if-not (:eligible? core)
      core
      (let [outcome (compute-solution opts (:net-target core))]
        (if (= :solved (:status outcome))
          core
          {:eligible? false :reason (:reason outcome)})))))

(defn- accepted-diagnostics
  [problem]
  {:strategy :closed-form
   :closed-form? true
   :closed-form-accepted? true
   :closed-form-post-validated? true
   :fallback? false
   :objective-kind (get-in problem [:objective :kind])
   :eligibility-reason nil})

(defn- rejected-diagnostics
  [problem reason]
  {:strategy :closed-form
   :closed-form? true
   :closed-form-candidate-rejected? true
   :fallback? true
   :objective-kind (get-in problem [:objective :kind])
   :rejection-reason reason})

(defn solve-portfolio
  "Solves a :closed-form-portfolio problem produced by solver planning.
  Pure and deterministic: it re-runs the exact eligibility + post-validation
  pipeline used at planning time, so a planned (already-accepted) problem
  cannot fail here for a new reason. A :solved result carries the accepted
  candidate; otherwise an :error result signals fallback with a rejection
  reason. Never returns clamped, repaired, or invalid weights."
  [problem]
  (let [core (core-eligibility problem)
        outcome (when (:eligible? core)
                  (compute-solution problem (:net-target core)))]
    (if (= :solved (:status outcome))
      {:status :solved
       :weights (:weights outcome)
       :solver :closed-form
       :iterations 0
       :elapsed-ms 0
       :objective-value (get-in outcome [:metrics :variance])
       :diagnostics (accepted-diagnostics problem)}
      (let [reason (or (:reason outcome)
                       (:reason core)
                       :ineligible)]
        {:status :error
         :solver :closed-form
         :reason reason
         :diagnostics (rejected-diagnostics problem reason)}))))
