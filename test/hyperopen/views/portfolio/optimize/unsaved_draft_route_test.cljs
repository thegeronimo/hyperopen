(ns hyperopen.views.portfolio.optimize.unsaved-draft-route-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions.common :as action-common]
            [hyperopen.portfolio.optimizer.actions.execution :as execution-actions]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.views.portfolio-view :as portfolio-view]))

(defn- apply-saves
  "Applies the :effects/save effects from an action's return to `state`, so a test can
  exercise the real staging action and then render the resulting surface."
  [state effects]
  (reduce (fn [st effect]
            (if (= :effects/save (first effect))
              (assoc-in st (second effect) (nth effect 2))
              st))
          state
          effects))

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

(def solved-result
  {:as-of-ms 1714137600000
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
                       :rows []}})

(defn- with-current-solved-run
  [state result]
  (let [request (:request (setup-readiness/build-readiness state))]
    (assoc-in state
              [:portfolio :optimizer :last-successful-run]
              (fixtures/sample-last-successful-run
               {:computed-at-ms 1714137600000
                :request-signature (action-common/build-request-signature request)
                :result result}))))

(defn- with-current-minimal-solved-run
  [state result]
  (let [request (:request (setup-readiness/build-readiness state))]
    (assoc-in state
              [:portfolio :optimizer :last-successful-run]
              (fixtures/sample-minimal-last-successful-run
               {:computed-at-ms 1714137600000
                :request-signature (action-common/build-request-signature request)
                :result result}))))

(deftest unsaved-draft-results-route-renders-last-run-weights-test
  (let [base-state {:router {:path "/portfolio/optimize/draft"}
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
                                                       :gross-max 1.5}}}}}
        view-node (portfolio-view/portfolio-view
                   (with-current-solved-run base-state solved-result))]
    (is (some? (node-by-role view-node
                             "portfolio-optimizer-scenario-detail-surface")))
    (is (some? (node-by-role view-node
                             "portfolio-optimizer-results-surface")))
    (is (some? (node-by-role view-node
                             "portfolio-optimizer-target-exposure-table")))
    (is (some? (node-by-role view-node
                             "portfolio-optimizer-target-exposure-row-0")))))

(deftest unsaved-draft-execution-stages-preview-from-active-target-test
  (let [base-state {:router {:path "/portfolio/optimize/draft"}
                    :portfolio-ui {:optimizer {:results-tab :execution}}
                    :asset-selector
                    {:market-by-key
                     {"perp:BTC" {:key "perp:BTC"
                                  :market-type :perp
                                  :coin "BTC"
                                  :markPx "100"}
                      "perp:ETH" {:key "perp:ETH"
                                  :market-type :perp
                                  :coin "ETH"
                                  :markPx "50"}}}
                    :webdata2
                    {:clearinghouseState
                     {:marginSummary {:accountValue "10000"
                                      :totalMarginUsed "500"}
                      :assetPositions [{:position {:coin "BTC"
                                                   :szi "25"
                                                   :positionValue "2500"
                                                   :markPx "100"
                                                   :leverage {:value 5
                                                              :type "cross"}}}]}}
                    :portfolio {:optimizer
                                {:active-scenario {:loaded-id nil
                                                   :status :computed}
                                 :draft {:id "draft"
                                         :name "New Scenario"
                                         :universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}
                                                    {:instrument-id "perp:ETH"
                                                     :market-type :perp
                                                     :coin "ETH"}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? true
                                                       :max-asset-weight 0.75
                                                       :rebalance-tolerance 0.001
                                                       :perp-leverage {"perp:BTC" 5
                                                                       "perp:ETH" 5}}
                                         :execution-assumptions
                                         {:fallback-slippage-bps 20
                                          :prices-by-id {"perp:BTC" 100
                                                         "perp:ETH" 50}
                                          :fee-bps-by-id {"perp:BTC" 4
                                                         "perp:ETH" 5}}}
                                 :history-data {:candle-history-by-coin
                                                {"BTC" [{:time-ms 1000 :close "100"}
                                                        {:time-ms 2000 :close "101"}
                                                        {:time-ms 3000 :close "102"}]
                                                 "ETH" [{:time-ms 1000 :close "50"}
                                                        {:time-ms 2000 :close "51"}
                                                        {:time-ms 3000 :close "52"}]}
                                                :funding-history-by-coin
                                                {"BTC" [{:time-ms 1000
                                                         :funding-rate-raw 0}]
                                                 "ETH" [{:time-ms 1000
                                                         :funding-rate-raw 0}]}}
                                 :runtime {:as-of-ms 3000
                                           :stale-after-ms 60000}}}}
        result (dissoc
                (assoc solved-result
                       :scenario-id "draft"
                       :instrument-ids ["perp:BTC" "perp:ETH"]
                       :current-weights [0.25 0.0]
                       :target-weights [0.5 0.5]
                       :target-weights-by-instrument {"perp:BTC" 0.5
                                                      "perp:ETH" 0.5}
                       :current-weights-by-instrument {"perp:BTC" 0.25
                                                       "perp:ETH" 0.0}
                       :labels-by-instrument {"perp:BTC" "BTC"
                                              "perp:ETH" "ETH"})
                :rebalance-preview)
        seeded-state (with-current-minimal-solved-run base-state result)
        staged-state (apply-saves seeded-state
                                  (execution-actions/open-portfolio-optimizer-execution
                                   seeded-state))
        view-node (portfolio-view/portfolio-view staged-state)
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-execution-tab")))
    (is (some? (node-by-role view-node
                             "portfolio-optimizer-execution-order-row-perp-BTC")))
    (is (some? (node-by-role view-node
                             "portfolio-optimizer-execution-order-row-perp-ETH")))
    (is (not (contains? strings
                        "Run or load a scenario to stage its rebalance here for review and execution.")))))

