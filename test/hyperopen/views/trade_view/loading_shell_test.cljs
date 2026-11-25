(ns hyperopen.views.trade-view.loading-shell-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.trade-modules :as trade-modules]
            [hyperopen.views.trade-view :as trade-view]
            [hyperopen.views.trade.test-support :as support]))

(deftest trade-view-chart-loading-shell-preserves-chart-host-geometry-test
  (with-redefs [trade-modules/render-trade-chart-view (constantly nil)]
    (let [view-node (support/with-viewport-width
                      1280
                      #(trade-view/trade-view (support/active-asset-state)))
          shell-node (support/find-by-parity-id view-node "trade-chart-module-shell")
          host-node (support/find-first-node shell-node
                                             #(contains? (support/node-class-set %) "trading-chart-host"))]
      (is (some? shell-node))
      (is (contains? (support/node-class-set shell-node) "w-full"))
      (is (contains? (support/node-class-set shell-node) "h-full"))
      (is (some? host-node))
      (is (contains? (support/node-class-set host-node) "min-h-[360px]"))
      (is (contains? (support/node-class-set host-node) "lg:min-h-[12rem]"))
      (is (some #{"Loading Chart"} (support/collect-strings shell-node))))))

(deftest trade-view-chart-shell-fills-the-desktop-top-row-test
  (with-redefs [trade-modules/render-trade-chart-view (constantly nil)]
    (let [view-node (support/with-viewport-width
                      1280
                      #(trade-view/trade-view (support/active-asset-state)))
          chart-panel (support/find-by-parity-id view-node "trade-chart-panel")
          loading-shell (support/find-by-parity-id view-node "trade-chart-module-shell")
          shell-inner (nth loading-shell 2)
          toolbar (support/find-by-data-role loading-shell "trade-chart-shell-toolbar")
          controls (support/find-by-data-role loading-shell "trade-chart-shell-controls")
          chart-type-placeholder (support/find-by-data-role loading-shell "trade-chart-shell-chart-type")
          indicators-placeholder (support/find-by-data-role loading-shell "trade-chart-shell-indicators")
          active-timeframe (support/find-by-data-role loading-shell "trade-chart-shell-timeframe-1d")
          dividers (support/find-all-nodes loading-shell
                                           #(= "trade-chart-shell-divider"
                                               (get-in % [1 :data-role])))
          chart-panel-classes (support/node-class-set chart-panel)
          shell-inner-classes (support/node-class-set shell-inner)
          toolbar-classes (support/node-class-set toolbar)
          chart-type-classes (support/node-class-set chart-type-placeholder)
          indicators-classes (support/node-class-set indicators-placeholder)
          active-timeframe-classes (support/node-class-set active-timeframe)]
      (is (some? chart-panel))
      (is (some? loading-shell))
      (is (some? toolbar))
      (is (contains? chart-panel-classes "lg:row-start-1"))
      (is (contains? chart-panel-classes "lg:col-start-1"))
      (is (contains? shell-inner-classes "h-full"))
      (is (contains? shell-inner-classes "flex"))
      (is (contains? shell-inner-classes "flex-col"))
      (is (contains? toolbar-classes "min-w-0"))
      (is (not (contains? toolbar-classes "justify-between")))
      (is (= 2 (count dividers)))
      (is (some? controls))
      (is (contains? chart-type-classes "h-6"))
      (is (contains? chart-type-classes "w-6"))
      (is (contains? indicators-classes "h-8"))
      (is (contains? active-timeframe-classes "text-trading-green"))
      (is (some #{"Indicators"} (support/collect-strings toolbar))))))
