(ns hyperopen.views.portfolio.optimize.tracking-panel-test
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

(deftest executed-scenario-renders-tracking-panel-test
  (let [view-node
        (portfolio-view/portfolio-view
         {:router {:path "/portfolio/optimize/scn_track"}
          :portfolio {:optimizer
                      {:active-scenario {:loaded-id "scn_track"
                                         :status :executed}
                       :tracking {:scenario-id "scn_track"
                                  :updated-at-ms 2000
                                  :snapshots
                                  [{:scenario-id "scn_track"
                                    :as-of-ms 2000
                                    :status :tracked
                                    :weight-drift-rms 0.1
                                    :distance-to-target 0.1
                                    :max-abs-weight-drift 0.12
                                    :predicted-return 0.18
                                    :realized-return nil
                                    :rows [{:instrument-id "perp:BTC"
                                            :current-weight 0.5
                                            :target-weight 0.6
                                            :weight-drift -0.1
                                            :signed-notional-usdc 500}]}]}}}})
        panel (node-by-role view-node "portfolio-optimizer-tracking-panel")
        refresh-button (node-by-role view-node
                                     "portfolio-optimizer-refresh-tracking")
        strings (set (collect-strings view-node))]
    (is (some? panel))
    (is (= [[:actions/refresh-portfolio-optimizer-tracking]]
           (click-actions refresh-button)))
    (is (contains? strings "Tracking"))
    (is (contains? strings "Weight Drift RMS"))
    (is (contains? strings "Predicted Return"))
    (is (contains? strings "perp:BTC"))))
