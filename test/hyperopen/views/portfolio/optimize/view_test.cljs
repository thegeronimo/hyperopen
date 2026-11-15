(ns hyperopen.views.portfolio.optimize.view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [collect-strings node-by-role]]))

(deftest portfolio-view-lands-optimizer-root-on-workspace-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize"}})]
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-route-surface")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-index")))
    (is (nil? (node-by-role view-node "portfolio-account-table")))
    (is (not (contains? (set (collect-strings view-node))
                        "Optimization Scenarios")))))

(deftest portfolio-optimizer-route-content-has-stable-element-root-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}})
        route-content (nth view-node 2)]
    (is (= "portfolio-optimizer-route-content"
           (get-in route-content [1 :data-role])))
    (is (not= :<> (first route-content)))
    (is (some? (node-by-role view-node
                              "portfolio-optimizer-setup-route-surface")))))
