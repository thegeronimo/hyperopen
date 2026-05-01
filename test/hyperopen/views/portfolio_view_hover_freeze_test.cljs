(ns hyperopen.views.portfolio-view-hover-freeze-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.views.chart.d3.hover-state :as chart-hover-state]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.portfolio.test-support :refer [sample-state]]))

(defn- node-children [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- find-first-node [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(use-fixtures :each
  (fn [f]
    (chart-hover-state/clear-hover-state!)
    (f)
    (chart-hover-state/clear-hover-state!)))

(deftest portfolio-view-freezes-shared-sections-while-chart-hover-is-active-test
  (let [route "/portfolio-hover-cache-test"
        base-state (assoc sample-state :router {:path route})
        initial-state (assoc-in base-state [:portfolio-ui :chart-tab] :account-value)
        updated-state (assoc-in base-state [:portfolio-ui :chart-tab] :pnl)
        tab-pressed? (fn [view data-role]
                       (true? (get-in (find-first-node view #(= data-role (get-in % [1 :data-role])))
                                      [1 :aria-pressed])))]
    (let [initial-view (portfolio-view/portfolio-view initial-state)]
      (is (true? (tab-pressed? initial-view "portfolio-chart-tab-account-value")))
      (is (not (true? (tab-pressed? initial-view "portfolio-chart-tab-pnl")))))
    (chart-hover-state/set-surface-hover-active! :portfolio true)
    (let [hover-view (portfolio-view/portfolio-view updated-state)
          hover-text (set (collect-strings hover-view))]
      (is (true? (tab-pressed? hover-view "portfolio-chart-tab-account-value")))
      (is (not (true? (tab-pressed? hover-view "portfolio-chart-tab-pnl"))))
      (is (contains? hover-text "Account Value")))
    (chart-hover-state/set-surface-hover-active! :portfolio false)
    (let [updated-view (portfolio-view/portfolio-view updated-state)]
      (is (true? (tab-pressed? updated-view "portfolio-chart-tab-pnl")))
      (is (not (true? (tab-pressed? updated-view "portfolio-chart-tab-account-value")))))))
