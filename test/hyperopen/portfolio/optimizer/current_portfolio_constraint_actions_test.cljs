(ns hyperopen.portfolio.optimizer.current-portfolio-constraint-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions :as actions]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.defaults :as defaults]))

(defn- effect-values-by-path
  [effects]
  (reduce (fn [acc effect]
            (case (first effect)
              :effects/save
              (assoc acc (second effect) (nth effect 2))

              :effects/save-many
              (reduce (fn [acc [path value]]
                        (assoc acc path value))
                      acc
                      (second effect))

              acc))
          {}
          (or effects [])))

(deftest from-current-holdings-seeds-current-centered-constraints-test
  (let [default-constraints (get-in (defaults/default-draft) [:constraints])
        state {:portfolio {:optimizer {:draft {:constraints default-constraints}}}
               :webdata2 {:clearinghouseState
                          {:marginSummary {:accountValue "1000"}
                           :assetPositions
                           [{:position {:coin "BTC"
                                        :szi "1"
                                        :positionValue "1500"
                                        :leverage {:type "cross"
                                                   :value "5"}}}
                            {:position {:coin "ETH"
                                        :szi "-1"
                                        :markPx "500"
                                        :leverage {:type "cross"
                                                   :value "5"}}}]}}}
        effects (actions/set-portfolio-optimizer-universe-from-current state)
        values (effect-values-by-path effects)
        universe (get values contracts/draft-universe-path)
        constraints (get values contracts/draft-constraints-path)]
    (is (= ["perp:BTC" "perp:ETH"]
           (mapv :instrument-id universe)))
    (is (= false (:long-only? constraints)))
    (is (= 2.0 (:gross-max constraints)))
    (is (= 1.0 (:net-min constraints)))
    (is (= 1.0 (:net-max constraints)))
    (is (= 0.025 (:net-band-pct constraints)))
    (is (= 1.5 (:max-asset-weight constraints)))
    (is (nil? (:max-turnover constraints)))
    (is (= (:dust-usdc default-constraints)
           (:dust-usdc constraints)))
    (is (= (:rebalance-tolerance default-constraints)
           (:rebalance-tolerance constraints)))
    (is (= true (get values contracts/draft-dirty-path)))))
