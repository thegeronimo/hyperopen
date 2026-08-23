(ns hyperopen.portfolio.optimizer.domain.risk-degeneracy-test
  "The two ways the risk model can publish a number that is not a risk estimate,
  and stay silent about it.

  Both shipped. On 2026-08-23 a four-times-levered book reported 8,697.7%
  annualized volatility with every asset - crypto, equities and metals alike -
  reading the same ~7,200%, while the results rail reported conditioning
  `Healthy`. See /hyperopen/docs/exec-plans/active/2026-08-23-optimizer-volatility-integrity.md."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.domain.risk :as risk]
            [hyperopen.portfolio.optimizer.domain.risk-ledoit-wolf :as ledoit-wolf]))

(defn- near?
  ([expected actual]
   (near? expected actual 0.0000001))
  ([expected actual tolerance]
   (< (js/Math.abs (- expected actual)) tolerance)))

(def ^:private twelve-day-returns
  "Three assets, twelve plausible daily returns each. Deliberately
  differentiated: A is the most volatile, C the least."
  {"A" [0.02 -0.03 0.01 0.04 -0.02 0.03 -0.01 0.02 -0.04 0.01 0.03 -0.02]
   "B" [0.01 -0.02 0.02 0.01 -0.01 0.02 -0.02 0.01 -0.01 0.02 0.01 -0.01]
   "C" [0.01 -0.01 0.01 0.02 -0.01 0.01 -0.01 0.01 -0.02 0.01 0.01 -0.01]})

