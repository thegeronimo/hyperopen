(ns hyperopen.portfolio.optimizer.infrastructure.prior-data-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.infrastructure.prior-data :as prior-data]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.0000001))

(deftest black-litterman-prior-prefers-market-cap-weights-test
  (let [prior (prior-data/resolve-black-litterman-prior
               {:universe [{:instrument-id "perp:BTC"
                            :coin "BTC"}
                           {:instrument-id "spot:PURR"
                            :coin "PURR"}]
                :market-cap-by-coin {"BTC" 900
                                     "PURR" 100}
                :current-portfolio {:by-instrument {"perp:BTC" {:weight 0.2}
                                                    "spot:PURR" {:weight 0.8}}}})]
    (is (= :market-cap (:source prior)))
    (is (near? 0.9 (get-in prior [:weights-by-instrument "perp:BTC"])))
    (is (near? 0.1 (get-in prior [:weights-by-instrument "spot:PURR"])))
    (is (= [] (:warnings prior)))))

(deftest black-litterman-prior-falls-back-to-current-portfolio-when-market-cap-is-incomplete-test
  (let [prior (prior-data/resolve-black-litterman-prior
               {:universe [{:instrument-id "perp:BTC"
                            :coin "BTC"}
                           {:instrument-id "spot:PURR"
                            :coin "PURR"}]
                :market-cap-by-coin {"BTC" 900}
                :current-portfolio {:by-instrument {"perp:BTC" {:weight 0.25}
                                                    "spot:PURR" {:weight 0.75}}}})]
    (is (= :fallback-current-portfolio (:source prior)))
    (is (= {"perp:BTC" 0.25
            "spot:PURR" 0.75}
           (:weights-by-instrument prior)))
    (is (= [{:code :missing-market-cap-prior
             :missing-coins ["PURR"]
             :fallback :current-portfolio}]
           (:warnings prior)))))

(deftest black-litterman-prior-uses-equal-weight-last-resort-with-warning-test
  (let [prior (prior-data/resolve-black-litterman-prior
               {:universe [{:instrument-id "perp:BTC"
                            :coin "BTC"}
                           {:instrument-id "perp:ETH"
                            :coin "ETH"}]
                :current-portfolio {:by-instrument {}}})]
    (is (= :fallback-equal-weight (:source prior)))
    (is (= {"perp:BTC" 0.5
            "perp:ETH" 0.5}
           (:weights-by-instrument prior)))
    (is (= [{:code :missing-market-cap-prior
             :missing-coins ["BTC" "ETH"]
             :fallback :equal-weight}
            {:code :missing-current-portfolio-prior
             :fallback :equal-weight}]
           (:warnings prior)))))
