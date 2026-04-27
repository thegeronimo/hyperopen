(ns hyperopen.views.portfolio.optimize.unsaved-draft-route-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio-view :as portfolio-view]))

(defn- node-children
  [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- find-first-node
  [node pred]
  (cond
    (vector? node)
    (or (when (pred node) node)
        (some #(find-first-node % pred) (node-children node)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- node-by-role
  [node role]
  (find-first-node node #(= role (get-in % [1 :data-role]))))

(def solved-run
  {:computed-at-ms 1714137600000
   :request-signature {:seed 1}
   :result {:status :solved
            :as-of-ms 1714137600000
            :instrument-ids ["perp:BTC" "perp:ETH"]
            :current-weights [0.1 0.2]
            :target-weights [0.35 0.15]
            :expected-return 0.14
            :volatility 0.32
            :performance {:shrunk-sharpe 0.44}
            :history-summary {:return-observations 12}
            :return-model :historical-mean
            :risk-model :diagonal-shrink
            :frontier []
            :return-decomposition-by-instrument
            {"perp:BTC" {:return-component 0.1
                         :funding-component 0.02}
             "perp:ETH" {:return-component 0.08
                         :funding-component 0.01}}
            :diagnostics {:gross-exposure 0.5
                          :net-exposure 0.5
                          :effective-n 2
                          :turnover 0.2}
            :rebalance-preview {:status :ready
                                :capital-usd 100000
                                :summary {:ready-count 2}
                                :rows []}}})

(deftest unsaved-draft-results-route-renders-last-run-weights-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/draft"}
                    :portfolio {:optimizer
                                {:active-scenario {:loaded-id nil
                                                   :status :computed}
                                 :draft {:name "New Scenario"
                                         :universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}
                                                    {:instrument-id "perp:ETH"
                                                     :market-type :perp
                                                     :coin "ETH"}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:max-asset-weight 0.4
                                                       :gross-max 1.5}}
                                 :last-successful-run solved-run}}})]
    (is (some? (node-by-role view-node
                             "portfolio-optimizer-scenario-detail-surface")))
    (is (some? (node-by-role view-node
                             "portfolio-optimizer-results-surface")))
    (is (some? (node-by-role view-node
                             "portfolio-optimizer-target-exposure-table")))
    (is (some? (node-by-role view-node
                             "portfolio-optimizer-target-exposure-row-0")))))
