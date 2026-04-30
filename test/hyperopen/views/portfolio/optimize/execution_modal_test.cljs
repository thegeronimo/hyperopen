(ns hyperopen.views.portfolio.optimize.execution-modal-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
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
    (let [children (node-children node)]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- collect-strings
  [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- node-by-role
  [node role]
  (find-first-node node #(= role (get-in % [1 :data-role]))))

(defn- click-actions
  [node]
  (get-in node [1 :on :click]))

(defn- node-text
  [node]
  (apply str (collect-strings node)))

(def solved-result
  (fixtures/sample-solved-result
   {:instrument-ids ["perp:BTC"]
    :current-weights [0.1]
    :target-weights [0.2]
    :target-weights-by-instrument {"perp:BTC" 0.2}
    :current-weights-by-instrument {"perp:BTC" 0.1}
    :expected-return 0.12
    :volatility 0.24
    :return-decomposition-by-instrument
    {"perp:BTC" {:return-component 0.1
                 :funding-component 0.02
                 :funding-source :market-funding-history}}
    :diagnostics {:gross-exposure 0.2
                  :net-exposure 0.2
                  :effective-n 1
                  :turnover 0.1}
    :rebalance-preview
    {:status :ready
     :capital-usd 10000
     :summary {:ready-count 1
               :blocked-count 0
               :gross-trade-notional-usd 1000}
     :rows [{:instrument-id "perp:BTC"
             :instrument-type :perp
             :status :ready
             :side :buy
             :quantity 0.25
             :delta-notional-usd 1000}]}}))

(deftest results-rebalance-preview-opens-execution-modal-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/scn_01"}
                    :portfolio-ui {:optimizer {:results-tab :rebalance}}
                    :portfolio {:optimizer
                                {:active-scenario {:loaded-id "scn_01"
                                                   :status :computed}
                                 :draft {:id "scn_01"}
                                 :last-successful-run {:result solved-result}}}})
        open-button (node-by-role view-node "portfolio-optimizer-open-execution-modal")]
    (is (some? (node-by-role view-node "portfolio-optimizer-rebalance-review-surface")))
    (is (some? (node-by-role view-node "portfolio-optimizer-rebalance-summary-kpis")))
    (is (some? (node-by-role view-node "portfolio-optimizer-rebalance-preview")))
    (is (some? (node-by-role view-node "portfolio-optimizer-rebalance-asset-BTC")))
    (is (some? (node-by-role view-node "portfolio-optimizer-rebalance-review-caution")))
    (is (some? open-button))
    (is (= false (get-in open-button [1 :disabled])))
    (is (= [[:actions/open-portfolio-optimizer-execution-modal]]
           (click-actions open-button)))))

(deftest execution-modal-renders-plan-and-close-action-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:last-successful-run {:result solved-result}
                                 :execution-modal
                                 {:open? true
                                  :plan {:status :partially-blocked
                                         :summary {:ready-count 1
                                                   :blocked-count 1
                                                   :skipped-count 0
                                                   :gross-ready-notional-usd 1000}
                                         :rows [{:instrument-id "perp:BTC"
                                                 :status :ready
                                                 :side :buy
                                                 :delta-notional-usd 1000}
                                               {:instrument-id "spot:PURR"
                                                 :status :blocked
                                                 :reason :spot-submit-unsupported
                                                 :delta-notional-usd -500}]}}}}})
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-execution-modal")))
    (is (= [[:actions/close-portfolio-optimizer-execution-modal]]
           (click-actions
            (node-by-role view-node "portfolio-optimizer-execution-modal-close"))))
    (is (= false
           (boolean
            (get-in (node-by-role view-node
                                  "portfolio-optimizer-execution-modal-confirm")
                    [1 :disabled]))))
    (is (= [[:actions/confirm-portfolio-optimizer-execution]]
           (click-actions
            (node-by-role view-node "portfolio-optimizer-execution-modal-confirm"))))
    (is (contains? strings "Confirm & Execute"))
    (is (contains? strings "perp:BTC"))
    (is (contains? strings "spot-submit-unsupported"))))

(deftest execution-modal-disables-confirm-while-submitting-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:last-successful-run {:result solved-result}
                                 :execution-modal
                                 {:open? true
                                  :submitting? true
                                  :plan {:status :ready
                                         :summary {:ready-count 1}
                                         :rows [{:instrument-id "perp:BTC"
                                                 :status :ready
                                                 :side :buy
                                                 :delta-notional-usd 1000}]}}}}})]
    (is (= true
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-execution-modal-confirm")
                   [1 :disabled])))))

(deftest execution-modal-renders-failed-latest-attempt-for-recovery-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:last-successful-run {:result solved-result}
                                 :execution
                                 {:status :failed
                                  :history [{:attempt-id "exec_1000"
                                             :status :failed
                                             :rows [{:instrument-id "perp:BTC"
                                                     :status :failed
                                                     :side :buy
                                                     :delta-notional-usd 1000
                                                     :error {:message "Order submit failed: exchange down"}}]}]}
                                 :execution-modal
                                 {:open? true
                                  :error "Execution failed before any rows submitted."
                                  :plan {:status :ready
                                         :summary {:ready-count 1}
                                         :rows [{:instrument-id "perp:BTC"
                                                 :status :ready
                                                 :side :buy
                                                 :delta-notional-usd 1000}]}}}}})
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node
                             "portfolio-optimizer-execution-latest-attempt")))
    (is (contains? strings "Latest Attempt"))
    (is (contains? strings "failed"))
    (is (contains? strings "Order submit failed: exchange down"))
    (is (= false
           (boolean
            (get-in (node-by-role view-node
                                  "portfolio-optimizer-execution-modal-confirm")
                    [1 :disabled]))))))

(deftest execution-modal-renders-vault-labels-by-name-test
  (let [vault-address "0x6666666666666666666666666666666666666666"
        vault-id (str "vault:" vault-address)
        view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:last-successful-run
                                 {:result {:labels-by-instrument {vault-id "Alpha Yield"}}}
                                 :execution
                                 {:status :failed
                                  :history [{:attempt-id "exec_vault"
                                             :status :failed
                                             :rows [{:instrument-id vault-id
                                                     :status :failed
                                                     :side :sell
                                                     :delta-notional-usd -400
                                                     :error {:message "Vault execution blocked"}}]}]}
                                 :execution-modal
                                 {:open? true
                                  :plan {:status :partially-blocked
                                         :summary {:ready-count 0
                                                   :blocked-count 1}
                                         :rows [{:instrument-id vault-id
                                                 :status :blocked
                                                 :side :sell
                                                 :reason :vault-submit-unsupported
                                                 :delta-notional-usd -400}]}}}}})
        modal (node-by-role view-node "portfolio-optimizer-execution-modal")
        text (node-text modal)]
    (is (str/includes? text "Alpha Yield"))
    (is (not (str/includes? text vault-id)))
    (is (not (str/includes? text vault-address)))))
