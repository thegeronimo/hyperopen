(ns hyperopen.borrow-lend.model-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.borrow-lend.model :as model]))

(defn- approx=
  ([a b] (approx= a b 1e-6))
  ([a b eps] (and (number? a) (number? b) (< (js/Math.abs (- a b)) eps))))

(deftest rate-curve-test
  (testing "flat below the kink"
    (is (= 0.05 (model/borrow-rate 0)))
    (is (= 0.05 (model/borrow-rate 0.8))))
  (testing "steep above the kink"
    (is (approx= 0.24 (model/borrow-rate 0.84)))
    (is (approx= 1.0 (model/borrow-rate 1.0))))
  (testing "utilization is clamped"
    (is (approx= 1.0 (model/borrow-rate 1.7)))
    (is (= 0.05 (model/borrow-rate -1)))
    (is (= 0.05 (model/borrow-rate js/NaN))))
  (testing "supply rate is 90% of borrow interest on the borrowed share"
    (is (approx= (* 0.9 0.84 0.24) (model/supply-rate 0.84)))
    (is (= 0 (model/supply-rate 0)))))

(deftest rate-curve-matches-live-reserve-test
  ;; Mainnet USDC reserve captured 2026-10-20.
  (let [u 0.800022346]
    (is (approx= 0.0501061434 (model/borrow-rate u) 1e-9))
    (is (approx= 0.0360774309 (model/supply-rate u) 1e-9))))

(deftest supply-preview-test
  (let [reserve {:total-supplied 1000 :total-borrowed 831}]
    (testing "depositing lowers utilization and rates"
      (let [{:keys [utilization borrow-apy supply-apy]} (model/supply-preview reserve :supply 39)]
        (is (approx= (/ 831 1039) utilization))
        (is (approx= 0.05 borrow-apy))
        (is (< supply-apy (model/supply-rate 0.831)))))
    (testing "withdrawing raises utilization"
      (is (> (:utilization (model/supply-preview reserve :withdraw 100)) 0.831)))
    (testing "withdrawing more than supplied clamps utilization to 1"
      (is (= 1 (:utilization (model/supply-preview reserve :withdraw 5000)))))
    (testing "zero, negative or missing amounts change nothing"
      (doseq [amount [0 -10 nil js/NaN]]
        (is (approx= 0.831 (:utilization (model/supply-preview reserve :supply amount))))))))

(def ^:private reserves
  {0 {:token 0 :oracle-px 1 :ltv 0}
   150 {:token 150 :oracle-px 40 :ltv 0.65}
   197 {:token 197 :oracle-px 100000 :ltv 0.5}})

(deftest brief-worked-example-test
  ;; 100 HYPE at $40, LTV 65%, 2,000 USDC borrowed.
  (let [positions [{:token 150 :supplied 100 :borrowed 0}
                   {:token 0 :supplied 5000 :borrowed 2000}]
        collateral (model/collateral-rows positions reserves)
        debt (model/borrowed-usd positions reserves)
        {:keys [borrow-limit-usd available-to-borrow-usd health-factor limit-used liquidatable?]}
        (model/health collateral debt)
        prices (get (model/liquidation-prices collateral debt) 150)]
    (testing "stablecoin supply is not collateral"
      (is (= [150] (mapv :token collateral))))
    (is (= 2000 debt))
    (is (approx= 2600 borrow-limit-usd))
    (is (approx= 600 available-to-borrow-usd))
    (is (approx= 1.3 health-factor))
    (is (approx= (/ 2000 2600) limit-used))
    (is (false? liquidatable?))
    (is (approx= 30.769230 (:borrow-stops-px prices) 1e-5))
    (is (approx= 24.242424 (:liquidation-px prices) 1e-5))))

(deftest multi-collateral-liquidation-price-test
  (let [positions [{:token 150 :supplied 100 :borrowed 0}
                   {:token 197 :supplied 0.01 :borrowed 0}
                   {:token 0 :supplied 0 :borrowed 2000}]
        collateral (model/collateral-rows positions reserves)
        prices (model/liquidation-prices collateral 2000)]
    (testing "other collateral lowers the HYPE liquidation price"
      ;; BTC contributes 1,000 * 0.75 = 750 of liquidation capacity.
      (is (approx= (/ (- 2000 750) 82.5) (get-in prices [150 :liquidation-px]) 1e-6)))
    (testing "price floors at zero when other collateral covers the debt"
      (is (= 0 (get-in (model/liquidation-prices collateral 500) [150 :liquidation-px]))))))

(deftest health-edge-cases-test
  (testing "no debt has no health factor"
    (let [h (model/health [] 0)]
      (is (nil? (:health-factor h)))
      (is (nil? (:limit-used h)))
      (is (= :none (model/health-status (:health-factor h))))))
  (testing "debt with no collateral is liquidatable"
    (is (true? (:liquidatable? (model/health [] 100)))))
  (testing "health-after applies borrow and repay deltas"
    (let [collateral (model/collateral-rows [{:token 150 :supplied 100}] reserves)]
      (is (approx= (/ 2600 2300) (:health-factor (model/health-after collateral 2000 300))))
      (is (approx= (/ 2600 1500) (:health-factor (model/health-after collateral 2000 -500))))
      (is (nil? (:health-factor (model/health-after collateral 2000 -5000)))))))

(deftest health-status-test
  (is (= :safe (model/health-status 1.5)))
  (is (= :caution (model/health-status 1.3)))
  (is (= :risk (model/health-status 1.1))))

(deftest net-apy-test
  (is (approx= (/ (- (* 5000 0.1484) (* 2000 0.198)) 5000)
               (model/net-apy [{:value-usd 5000 :apy 0.1484}]
                              [{:value-usd 2000 :apy 0.198}])))
  (is (nil? (model/net-apy [] [{:value-usd 1 :apy 0.1}]))))
