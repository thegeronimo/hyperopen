(ns hyperopen.portfolio.optimizer.domain.rebalance-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.domain.rebalance :as rebalance]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.0000001))

(deftest build-rebalance-preview-generates-ready-perp-and-blocked-spot-rows-test
  (let [preview (rebalance/build-rebalance-preview
                 {:capital-usd 10000
                  :rebalance-tolerance 0.005
                  :fallback-slippage-bps 15
                  :instrument-ids ["perp:BTC" "spot:PURR"]
                  :current-weights [0.2 0.1]
                  :target-weights [0.35 0.02]
                  :instruments-by-id {"perp:BTC" {:instrument-type :perp
                                                  :coin "BTC"}
                                      "spot:PURR" {:instrument-type :spot
                                                   :coin "PURR"}}
                  :prices-by-id {"perp:BTC" 30000
                                 "spot:PURR" 2}
                  :cost-contexts-by-id {"perp:BTC" {:source :live-book
                                                    :slippage-bps 4}}
                  :fee-bps-by-id {"perp:BTC" 4.5
                                  "spot:PURR" 7}})]
    (is (= :partially-blocked (:status preview)))
    (is (= 2 (count (:rows preview))))
    (let [perp-row (first (:rows preview))
          spot-row (second (:rows preview))]
      (is (= :ready (:status perp-row)))
      (is (= :buy (:side perp-row)))
      (is (near? 1500 (:delta-notional-usd perp-row)))
      (is (near? 0.05 (:quantity perp-row)))
      (is (= :live-book (get-in perp-row [:cost :source])))
      (is (= 4 (get-in perp-row [:cost :slippage-bps])))
      (is (near? 0.6 (get-in perp-row [:cost :estimated-slippage-usd])))
      (is (= 4.5 (get-in perp-row [:cost :fee-bps])))
      (is (near? 0.675 (get-in perp-row [:cost :estimated-fee-usd])))
      (is (= :blocked (:status spot-row)))
      (is (= :spot-read-only (:reason spot-row)))
      (is (near? -800 (:delta-notional-usd spot-row))))))

(deftest build-rebalance-preview-skips-tolerance-and-blocks-missing-prices-test
  (let [preview (rebalance/build-rebalance-preview
                 {:capital-usd 1000
                  :rebalance-tolerance 0.01
                  :instrument-ids ["perp:ETH" "perp:SOL"]
                  :current-weights [0.2 0.2]
                  :target-weights [0.205 0.1]
                  :instruments-by-id {"perp:ETH" {:instrument-type :perp}
                                      "perp:SOL" {:instrument-type :perp}}
                  :prices-by-id {"perp:ETH" 2000}})]
    (is (= :blocked (:status preview)))
    (is (= :within-tolerance (get-in preview [:rows 0 :status])))
    (is (= :blocked (get-in preview [:rows 1 :status])))
    (is (= :missing-price (get-in preview [:rows 1 :reason])))
    (is (near? 100 (get-in preview [:summary :gross-trade-notional-usd])))))
