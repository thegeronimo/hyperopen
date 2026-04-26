(ns hyperopen.portfolio.optimizer.infrastructure.wire-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.infrastructure.wire :as wire]))

(deftest normalize-worker-boundary-stringifies-known-instrument-keyed-maps-test
  (let [perp-id (keyword "perp:BTC")
        spot-id (keyword "spot:PURR/USDC")
        normalized (wire/normalize-worker-boundary
                    {:current-portfolio {:by-instrument {spot-id {:weight 0.2}}}
                     :history {:return-series-by-instrument {perp-id [0.01 0.02]}
                               :funding-by-instrument {perp-id {:source "market-funding-history"}}}
                     :payload {:status "solved"
                               :return-decomposition-by-instrument
                               {perp-id {:return-component 0.12
                                         :funding-component 0.04
                                         :funding-source "market-funding-history"}
                                spot-id {:return-component 0.08
                                         :funding-component 0
                                         :funding-source "missing"}}
                               :current-weights-by-instrument {spot-id 0.2}
                               :target-weights-by-instrument {perp-id 0.35}
                               :diagnostics {:weight-sensitivity-by-instrument
                                             {perp-id {:max-delta 0.01}}}}})]
    (is (= {"spot:PURR/USDC" {:weight 0.2}}
           (get-in normalized [:current-portfolio :by-instrument])))
    (is (= {"perp:BTC" [0.01 0.02]}
           (get-in normalized [:history :return-series-by-instrument])))
    (is (= :market-funding-history
           (get-in normalized [:history :funding-by-instrument "perp:BTC" :source])))
    (is (= :solved (get-in normalized [:payload :status])))
    (is (= #{ "perp:BTC" "spot:PURR/USDC" }
           (set (keys (get-in normalized [:payload :return-decomposition-by-instrument])))))
    (is (= :market-funding-history
           (get-in normalized
                   [:payload :return-decomposition-by-instrument
                    "perp:BTC"
                    :funding-source])))
    (is (= {"spot:PURR/USDC" 0.2}
           (get-in normalized [:payload :current-weights-by-instrument])))
    (is (= {"perp:BTC" 0.35}
           (get-in normalized [:payload :target-weights-by-instrument])))
    (is (= {"perp:BTC" {:max-delta 0.01}}
           (get-in normalized
                   [:payload :diagnostics :weight-sensitivity-by-instrument])))))