(deftest draft-route-renders-workspace-run-of-a-saved-scenario-test
  (let [state (with-current-solved-run
                {:router {:path "/portfolio/optimize/draft"}
                 :portfolio {:optimizer
                             {:active-scenario {:loaded-id "scn-1"
                                                :status :computed
                                                :read-only? false}
                              :draft {:id "scn-1"
                                      :status :saved
                                      :name "Saved scenario"
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
                                                    :gross-max 1.5}}}}}
                solved-result)
        view-node (portfolio-view/portfolio-view state)
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-results-surface"))
        "the workspace run renders — no masked idle shell")
    (is (some? (node-by-role view-node "portfolio-optimizer-target-exposure-table")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-recommendation-empty")))
    (is (contains? strings "Saved scenario")
        "the surface carries the saved scenario's name, not a masked default")
    (is (nil? (node-by-role view-node "portfolio-optimizer-scenario-loading-state"))
        "never claims a load is in progress for the draft alias")
    (is (not-any? #(re-find #"is loading" %) strings)
        "no loading copy anywhere on the surface")
    (is (not-any? #(re-find #"read-only" %) strings)
        "no read-only claim about the user's own draft workspace")))

(deftest draft-route-without-a-run-for-a-saved-draft-renders-idle-test
  (let [state {:router {:path "/portfolio/optimize/draft"}
               :portfolio {:optimizer
                           {:active-scenario {:loaded-id "scn-1"
                                              :status :saved
                                              :read-only? false}
                            :draft {:id "scn-1"
                                    :status :saved
                                    :name "Saved scenario"
                                    :universe [{:instrument-id "perp:BTC"
                                                :market-type :perp
                                                :coin "BTC"}]
                                    :objective {:kind :minimum-variance}
                                    :return-model {:kind :historical-mean}
                                    :risk-model {:kind :diagonal-shrink}
                                    :constraints {:max-asset-weight 0.4
                                                  :gross-max 1.5}}}}}
        view-node (portfolio-view/portfolio-view state)
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-recommendation-empty")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-scenario-loading-state")))
    (is (not-any? #(re-find #"is loading" %) strings))
    (is (not-any? #(re-find #"read-only" %) strings))))

(deftest draft-route-with-empty-draft-disables-rerun-with-reason-test
  (let [state {:router {:path "/portfolio/optimize/draft"}
               :portfolio {:optimizer {:draft {:universe []}}}}
        view-node (portfolio-view/portfolio-view state)
        rerun (node-by-role view-node "portfolio-optimizer-scenario-rerun")]
    (is (some? rerun))
    (is (true? (get-in rerun [1 :disabled])))
    (is (= "Add assets to the universe before rerunning."
           (get-in rerun [1 :title])))))

(deftest unsaved-draft-results-route-uses-draft-vault-name-when-result-label-is-missing-or-raw-test
  (let [vault-address "0x1e37a337ed460039d1b15bd3bc489de789768d5e"
        vault-id (str "vault:" vault-address)
        vault-name "Alpha Yield"]
    (doseq [[case-label labels-by-instrument] [["missing label" nil]
                                               ["raw address label" {vault-id vault-address}]]]
      (let [view-node (portfolio-view/portfolio-view
                       (let [base-state {:router {:path "/portfolio/optimize/draft"}
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
                                                                            :gross-max 1.5}}}}}
                             result {:as-of-ms 1714137600000
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
                                                         :rows []}}]
                         (with-current-solved-run base-state result)))
            strings (set (collect-strings view-node))]
        (is (contains? strings vault-name) case-label)
        (is (not (contains? strings vault-address)) case-label)))))
