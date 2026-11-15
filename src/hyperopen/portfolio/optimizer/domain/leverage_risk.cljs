(ns hyperopen.portfolio.optimizer.domain.leverage-risk
  "Closed-form one-year outcome model for a levered portfolio, from the run's
  annualized arithmetic expected return μ and annualized volatility σ (both
  decimal fractions). Explanatory analytics only — never feeds the solver.

  Model (stated on the card that renders it): lognormal / geometric Brownian
  motion with annual log-growth ~ Normal(ν, σ) where ν = ln(1+μ) − σ²/2.
  This parameterization keeps the modeled MEAN ending equity exactly
  (1+μ)·start — it can never contradict the expected-return KPI — while the
  MEDIAN e^ν carries the volatility drag that makes high-σ leverage risky.

  The result payload carries no per-asset maintenance margins, so no
  liquidation probability is computed; the honest proxy provided is the
  probability of TOUCHING a 50% drawdown from starting equity at any point in
  the year (first passage), which for a levered book is a lower bound on
  ruin-type risk because forced liquidation typically fires first.

  Everything is deterministic and pure; invalid inputs (μ ≤ −100%, negative σ,
  NaN, infinities) yield nil rather than a number."
  (:require [hyperopen.portfolio.optimizer.domain.math :as math]))

(def ^:private z-95
  "Standard-normal 95th percentile (two-sided 90% band edge)."
  1.6448536269514722)

(defn normal-cdf
  "Standard normal CDF Φ(x) via the Abramowitz–Stegun 7.1.26 erf
  approximation (absolute error < 1.5e-7). nil for non-finite input."
  [x]
  (when (math/finite-number? x)
    (let [z (/ (js/Math.abs x) (js/Math.sqrt 2))
          t (/ 1 (+ 1 (* 0.3275911 z)))
          poly (* t
                  (+ 0.254829592
                     (* t
                        (+ -0.284496736
                           (* t
                              (+ 1.421413741
                                 (* t
                                    (+ -1.453152027
                                       (* t 1.061405429)))))))))
          erf (- 1 (* poly (js/Math.exp (- (* z z)))))
          phi (* 0.5 (+ 1 erf))]
      (if (neg? x) (- 1 phi) phi))))

(defn- valid-inputs?
  [mu sigma]
  (and (math/finite-number? mu)
       (math/finite-number? sigma)
       (> mu -1)
       (>= sigma 0)))

(defn- terminal-loss-probability
  "P(ending equity ≤ barrier·start) after one year."
  [nu sigma barrier]
  (if (zero? sigma)
    (if (<= (js/Math.exp nu) barrier) 1 0)
    (normal-cdf (/ (- (js/Math.log barrier) nu) sigma))))

(defn- touch-probability
  "P(equity touches barrier·start at any time within one year): first passage
  of GBM, Φ((ln b − ν)/σ) + b^(2ν/σ²)·Φ((ln b + ν)/σ), computed with the
  reflection term in log space so extreme drifts cannot overflow into NaN.
  Clamped to [P(terminal ≤ b), 1], which are the true mathematical bounds."
  [nu sigma barrier]
  (if (zero? sigma)
    (if (<= (js/Math.exp nu) barrier) 1 0)
    (let [lb (js/Math.log barrier)
          terminal (normal-cdf (/ (- lb nu) sigma))
          phi2 (normal-cdf (/ (+ lb nu) sigma))
          k (/ (* 2 nu) (* sigma sigma))
          reflection (when (and (some? phi2) (pos? phi2))
                       (js/Math.exp (+ (* k lb) (js/Math.log phi2))))
          raw (+ terminal (or reflection 0))]
      (-> raw
          (max terminal)
          (min 1)
          (max 0)))))

(defn outcome-model
  "One-year modeled outcome factors (ending equity ÷ starting equity) and
  loss probabilities:

    {:log-drift ν
     :log-sigma σ
     :median-ending-factor e^ν
     :p5-ending-factor     e^(ν − 1.645σ)
     :p95-ending-factor    e^(ν + 1.645σ)
     :mean-ending-factor   1+μ
     :prob-terminal-loss-half   P(ending ≤ half of start)
     :prob-touch-half-drawdown  P(touching half of start during the year)}

  ν and σ are the lognormal model's own parameters (annual log-growth ~
  Normal(ν, σ)) so a view can draw the ending-wealth distribution without
  re-deriving them from the factors.

  nil when μ/σ are missing or invalid (μ ≤ −100%, σ < 0, NaN, ∞)."
  [{:keys [expected-return volatility]}]
  (let [mu expected-return
        sigma volatility]
    (when (valid-inputs? mu sigma)
      (let [nu (- (js/Math.log (+ 1 mu)) (/ (* sigma sigma) 2))
            spread (* z-95 sigma)]
        {:log-drift nu
         :log-sigma sigma
         :median-ending-factor (js/Math.exp nu)
         :p5-ending-factor (js/Math.exp (- nu spread))
         :p95-ending-factor (js/Math.exp (+ nu spread))
         :mean-ending-factor (+ 1 mu)
         :prob-terminal-loss-half (terminal-loss-probability nu sigma 0.5)
         :prob-touch-half-drawdown (touch-probability nu sigma 0.5)}))))
