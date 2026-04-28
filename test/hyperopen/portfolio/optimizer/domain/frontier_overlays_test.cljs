(ns hyperopen.portfolio.optimizer.domain.frontier-overlays-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.domain.frontier-overlays :as overlays]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.000001))

(deftest asset-overlay-points-compute-standalone-and-signed-contribution-metrics-test
  (let [points (overlays/asset-overlay-points
                {:instrument-ids ["perp:BTC" "perp:ETH"]
                 :target-weights [0.1 0.6]
                 :expected-returns [0.1 0.04]
                 :covariance [[0.04 -0.03]
                              [-0.03 0.09]]
                 :labels-by-instrument {"perp:BTC" "BTC"
                                        "perp:ETH" "ETH"}})
        by-id (into {} (map (juxt :instrument-id identity)) points)
        btc (get by-id "perp:BTC")
        eth (get by-id "perp:ETH")
        contribution-sum (reduce + (map #(get-in % [:contribution :volatility]) points))]
    (is (= ["perp:BTC" "perp:ETH"] (mapv :instrument-id points)))
    (is (= "BTC" (:label btc)))
    (is (near? 0.1 (:target-weight btc)))
    (is (near? 0.1 (get-in btc [:standalone :expected-return])))
    (is (near? 0.2 (get-in btc [:standalone :volatility])))
    (is (near? 0.04 (get-in eth [:standalone :expected-return])))
    (is (near? 0.3 (get-in eth [:standalone :volatility])))
    (is (near? 0.01 (get-in btc [:contribution :expected-return])))
    (is (near? 0.024 (get-in eth [:contribution :expected-return])))
    (is (neg? (get-in btc [:contribution :volatility]))
        "A hedging asset can have negative signed volatility contribution.")
    (is (near? (js/Math.sqrt 0.0292) contribution-sum)
        "Signed volatility contributions should sum to target portfolio volatility.")))

(deftest asset-overlay-points-omit-contribution-volatility-when-portfolio-volatility-is-zero-test
  (let [points (overlays/asset-overlay-points
                {:instrument-ids ["perp:BTC"]
                 :target-weights [0]
                 :expected-returns [0.1]
                 :covariance [[0.04]]})]
    (is (= 1 (count points)))
    (is (nil? (get-in (first points) [:contribution :volatility])))))
