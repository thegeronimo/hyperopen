(ns hyperopen.views.portfolio.optimize.view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [collect-strings node-by-role]]))

(deftest portfolio-view-delegates-optimizer-index-route-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize"}})]
    (is (some? (node-by-role view-node "portfolio-optimizer-index")))
    (is (nil? (node-by-role view-node "portfolio-account-table")))
    (is (contains? (set (collect-strings view-node))
                   "Optimization Scenarios"))))
