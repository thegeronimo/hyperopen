(ns hyperopen.views.portfolio.optimize.unsaved-draft-route-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
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

(defn- collect-strings
  [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(def solved-run
  (fixtures/sample-last-successful-run
   {:computed-at-ms 1714137600000
    :request-signature {:seed 1}
    :result {:as-of-ms 1714137600000
             :instrument-ids ["perp:BTC" "perp:ETH"]
             :current-weights [0.1 0.2]
             :target-weights [0.35 0.15]
             :target-weights-by-instrument {"perp:BTC" 0.35
                                            "perp:ETH" 0.15}
             :current-weights-by-instrument {"perp:BTC" 0.1
                                             "perp:ETH" 0.2}
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
                                 :rows []}}}))

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

(deftest unsaved-draft-results-route-uses-draft-vault-name-when-result-label-is-missing-or-raw-test
  (let [vault-address "0x1e37a337ed460039d1b15bd3bc489de789768d5e"
        vault-id (str "vault:" vault-address)
        vault-name "Alpha Yield"]
    (doseq [[case-label labels-by-instrument] [["missing label" nil]
                                               ["raw address label" {vault-id vault-address}]]]
      (let [view-node (portfolio-view/portfolio-view
                       {:router {:path "/portfolio/optimize/draft"}
                        :portfolio {:optimizer
                                    {:active-scenario {:loaded-id nil
                                                       :status :computed}
                                     :draft {:name "New Scenario"
                                             :universe [{:instrument-id vault-id
                                                         :market-type :vault
                                                         :coin vault-id
                                                         :vault-address vault-address
                                                         :name vault-name
                                                         :symbol vault-name}]
                                             :objective {:kind :minimum-variance}
                                             :return-model {:kind :historical-mean}
                                             :risk-model {:kind :diagonal-shrink}
                                             :constraints {:max-asset-weight 0.5
                                                           :gross-max 1.5}}
                                     :last-successful-run
                                     (fixtures/sample-last-successful-run
                                      {:computed-at-ms 1714137600000
                                       :request-signature {:seed 2}
                                       :result {:as-of-ms 1714137600000
                                                :instrument-ids [vault-id]
                                                :current-weights [0.0]
                                                :target-weights [0.5]
                                                :labels-by-instrument labels-by-instrument
                                                :target-weights-by-instrument {vault-id 0.5}
                                                :current-weights-by-instrument {vault-id 0.0}
                                                :expected-return 0.14
                                                :volatility 0.32
                                                :performance {:shrunk-sharpe 0.44}
                                                :history-summary {:return-observations 12}
                                                :return-model :historical-mean
                                                :risk-model :diagonal-shrink
                                                :frontier []
                                                :return-decomposition-by-instrument {}
                                                :diagnostics {:gross-exposure 0.5
                                                              :net-exposure 0.5
                                                              :effective-n 1
                                                              :turnover 0.5}
                                                :rebalance-preview {:status :ready
                                                                    :capital-usd 100000
                                                                    :summary {:ready-count 1}
                                                                    :rows []}}})}}})
            strings (set (collect-strings view-node))]
        (is (contains? strings vault-name) case-label)
        (is (not (contains? strings vault-address)) case-label)))))
