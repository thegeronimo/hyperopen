(ns hyperopen.views.portfolio.optimize.execution-modal-test
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

(def solved-result
  {:status :solved
   :instrument-ids ["perp:BTC"]
   :current-weights [0.1]
   :target-weights [0.2]
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
            :delta-notional-usd 1000}]}})

(deftest results-rebalance-preview-opens-execution-modal-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:id "draft-1"}
                                 :last-successful-run {:result solved-result}}}})
        open-button (node-by-role view-node "portfolio-optimizer-open-execution-modal")]
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
                                                 :reason :spot-read-only
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
    (is (contains? strings "spot-read-only"))))

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
