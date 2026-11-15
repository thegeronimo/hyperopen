(ns hyperopen.portfolio.optimizer.domain.risk-contributions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.domain.risk-contributions :as risk-contributions]))

(defn- near?
  ([expected actual] (near? expected actual 1e-9))
  ([expected actual tolerance]
   (and (number? actual)
        (< (js/Math.abs (- expected actual)) tolerance))))

(def ^:private diagonal-covariance
  [[0.01 0.0]
   [0.0 0.04]])

(deftest contributions-are-signed-euler-and-sum-to-totals-test
  (let [weights [(/ 2 3) (/ 1 3)]
        {:keys [status q sigma volatility-contributions relative-contributions]}
        (risk-contributions/contributions diagonal-covariance weights)]
    (is (= :ok status))
    (is (near? 0.5 (nth relative-contributions 0) 1e-12))
    (is (near? 0.5 (nth relative-contributions 1) 1e-12))
    (is (near? sigma (reduce + 0 volatility-contributions) 1e-12))
    (is (near? 1 (/ (reduce + 0 (map * weights
                                     (map #(reduce + 0 (map * % weights))
                                          diagonal-covariance)))
                    q)
               1e-12))))

(deftest negative-contribution-is-preserved-not-absolute-valued-test
  (let [covariance [[0.04 0.03]
                    [0.03 0.04]]
        weights [1.5 -0.5]
        {:keys [status relative-contributions]}
        (risk-contributions/contributions covariance weights)]
    (is (= :ok status))
    (is (neg? (nth relative-contributions 1)))
    (is (near? 1 (reduce + 0 relative-contributions) 1e-12))))

(deftest relative-contributions-are-covariance-scale-invariant-test
  (let [covariance [[0.04 0.01 0.0]
                    [0.01 0.09 -0.02]
                    [0.0 -0.02 0.02]]
        weights [0.5 0.3 -0.2]
        base (risk-contributions/contributions covariance weights)
        scaled (risk-contributions/contributions
                (mapv (fn [row] (mapv #(* 100 %) row)) covariance)
                weights)]
    (is (= :ok (:status base) (:status scaled)))
    (doseq [[a b] (map vector
                       (:relative-contributions base)
                       (:relative-contributions scaled))]
      (is (near? a b 1e-12)))))

(deftest degenerate-variance-fails-explicitly-test
  (testing "zero covariance"
    (let [result (risk-contributions/contributions [[0.0 0.0] [0.0 0.0]] [0.5 0.5])]
      (is (= :error (:status result)))
      (is (= :degenerate-variance (:reason result)))))
  (testing "perfectly hedged singular covariance"
    (let [result (risk-contributions/contributions [[0.04 0.04] [0.04 0.04]] [1 -1])]
      (is (= :error (:status result)))
      (is (= :degenerate-variance (:reason result))))))

(deftest covariance-validation-rejects-material-asymmetry-and-shape-test
  (testing "materially asymmetric input is rejected, not repaired"
    (let [result (risk-contributions/validate-covariance [[0.04 0.03]
                                                          [0.01 0.04]]
                                                         2)]
      (is (= :error (:status result)))
      (is (= :covariance-asymmetric (:reason result)))))
  (testing "floating-point asymmetry is symmetrized"
    (let [result (risk-contributions/validate-covariance [[0.04 0.03]
                                                          [(+ 0.03 1e-14) 0.04]]
                                                         2)]
      (is (= :ok (:status result)))
      (is (= (get-in result [:covariance 0 1])
             (get-in result [:covariance 1 0])))))
  (testing "misaligned dimension is rejected"
    (let [result (risk-contributions/validate-covariance [[0.04]] 2)]
      (is (= :error (:status result)))
      (is (= :covariance-shape (:reason result)))))
  (testing "non-finite entries are rejected"
    (let [result (risk-contributions/validate-covariance [[0.04 js/NaN]
                                                          [js/NaN 0.04]]
                                                         2)]
      (is (= :error (:status result)))
      (is (= :covariance-shape (:reason result))))))

(defn- finite-difference-gradient
  [covariance weights targets h]
  (mapv (fn [idx]
          (let [bump (fn [delta]
                       (let [w (update (vec weights) idx + delta)
                             result (risk-contributions/contributions covariance w)]
                         (risk-contributions/objective-value
                          (:relative-contributions result)
                          targets)))]
            (/ (- (bump h) (bump (- h))) (* 2 h))))
        (range (count weights))))

(def ^:private gradient-cases
  [{:covariance [[0.01 0.0] [0.0 0.04]]
    :weights [(/ 2 3) (/ 1 3)]}
   {:covariance [[0.04 0.02] [0.02 0.04]]
    :weights [0.9 -0.6]}
   {:covariance [[0.04 0.01 0.0]
                 [0.01 0.09 -0.02]
                 [0.0 -0.02 0.02]]
    :weights [0.5 0.3 -0.2]}
   {:covariance [[0.09 0.03 0.02 0.01]
                 [0.03 0.06 0.015 0.0]
                 [0.02 0.015 0.05 -0.01]
                 [0.01 0.0 -0.01 0.03]]
    :weights [0.4 0.3 0.2 0.1]}])

(deftest analytic-gradient-matches-central-differences-test
  (doseq [{:keys [covariance weights]} gradient-cases]
    (let [n (count weights)
          targets (vec (repeat n (/ 1 n)))
          evaluation (risk-contributions/evaluate covariance weights targets)
          numeric (finite-difference-gradient covariance weights targets 1e-6)]
      (is (= :ok (:status evaluation)))
      (doseq [[analytic fd] (map vector (:gradient evaluation) numeric)]
        (is (< (js/Math.abs (- analytic fd))
               (* 1e-4 (max 1 (js/Math.abs analytic))))
            (str "gradient mismatch: analytic " analytic " vs fd " fd
                 " for weights " (pr-str weights)))))))

(deftest contribution-summary-reports-errors-from-final-weights-test
  (let [summary (risk-contributions/contribution-summary
                 {:instrument-ids ["perp:A" "perp:B"]
                  :covariance diagonal-covariance
                  :weights [(/ 2 3) (/ 1 3)]
                  :targets [0.5 0.5]})]
    (is (= :ok (:status summary)))
    (is (= :signed-euler-volatility (:method summary)))
    (is (near? 1 (:sum-relative-contributions summary) 1e-12))
    (is (near? 0 (:rms-error summary) 1e-12))
    (is (near? 0 (:max-absolute-error summary) 1e-12))
    (is (zero? (:negative-contribution-count summary)))
    (is (near? 0.5 (get-in summary [:relative-contributions-by-instrument "perp:A"]) 1e-12))
    (is (near? 0.5 (get-in summary [:target-relative-contributions-by-instrument "perp:B"]) 1e-12)))
  (let [summary (risk-contributions/contribution-summary
                 {:instrument-ids ["perp:A" "perp:B"]
                  :covariance [[0.04 0.03] [0.03 0.04]]
                  :weights [1.5 -0.5]
                  :targets [0.5 0.5]})]
    (is (= 1 (:negative-contribution-count summary)))
    (is (pos? (:rms-error summary)))
    (is (pos? (:max-absolute-error summary)))))
