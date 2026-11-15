(ns hyperopen.portfolio.optimizer.domain.volatility-intuition-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.domain.volatility-intuition
             :as intuition]))

(defn- near?
  ([expected actual] (near? expected actual 1e-6))
  ([expected actual tolerance]
   (and (number? actual)
        (< (js/Math.abs (- expected actual)) tolerance))))

(deftest resolve-basis-knows-only-real-conventions-test
  (is (= 365 (:periods-per-year (intuition/resolve-basis 365))))
  (is (= 7 (:weekly-periods (intuition/resolve-basis 365))))
  (is (= 30 (:monthly-periods (intuition/resolve-basis 365))))
  (is (= 252 (:periods-per-year (intuition/resolve-basis 252))))
  (is (= 5 (:weekly-periods (intuition/resolve-basis 252))))
  (is (= 21 (:monthly-periods (intuition/resolve-basis 252))))
  (is (nil? (intuition/resolve-basis 300)))
  (is (nil? (intuition/resolve-basis nil))))

(deftest forty-percent-on-365-calendar-days-test
  (let [{:keys [daily weekly monthly]}
        (intuition/horizon-vols 0.40 intuition/calendar-day-basis)]
    (is (near? 0.0209370 daily 1e-6))
    (is (near? 0.0553940 weekly 1e-6))
    (is (near? 0.1146764 monthly 1e-6))))

(deftest forty-percent-on-252-trading-days-test
  (let [{:keys [daily weekly monthly]}
        (intuition/horizon-vols 0.40 intuition/trading-day-basis)]
    (is (near? 0.0251976 daily 1e-6))
    (is (near? 0.0563436 weekly 1e-6))
    (is (near? 0.1154701 monthly 1e-6))))

(deftest extreme-vol-on-365-calendar-days-test
  (let [{:keys [daily weekly monthly]}
        (intuition/horizon-vols 4.1182 intuition/calendar-day-basis)]
    (is (near? 0.2155565 daily 1e-5))
    (is (near? 0.5703081 weekly 1e-5))
    (is (near? 1.1806513 monthly 1e-5))
    (is (> monthly 1.0))))

(deftest extreme-vol-on-252-trading-days-test
  (let [{:keys [daily weekly monthly]}
        (intuition/horizon-vols 4.1182 intuition/trading-day-basis)]
    (is (near? 0.2594257 daily 1e-5))
    (is (near? 0.5800852 weekly 1e-5))
    (is (near? 1.1888219 monthly 1e-5))))

(deftest zero-volatility-is-valid-and-all-zero-test
  (let [{:keys [annualized daily weekly monthly]}
        (intuition/horizon-vols 0 intuition/calendar-day-basis)]
    (is (zero? annualized))
    (is (zero? daily))
    (is (zero? weekly))
    (is (zero? monthly))))

(deftest invalid-volatility-yields-no-horizons-test
  (is (nil? (intuition/horizon-vols nil intuition/calendar-day-basis)))
  (is (nil? (intuition/horizon-vols -0.1 intuition/calendar-day-basis)))
  (is (nil? (intuition/horizon-vols js/NaN intuition/calendar-day-basis)))
  (is (nil? (intuition/horizon-vols js/Infinity intuition/calendar-day-basis)))
  (is (nil? (intuition/horizon-vols 0.4 nil))))

(deftest severity-tiers-test
  (is (= :none (intuition/severity 0.30)))
  (is (= :elevated (intuition/severity 0.50)))
  (is (= :elevated (intuition/severity 0.9999)))
  (is (= :very-high (intuition/severity 1.0)))
  (is (= :very-high (intuition/severity 1.9999)))
  (is (= :extreme (intuition/severity 2.0)))
  (is (= :extreme (intuition/severity 4.1182)))
  (is (nil? (intuition/severity nil)))
  (is (nil? (intuition/severity js/NaN))))

(deftest severity-at-least-orders-tiers-test
  (is (intuition/severity-at-least? :extreme :very-high))
  (is (intuition/severity-at-least? :very-high :very-high))
  (is (not (intuition/severity-at-least? :elevated :very-high)))
  (is (not (intuition/severity-at-least? nil :elevated))))

(deftest intuition-model-happy-path-test
  (let [{:keys [status basis target current change]}
        (intuition/intuition-model {:target-volatility 4.1182
                                    :current-volatility 3.1390
                                    :periods-per-year 365})]
    (is (= :ok status))
    (is (= 365 (:periods-per-year basis)))
    (is (near? 0.2155565 (:daily target) 1e-5))
    (is (= :extreme (:severity target)))
    (is (true? (:monthly-boundary? target)))
    (is (near? (/ 3.1390 19.104973) (:daily current) 1e-5))
    (is (= :extreme (:severity current)))
    (is (every? pos? (vals change)))))

(deftest intuition-model-reduction-has-negative-change-test
  (let [{:keys [change]}
        (intuition/intuition-model {:target-volatility 0.20
                                    :current-volatility 0.60
                                    :periods-per-year 365})]
    (is (every? neg? (vals change)))))

(deftest intuition-model-without-current-omits-current-and-change-test
  (let [{:keys [status current change]}
        (intuition/intuition-model {:target-volatility 0.40
                                    :periods-per-year 365})]
    (is (= :ok status))
    (is (nil? current))
    (is (nil? change))))

(deftest intuition-model-boundary-flag-off-at-moderate-vol-test
  (let [{:keys [target]}
        (intuition/intuition-model {:target-volatility 0.40
                                    :periods-per-year 365})]
    (is (false? (:monthly-boundary? target)))
    (is (= :none (:severity target)))))

(deftest intuition-model-unknown-basis-is-unavailable-test
  (let [{:keys [status reason]}
        (intuition/intuition-model {:target-volatility 0.40
                                    :periods-per-year 360})]
    (is (= :unavailable status))
    (is (= :unknown-annualization-basis reason))))

(deftest intuition-model-invalid-target-is-unavailable-test
  (doseq [bad [nil -0.2 js/NaN js/Infinity]]
    (let [{:keys [status reason]}
          (intuition/intuition-model {:target-volatility bad
                                      :current-volatility 0.5
                                      :periods-per-year 365})]
      (is (= :unavailable status))
      (is (= :missing-target-volatility reason)))))

(deftest intuition-model-invalid-current-degrades-to-target-only-test
  (doseq [bad [-1 js/NaN js/Infinity]]
    (let [{:keys [status current change]}
          (intuition/intuition-model {:target-volatility 0.4
                                      :current-volatility bad
                                      :periods-per-year 365})]
      (is (= :ok status))
      (is (nil? current))
      (is (nil? change)))))

(deftest ok-model-never-carries-non-finite-numbers-test
  (let [{:keys [target current change]}
        (intuition/intuition-model {:target-volatility 4.1182
                                    :current-volatility 3.1390
                                    :periods-per-year 365})
        numbers (concat (vals (select-keys target
                                           [:annualized :daily :weekly :monthly]))
                        (vals (select-keys current
                                           [:annualized :daily :weekly :monthly]))
                        (vals change))]
    (is (every? #(and (number? %) (js/isFinite %)) numbers))))
