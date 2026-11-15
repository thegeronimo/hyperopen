(ns hyperopen.portfolio.optimizer.domain.history-assumption-proxy-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.domain.history-assumption-proxy :as proxy]
            [hyperopen.portfolio.optimizer.domain.risk :as risk]))

(defn- near?
  ([expected actual] (near? expected actual 1e-9))
  ([expected actual tolerance]
   (and (number? actual)
        (< (js/Math.abs (- expected actual)) tolerance))))

(deftest build-proxy-prior-uses-valid-user-weights-test
  (let [{:keys [weights source warnings]}
        (proxy/build-proxy-prior ["perp:BTC" "perp:ETH"]
                                 {"perp:BTC" 2 "perp:ETH" 6})]
    (is (near? 0.25 (get weights "perp:BTC")))
    (is (near? 0.75 (get weights "perp:ETH")))
    (is (= :user source))
    (is (empty? warnings))))

(deftest build-proxy-prior-falls-back-to-equal-weight-test
  (testing "no user prior => equal weight, labeled :equal, no warning"
    (let [{:keys [weights source warnings]}
          (proxy/build-proxy-prior ["perp:BTC" "perp:ETH" "perp:SOL"] nil)]
      (is (every? #(near? (/ 1 3) %) (vals weights)))
      (is (= :equal source))
      (is (empty? warnings))))
  (testing "invalid user prior (negative / zero total / missing id) => equal weight + warning"
    (doseq [bad [{"perp:BTC" -1 "perp:ETH" 2}
                 {"perp:BTC" 0 "perp:ETH" 0}
                 {"perp:BTC" 1}]]
      (let [{:keys [weights source warnings]}
            (proxy/build-proxy-prior ["perp:BTC" "perp:ETH"] bad)]
        (is (near? 0.5 (get weights "perp:BTC")))
        (is (near? 0.5 (get weights "perp:ETH")))
        (is (= :equal source) "A rejected prior is labeled the equal fallback, never :user.")
        (is (some #(= :proxy-prior-weights-invalid (:code %)) warnings)
            "Rejected user weights warn - never a silent fallback."))))
  (testing "no proxies => nil"
    (is (nil? (proxy/build-proxy-prior [] {"perp:BTC" 1})))))

(deftest min-regression-observations-floors-at-ten-test
  (is (= 10 (proxy/min-regression-observations 1)))
  (is (= 10 (proxy/min-regression-observations 2)))
  (is (= 10 (proxy/min-regression-observations 7)))
  (is (= 11 (proxy/min-regression-observations 8))
      "Wide baskets need k + 3 observations."))

(deftest confidence-q-stays-low-for-tiny-samples-test
  (is (near? (/ 10 130) (proxy/confidence-q 10 1.0)))
  (is (> 0.08 (proxy/confidence-q 10 1.0)))
  (is (zero? (proxy/confidence-q 0 0.9)))
  (is (zero? (proxy/confidence-q 10 nil)))
  (is (near? (* (/ 240 360) 0.5) (proxy/confidence-q 240 0.25)))
  (is (near? (/ 240 360) (proxy/confidence-q 240 0.9))))

(deftest blend-exposure-is-prior-at-zero-confidence-test
  (let [prior {"perp:BTC" 0.5 "perp:ETH" 0.5}]
    (is (= prior (proxy/blend-exposure prior {"perp:BTC" 1 "perp:ETH" 0} 0)))
    (is (= prior (proxy/blend-exposure prior nil 0.4)))))

(deftest blend-exposure-shrinks-between-prior-and-regression-test
  (let [prior {"perp:BTC" 0.5 "perp:ETH" 0.5}
        regression {"perp:BTC" 1.0 "perp:ETH" 0.0}
        blended (proxy/blend-exposure prior regression 0.5)]
    (is (near? 0.75 (get blended "perp:BTC")))
    (is (near? 0.25 (get blended "perp:ETH")))
    (is (near? 1.0 (reduce + (vals blended))) "Exposure stays a normalized basket.")))

(defn- regression-series
  "y follows BTC exactly; ETH is noise-free but unrelated."
  [n]
  (let [btc (vec (map-indexed (fn [idx _] (if (even? idx) 0.01 -0.008)) (range n)))
        eth (vec (map-indexed (fn [idx _] (if (zero? (mod idx 3)) 0.004 -0.002)) (range n)))]
    {:x-returns btc
     :proxy-returns-by-id {"perp:BTC" btc
                           "perp:ETH" eth}
     :observations n}))

(deftest estimate-regression-uses-joint-fit-not-r2-weights-test
  (let [result (proxy/estimate-regression
                {:regression-series (regression-series 200)
                 :proxy-ids ["perp:BTC" "perp:ETH"]
                 :prior-weights {"perp:BTC" 0.5 "perp:ETH" 0.5}})]
    (is (= :estimated (:status result)))
    (is (= 200 (:sample-count result)))
    (is (> (:r2 result) 0.9) "y is BTC, so the joint fit is near-perfect.")
    (is (> (get-in result [:beta "perp:BTC"]) 0.6)
        "The joint regression tilts toward BTC...")
    (is (< (get-in result [:beta "perp:ETH"]) 0.4)
        "...but never collapses to an R2-as-weights split.")
    (is (> (get-in result [:beta "perp:BTC"])
           (get-in result [:beta "perp:ETH"])))
    (is (near? 1.0 (reduce + (vals (:beta result)))) "V1 policy: basket sums to 1.")))

(deftest estimate-regression-skips-tiny-or-degenerate-overlap-test
  (is (= :no-overlap
         (:reason (proxy/estimate-regression
                   {:regression-series nil
                    :proxy-ids ["perp:BTC"]
                    :prior-weights {"perp:BTC" 1}}))))
  (is (= :insufficient-overlap
         (:reason (proxy/estimate-regression
                   {:regression-series (regression-series 2)
                    :proxy-ids ["perp:BTC" "perp:ETH"]
                    :prior-weights {"perp:BTC" 0.5 "perp:ETH" 0.5}}))))
  (is (= :insufficient-overlap
         (:reason (proxy/estimate-regression
                   {:regression-series (regression-series 8)
                    :proxy-ids ["perp:BTC" "perp:ETH"]
                    :prior-weights {"perp:BTC" 0.5 "perp:ETH" 0.5}})))
      "Eight observations sit under the ten-observation floor - never model evidence."))

(defn- blended-regression-series
  "Deterministic synthetic overlap: the target is 70% ETH + 30% BTC plus small
  deterministic noise; ETH/BTC ride incommensurate cycles so they are far from
  collinear."
  [n]
  (let [eth (mapv (fn [i] (* 0.02 (js/Math.sin (* 0.9 i)))) (range n))
        btc (mapv (fn [i] (* 0.015 (js/Math.cos (* 0.7 i)))) (range n))
        noise (mapv (fn [i] (* 0.004 (js/Math.sin (* 3.7 i)))) (range n))
        y (mapv (fn [e b z] (+ (* 0.7 e) (* 0.3 b) z)) eth btc noise)]
    {:x-returns y
     :proxy-returns-by-id {"perp:ETH" eth
                           "perp:BTC" btc}
     :observations n}))

(deftest model-exposure-recovers-the-true-split-with-enough-overlap-test
  (let [result (proxy/model-exposure
                {:proxy-ids ["perp:ETH" "perp:BTC"]
                 :user-prior-weights nil
                 :regression-series (blended-regression-series 248)})
        final-eth (get-in result [:final-beta "perp:ETH"])]
    (is (= :equal (:prior-source result)))
    (is (near? 0.5 (get-in result [:prior-weights "perp:ETH"])))
    (is (= :estimated (get-in result [:regression :status])))
    (is (> (get-in result [:regression :r2]) 0.5))
    (is (> (:confidence-q result) 0.4))
    (is (< (:confidence-q result) 0.75)
        "Even a strong fit on 248 days never earns full confidence.")
    (is (> final-eth 0.53) "The final basket tilts toward the 70% driver...")
    (is (< final-eth 0.72) "...but stays shrunk toward the prior.")
    (is (not= (:final-beta result) (:prior-weights result)))
    (is (near? 1.0 (reduce + (vals (:final-beta result))) 1e-9))))

(deftest model-exposure-stays-prior-dominant-on-tiny-samples-test
  (let [result (proxy/model-exposure
                {:proxy-ids ["perp:ETH" "perp:BTC"]
                 :user-prior-weights nil
                 :regression-series (blended-regression-series 10)})
        final-eth (get-in result [:final-beta "perp:ETH"])]
    (is (= :estimated (get-in result [:regression :status]))
        "Ten observations sit exactly at the floor.")
    (is (< (:confidence-q result) 0.08)
        "q stays low for 10 observations even when the in-sample fit looks great.")
    (is (near? 0.5 final-eth 0.05)
        "The final basket stays close to the prior on a tiny sample.")))

(deftest model-exposure-returns-prior-exactly-without-overlap-test
  (let [result (proxy/model-exposure
                {:proxy-ids ["perp:BTC" "perp:ETH"]
                 :user-prior-weights nil
                 :regression-series nil})]
    (is (= :skipped (get-in result [:regression :status])))
    (is (= :no-overlap (get-in result [:regression :reason])))
    (is (zero? (:confidence-q result)))
    (is (= (:prior-weights result) (:final-beta result))
        "q = 0 => the final basket IS the prior.")))

(deftest model-exposure-honors-user-prior-weights-test
  (let [result (proxy/model-exposure
                {:proxy-ids ["perp:ETH" "perp:BTC"]
                 :user-prior-weights {"perp:ETH" 3 "perp:BTC" 1}
                 :regression-series nil})]
    (is (= :user (:prior-source result)))
    (is (near? 0.75 (get-in result [:prior-weights "perp:ETH"])))
    (is (near? 0.75 (get-in result [:final-beta "perp:ETH"]))
        "No overlap => the user's prior is the final basket.")))

(deftest specific-variance-is-floored-and-positive-test
  (testing "user variance above systematic: the gap wins"
    (is (near? 0.39 (proxy/specific-variance {:user-variance 0.64
                                              :systematic-variance 0.25
                                              :residual-variance nil
                                              :min-share 0.25}))))
  (testing "systematic near user variance: the floor keeps specific risk nonzero"
    (is (near? 0.16 (proxy/specific-variance {:user-variance 0.64
                                              :systematic-variance 0.6
                                              :residual-variance nil
                                              :min-share 0.25}))))
  (testing "residual variance can lift the floor"
    (is (near? 0.3 (proxy/specific-variance {:user-variance 0.64
                                             :systematic-variance 0.6
                                             :residual-variance 0.3
                                             :min-share 0.25}))))
  (testing "never negative"
    (is (near? 0.0 (proxy/specific-variance {:user-variance 0
                                             :systematic-variance 0.5
                                             :residual-variance nil
                                             :min-share 0})))))

(def ^:private base-risk-result
  {:model :sample-covariance
   :instrument-ids ["perp:BTC" "perp:ETH"]
   :covariance [[0.04 0.03]
                [0.03 0.09]]})

(def ^:private tokenx-entry
  {:behavior :proxy
   :volatility 0.8
   :max-weight 0.05
   :relationship-strength :medium
   :proxy-instrument-ids ["perp:BTC" "perp:ETH"]
   :proxy-prior-weights nil
   :regression-series nil})

(deftest augment-appends-proxy-row-from-proxy-covariance-test
  (let [augmented (proxy/augment-risk-result-with-proxy-assumptions
                   base-risk-result
                   {"perp:TOKENX" tokenx-entry})
        cov (:covariance augmented)
        diag (get-in augmented [:risk-estimation :history-assumptions "perp:TOKENX"])]
    (is (= ["perp:BTC" "perp:ETH" "perp:TOKENX"] (:instrument-ids augmented)))
    (is (near? 0.035 (get-in cov [0 2]) 1e-6))
    (is (near? 0.06 (get-in cov [1 2]) 1e-6))
    (is (near? (get-in cov [0 2]) (get-in cov [2 0])) "Row/column stay symmetric.")
    (is (pos? (get-in cov [0 2]))
        "Proxy covariance is synthesized from the basket, never zero correlation.")
    (is (near? 0.64 (get-in cov [2 2]) 1e-6)
        "Total variance honors the user's stated volatility when it exceeds systematic.")
    (is (zero? (:confidence-q diag)))
    (is (nil? (:r2 diag)))
    (is (= {"perp:BTC" 0.5 "perp:ETH" 0.5} (:final-beta diag)))
    (is (= :equal (:prior-source diag))
        "No explicit prior => the equal fallback is labeled as such.")
    (is (near? 0.0475 (:systematic-variance diag) 1e-6))
    (is (pos? (:specific-variance diag)))
    (is (near? 0.8 (:input-volatility diag)))
    (is (near? 0.8 (:effective-modeled-volatility diag) 1e-6)
        "Var(X) honors the stated volatility here, so effective vol == input vol.")))

(deftest augment-replaces-row-when-asset-already-present-test
  (let [base {:model :sample-covariance
              :instrument-ids ["perp:BTC" "perp:ETH" "perp:TOKENX"]
              :covariance [[0.04 0.03 0.001]
                           [0.03 0.09 0.0005]
                           [0.001 0.0005 0.0002]]}
        augmented (proxy/augment-risk-result-with-proxy-assumptions
                   base
                   {"perp:TOKENX" tokenx-entry})
        cov (:covariance augmented)]
    (is (= ["perp:BTC" "perp:ETH" "perp:TOKENX"] (:instrument-ids augmented))
        "The asset keeps its position; no duplicate row is appended.")
    (is (near? 0.035 (get-in cov [0 2]) 1e-6)
        "The thin realized row is replaced by the synthesized one.")
    (is (near? 0.64 (get-in cov [2 2]) 1e-6))
    (is (near? 0.04 (get-in cov [0 0])) "Other rows are untouched.")))

(deftest augment-uses-regression-when-overlap-exists-test
  (let [entry (assoc tokenx-entry
                     :regression-series (regression-series 200))
        augmented (proxy/augment-risk-result-with-proxy-assumptions
                   base-risk-result
                   {"perp:TOKENX" entry}
                   {:periods-per-year 365})
        diag (get-in augmented [:risk-estimation :history-assumptions "perp:TOKENX"])]
    (is (pos? (:confidence-q diag)))
    (is (> (get-in diag [:final-beta "perp:BTC"]) 0.5)
        "The regression tilts the basket toward the proxy the asset tracks.")
    (is (> (get-in diag [:final-beta "perp:BTC"]) (get-in diag [:final-beta "perp:ETH"])))
    (is (near? 1.0 (reduce + (vals (:final-beta diag))) 1e-6))
    (is (= 200 (:sample-count diag)))
    (is (pos? (:specific-variance diag)))
    (is (map? (:regression-beta-raw diag))
        "The unclamped regression coefficients are disclosed alongside the policy beta.")
    (is (near? 0.8 (:effective-modeled-volatility diag) 1e-6))))

(deftest covariance-row-follows-final-beta-not-prior-test
  (let [prior-only (proxy/augment-risk-result-with-proxy-assumptions
                    base-risk-result
                    {"perp:TOKENX" tokenx-entry})
        adjusted (proxy/augment-risk-result-with-proxy-assumptions
                  base-risk-result
                  {"perp:TOKENX" (assoc tokenx-entry
                                        :regression-series (regression-series 200))}
                  {:periods-per-year 365})
        diag (get-in adjusted [:risk-estimation :history-assumptions "perp:TOKENX"])]
    (is (not= (:final-beta diag) (:prior-weights diag)))
    (is (> (get-in adjusted [:covariance 0 2])
           (get-in prior-only [:covariance 0 2]))
        "Tilting the basket toward BTC lifts Cov(BTC, X) above the prior-implied row.")))

(deftest augment-labels-and-honors-user-prior-weights-test
  (let [entry (assoc tokenx-entry
                     :proxy-prior-weights {"perp:BTC" 3 "perp:ETH" 1})
        augmented (proxy/augment-risk-result-with-proxy-assumptions
                   base-risk-result
                   {"perp:TOKENX" entry})
        diag (get-in augmented [:risk-estimation :history-assumptions "perp:TOKENX"])]
    (is (= :user (:prior-source diag)))
    (is (near? 0.75 (get-in diag [:prior-weights "perp:BTC"])))
    (is (= (:prior-weights diag) (:final-beta diag))
        "No overlap => the user's prior is the final basket.")
    (is (near? 0.0375 (get-in augmented [:covariance 0 2]) 1e-6))))

(deftest augment-warns-on-invalid-user-prior-weights-test
  (let [entry (assoc tokenx-entry
                     :proxy-prior-weights {"perp:BTC" -2 "perp:ETH" 1})
        augmented (proxy/augment-risk-result-with-proxy-assumptions
                   base-risk-result
                   {"perp:TOKENX" entry})
        diag (get-in augmented [:risk-estimation :history-assumptions "perp:TOKENX"])]
    (is (= :equal (:prior-source diag)))
    (is (= {"perp:BTC" 0.5 "perp:ETH" 0.5} (:final-beta diag)))
    (is (some #(and (= :proxy-prior-weights-invalid (:code %))
                    (= "perp:TOKENX" (:instrument-id %)))
              (:warnings augmented))
        "A rejected explicit prior is disclosed, never silently replaced.")))

(deftest augment-result-is-psd-and-warns-when-basket-exceeds-target-test
  (let [entry (assoc tokenx-entry :volatility 0.1)
        augmented (proxy/augment-risk-result-with-proxy-assumptions
                   base-risk-result
                   {"perp:TOKENX" entry})
        conditioning (risk/covariance-conditioning (:covariance augmented))]
    (is (not= :not-positive-semidefinite (:status conditioning))
        "The synthesized matrix is PSD (repaired when needed).")
    (is (some #(= :proxy-systematic-variance-exceeds-target (:code %))
              (:warnings augmented))
        "Overshooting the stated volatility warns instead of crashing.")
    (is (>= (get-in augmented [:covariance 2 2])
            (get-in augmented [:risk-estimation :history-assumptions
                               "perp:TOKENX" :systematic-variance]))
        "Total variance is lifted through specific risk, never below systematic.")))

(deftest augment-skips-assets-with-no-usable-proxies-test
  (let [entry (assoc tokenx-entry :proxy-instrument-ids ["perp:GONE"])
        augmented (proxy/augment-risk-result-with-proxy-assumptions
                   base-risk-result
                   {"perp:TOKENX" entry})]
    (is (= ["perp:BTC" "perp:ETH"] (:instrument-ids augmented))
        "No synthetic row without a usable basket.")
    (is (some #(= :proxy-assumption-skipped (:code %)) (:warnings augmented)))))

(deftest augment-renormalizes-over-present-proxies-test
  (let [entry (assoc tokenx-entry
                     :proxy-instrument-ids ["perp:BTC" "perp:ETH" "perp:GONE"])
        augmented (proxy/augment-risk-result-with-proxy-assumptions
                   base-risk-result
                   {"perp:TOKENX" entry})
        diag (get-in augmented [:risk-estimation :history-assumptions "perp:TOKENX"])]
    (is (= {"perp:BTC" 0.5 "perp:ETH" 0.5} (:final-beta diag))
        "The basket renormalizes over the proxies that reached the risk model.")
    (is (some #(= :proxy-asset-unavailable (:code %)) (:warnings augmented)))))

(deftest augment-is-a-noop-without-usable-entries-test
  (is (= base-risk-result
         (proxy/augment-risk-result-with-proxy-assumptions base-risk-result {})))
  (is (= base-risk-result
         (proxy/augment-risk-result-with-proxy-assumptions
          base-risk-result
          {"perp:TOKENX" (assoc tokenx-entry :volatility nil)}))
      "No positive volatility => entry ignored.")
  (is (= base-risk-result
         (proxy/augment-risk-result-with-proxy-assumptions
          base-risk-result
          {"perp:TOKENX" (assoc tokenx-entry :proxy-instrument-ids [])}))
      "No proxies => entry ignored."))
