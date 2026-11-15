(ns hyperopen.portfolio.optimizer.domain.leverage-risk-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.domain.leverage-risk :as leverage-risk]))

(defn- near?
  ([expected actual] (near? expected actual 1e-6))
  ([expected actual tolerance]
   (and (number? actual)
        (< (js/Math.abs (- expected actual)) tolerance))))

(deftest normal-cdf-reference-values-test
  (is (near? 0.5 (leverage-risk/normal-cdf 0) 1e-7))
  (is (near? 0.95 (leverage-risk/normal-cdf 1.6448536269514722) 3e-7))
  (is (near? 0.1586553 (leverage-risk/normal-cdf -1) 3e-7))
  (is (near? 0.9772499 (leverage-risk/normal-cdf 2) 3e-7))
  (doseq [x [0.3 0.75 1.2 2.5 4]]
    (is (near? 1
               (+ (leverage-risk/normal-cdf x)
                  (leverage-risk/normal-cdf (- x)))
               1e-6)))
  (is (nil? (leverage-risk/normal-cdf js/NaN))))

(deftest moderate-book-outcomes-test
  (let [{:keys [log-drift log-sigma median-ending-factor p5-ending-factor
                p95-ending-factor mean-ending-factor
                prob-terminal-loss-half prob-touch-half-drawdown]}
        (leverage-risk/outcome-model {:expected-return 0.10 :volatility 0.40})]
    (is (near? 0.0153102 log-drift 1e-6))
    (is (near? 0.40 log-sigma 1e-12)
        "The model exposes its own σ so views can draw the distribution.")
    (is (near? 1.0154280 median-ending-factor 1e-5))
    (is (near? 0.5259088 p5-ending-factor 1e-5))
    (is (near? 1.9606021 p95-ending-factor 1e-5))
    (is (near? 1.10 mean-ending-factor 1e-12))
    (is (near? 0.038273 prob-terminal-loss-half 5e-4))
    (is (near? 0.077751 prob-touch-half-drawdown 1e-3))
    (is (> prob-touch-half-drawdown prob-terminal-loss-half))))

(deftest extreme-levered-book-outcomes-test
  (let [{:keys [median-ending-factor mean-ending-factor
                prob-touch-half-drawdown prob-terminal-loss-half]}
        (leverage-risk/outcome-model {:expected-return 18.6606
                                      :volatility 4.1182})]
    (is (near? 19.6606 mean-ending-factor 1e-9))
    (is (< median-ending-factor 0.01))
    (is (near? 0.0040864 median-ending-factor 1e-5))
    (is (near? 0.8785 prob-terminal-loss-half 1e-3))
    (is (near? 0.9824 prob-touch-half-drawdown 2e-3))
    (is (<= prob-touch-half-drawdown 1))))

(deftest volatility-drag-is-monotone-in-sigma-test
  (let [median-at (fn [sigma]
                    (:median-ending-factor
                     (leverage-risk/outcome-model {:expected-return 0.10
                                                   :volatility sigma})))
        touch-at (fn [sigma]
                   (:prob-touch-half-drawdown
                    (leverage-risk/outcome-model {:expected-return 0.10
                                                  :volatility sigma})))]
    (is (> (median-at 0.2) (median-at 0.4)))
    (is (> (median-at 0.4) (median-at 0.8)))
    (is (< (touch-at 0.4) (touch-at 0.8)))
    (is (< (touch-at 0.8) (touch-at 1.6)))))

(deftest zero-volatility-degenerates-deterministically-test
  (let [up (leverage-risk/outcome-model {:expected-return 0.10 :volatility 0})
        down (leverage-risk/outcome-model {:expected-return -0.60 :volatility 0})]
    (is (near? 1.10 (:median-ending-factor up) 1e-12))
    (is (near? 1.10 (:p5-ending-factor up) 1e-12))
    (is (near? 1.10 (:p95-ending-factor up) 1e-12))
    (is (zero? (:prob-terminal-loss-half up)))
    (is (zero? (:prob-touch-half-drawdown up)))
    (is (near? 0.40 (:median-ending-factor down) 1e-12))
    (is (= 1 (:prob-terminal-loss-half down)))
    (is (= 1 (:prob-touch-half-drawdown down)))))

(deftest invalid-inputs-yield-nil-test
  (doseq [inputs [{:expected-return -1 :volatility 0.4}
                  {:expected-return -1.5 :volatility 0.4}
                  {:expected-return js/NaN :volatility 0.4}
                  {:expected-return js/Infinity :volatility 0.4}
                  {:expected-return 0.1 :volatility -0.2}
                  {:expected-return 0.1 :volatility js/NaN}
                  {:expected-return 0.1 :volatility js/Infinity}
                  {:expected-return nil :volatility 0.4}
                  {:expected-return 0.1 :volatility nil}]]
    (is (nil? (leverage-risk/outcome-model inputs))
        (str "expected nil for " (pr-str inputs)))))

(deftest extreme-drift-cannot-produce-nan-test
  (doseq [inputs [{:expected-return -0.9999 :volatility 0.05}
                  {:expected-return -0.99 :volatility 0.01}
                  {:expected-return 50 :volatility 0.01}
                  {:expected-return 100 :volatility 10}]]
    (let [{:keys [prob-touch-half-drawdown prob-terminal-loss-half]
           :as model} (leverage-risk/outcome-model inputs)]
      (is (some? model))
      (is (and (number? prob-touch-half-drawdown)
               (js/isFinite prob-touch-half-drawdown)
               (<= 0 prob-touch-half-drawdown 1))
          (str "touch probability out of range for " (pr-str inputs)))
      (is (<= prob-terminal-loss-half prob-touch-half-drawdown)))))

(deftest percentile-ordering-invariant-test
  (doseq [inputs [{:expected-return 0.1 :volatility 0.4}
                  {:expected-return 18.6606 :volatility 4.1182}
                  {:expected-return -0.5 :volatility 1.0}]]
    (let [{:keys [p5-ending-factor median-ending-factor p95-ending-factor]}
          (leverage-risk/outcome-model inputs)]
      (is (< p5-ending-factor median-ending-factor p95-ending-factor)))))