(defn- diagonal
  [covariance n]
  (mapv #(get-in covariance [% %]) (range n)))

(defn- estimate
  [return-series-by-instrument]
  (risk/estimate-risk-model
   {:risk-model {:kind :ledoit-wolf-dense}
    :periods-per-year 365
    :history {:return-series-by-instrument return-series-by-instrument}}))

(deftest ledoit-wolf-dense-differentiates-volatility-on-clean-history-test
  ;; Baseline for the saturation test below: on plausible returns the estimator
  ;; shrinks only partially and the three assets keep distinct volatilities.
  (let [result (estimate twelve-day-returns)
        covariance (:covariance result)
        shrinkage (get-in result [:shrinkage :shrinkage])
        vols (mapv js/Math.sqrt (diagonal covariance 3))]
    (is (= :ledoit-wolf-dense (:model result)))
    (is (< shrinkage 0.99)
        "Clean history must not saturate the shrinkage.")
    (is (apply distinct? vols)
        "Clean history must keep per-asset volatility differentiated.")
    (is (not (zero? (get-in covariance [0 1])))
        "Clean history must retain cross-asset covariance.")
    (is (every? #(< % 5.0) vols)
        "Every volatility must be a market number.")
    (is (empty? (:warnings result))
        "Clean history must not warn.")))

(deftest ledoit-wolf-dense-saturates-to-a-scaled-identity-on-a-single-outlier-test
  ;; One +3000% print in ONE asset drives beta-hat/delta-hat above 1, the
  ;; (min 1) in risk_ledoit_wolf.cljs fires, and the covariance collapses to
  ;; 365*mu*I: every correlation discarded and the poisoned variance broadcast
  ;; onto all three diagonals. This is the whole-book failure the user saw.
  (let [poisoned (assoc-in twelve-day-returns ["A" 5] 30.0)
        result (estimate poisoned)
        covariance (:covariance result)
        diag (diagonal covariance 3)]
    (is (= 1 (get-in result [:shrinkage :shrinkage]))
        "One bad observation saturates the shrinkage.")
    (is (apply = diag)
        "Every asset receives the SAME variance: the matrix is a scaled identity.")
    (is (= 0 (get-in covariance [0 1])))
    (is (= 0 (get-in covariance [0 2])))
    (is (= 0 (get-in covariance [1 2])))
    (is (every? #(> (js/Math.sqrt %) 5.0) diag)
        "The broadcast volatility is far past any market number.")
    ;; The diagnostic the user actually looked at is blind to this by
    ;; construction: a condition number is a RATIO, and every eigenvalue of a
    ;; scaled identity is equal.
    (is (= 1 (:condition-number (risk/covariance-conditioning covariance))))
    (is (= :ok (:status (risk/covariance-conditioning covariance)))
        "Conditioning reports :ok, which the rail renders as Healthy.")
    ;; ...so the magnitude check must be the one that catches it.
    (is (= :implausible
           (:status (risk/covariance-plausibility covariance (:instrument-ids result))))
        "A magnitude check, unlike a conditioning ratio, must reject this matrix.")
    (is (some #(= :risk-shrinkage-saturated (:code %)) (:warnings result))
        "Full shrinkage discards every correlation; the run must say so.")))

(deftest ledoit-wolf-dense-ragged-history-must-not-return-a-silent-zero-covariance-test
  ;; :ledoit-wolf-dense is the LIVE DEFAULT (request-builder/default-risk-model),
  ;; so one short series made the whole portfolio read 0% volatility with no
  ;; explanation - the opposite failure, equally dishonest and more believable.
  (let [result (estimate {"A" [0.01 -0.02 0.03 -0.01]
                          "B" [0.02 -0.01 0.01]})
        covariance (:covariance result)]
    (is (= ["A" "B"] (:instrument-ids result)))
    (is (some #(= :ragged-return-series (:code %)) (:warnings result))
        "Ragged series lengths must be reported, not silently zeroed.")
    (is (not (every? zero? (flatten covariance)))
        "An all-zero covariance reports 0% portfolio volatility for a real book.")
    (is (pos? (get-in covariance [0 0]))
        "Asset A has four observations; its variance is knowable.")
    (is (pos? (get-in covariance [1 1]))
        "Asset B has three observations; its variance is knowable.")))

(deftest ledoit-wolf-estimator-reports-ragged-series-at-its-own-boundary-test
  ;; Unit-level twin: the estimator itself must name the condition, so the
  ;; fallback policy can live in estimate-risk-model rather than being guessed.
  (let [result (ledoit-wolf/estimate {:series [[0.01 0.02 0.03] [0.01 0.02]]
                                      :periods-per-year 365})
        warning (first (filter #(= :ragged-return-series (:code %))
                               (:warnings result)))]
    (is (some? warning)
        "The estimator must report that it could not run.")
    (is (= [3 2] (:series-lengths warning))
        "The warning names the offending lengths so the rail can say which asset is short.")))

(deftest ledoit-wolf-estimator-reports-shrinkage-saturation-at-its-own-boundary-test
  (let [clean [[0.02 -0.03 0.01 0.04 -0.02 0.03]
               [0.01 -0.02 0.02 0.01 -0.01 0.02]]
        poisoned [[0.02 -0.03 0.01 30.0 -0.02 0.03]
                  [0.01 -0.02 0.02 0.01 -0.01 0.02]]]
    (is (empty? (:warnings (ledoit-wolf/estimate {:series clean
                                                  :periods-per-year 365}))))
    (let [result (ledoit-wolf/estimate {:series poisoned :periods-per-year 365})
          warning (first (filter #(= :risk-shrinkage-saturated (:code %))
                                 (:warnings result)))]
      (is (some? warning))
      (is (near? 1 (:shrinkage warning) 0.011)
          "The warning carries the shrinkage that triggered it.")
      (is (= 6 (:sample-count warning)))
      (is (= 2 (:feature-count warning))))))

(deftest covariance-plausibility-accepts-a-market-covariance-test
  ;; The magnitude check must not fire on a normal levered crypto book: the
  ;; measured per-asset ceiling across 60 perpetuals was 390% annualized.
  (let [result (estimate twelve-day-returns)]
    (is (= :ok (:status (risk/covariance-plausibility (:covariance result)
                                                      (:instrument-ids result)))))))
