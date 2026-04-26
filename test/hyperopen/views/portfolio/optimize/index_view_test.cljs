(ns hyperopen.views.portfolio.optimize.index-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.system :as app-system]
            [hyperopen.views.portfolio.optimize.index-view :as index-view]
            [nexus.registry :as nxr]))

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

(deftest index-view-renders-saved-scenario-board-test
  (let [view-node (index-view/index-view
                   {:portfolio {:optimizer
                                {:scenario-index
                                 {:ordered-ids ["scn_02" "scn_01"]
                                  :by-id {"scn_02" {:id "scn_02"
                                                     :name "Fresh Run"
                                                     :status :saved
                                                     :objective-kind :minimum-variance
                                                     :return-model-kind :historical-mean
                                                     :risk-model-kind :diagonal-shrink
                                                     :expected-return 0.12
                                                     :volatility 0.24
                                                     :updated-at-ms 4000}
                                          "scn_01" {:id "scn_01"
                                                     :name "Core Hedge"
                                                     :status :partially-executed
                                                     :objective-kind :max-sharpe
                                                     :return-model-kind :black-litterman
                                                     :risk-model-kind :diagonal-shrink
                                                     :expected-return 0.18
                                                     :volatility 0.42
                                                     :updated-at-ms 3000}}}}}})
        row (node-by-role view-node "portfolio-optimizer-scenario-row-scn_01")
        strings (set (collect-strings view-node))]
    (is (nil? (node-by-role view-node "portfolio-optimizer-empty-scenarios")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-board")))
    (is (some? row))
    (is (= [[:actions/navigate "/portfolio/optimize/scn_01"]]
           (click-actions row)))
    (let [duplicate-click (click-actions
                           (node-by-role view-node
                                         "portfolio-optimizer-scenario-duplicate-scn_01"))
          archive-click (click-actions
                         (node-by-role view-node
                                       "portfolio-optimizer-scenario-archive-scn_01"))
          dispatches* (atom [])
          prevent-calls* (atom 0)
          stop-calls* (atom 0)
          event #js {:preventDefault (fn []
                                       (swap! prevent-calls* inc))
                     :stopPropagation (fn []
                                        (swap! stop-calls* inc))}]
      (is (fn? duplicate-click))
      (is (fn? archive-click))
      (with-redefs [app-system/store ::store
                    nxr/dispatch (fn [store event actions]
                                   (swap! dispatches* conj {:store store
                                                            :event event
                                                            :actions actions}))]
        (duplicate-click event)
        (archive-click event))
      (is (= 2 @prevent-calls*))
      (is (= 2 @stop-calls*))
      (is (= [{:store ::store
               :event nil
               :actions [[:actions/duplicate-portfolio-optimizer-scenario "scn_01"]]}
              {:store ::store
               :event nil
               :actions [[:actions/archive-portfolio-optimizer-scenario "scn_01"]]}]
             @dispatches*)))
    (is (contains? strings "Fresh Run"))
    (is (contains? strings "Core Hedge"))
    (is (contains? strings "partially-executed"))
    (is (contains? strings "Black Litterman"))
    (is (contains? strings "Duplicate"))
    (is (contains? strings "Archive"))
    (is (contains? strings "18.00%"))))
