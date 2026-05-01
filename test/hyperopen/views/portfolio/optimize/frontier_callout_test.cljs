(ns hyperopen.views.portfolio.optimize.frontier-callout-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio.optimize.frontier-callout :as frontier-callout]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [collect-nodes collect-strings node-attr text-node]]))

(deftest frontier-callout-wraps-long-title-within-card-test
  (let [callout (frontier-callout/callout
                 {:bounds {:width 520 :height 240}
                  :data-role "portfolio-optimizer-frontier-callout-long-title"
                  :label "[ Systemic Strategies ] Hyperliquid Vault"
                  :point {:x 120 :y 72}
                  :rows [{:label "Expected Return" :value "18.38%"}
                         {:label "Volatility" :value "23.74%"}
                         {:label "Sharpe" :value "0.774"}
                         {:label "Target Weight" :value "0.47%"}]})
        rect (first (collect-nodes callout #(= :rect (first %))))
        title-lines (collect-nodes callout #(= :tspan (first %)))
        first-metric (text-node callout "Expected Return")]
    (is (= 2 (count title-lines)))
    (is (every? #(<= (count (first (collect-strings %))) 26) title-lines))
    (is (< 113 (node-attr rect :height))
        "Wrapped title should grow the callout instead of overlapping metric rows.")
    (is (= 52 (node-attr first-metric :y)))))
