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
                                    :nav-usdc 1000
                                    :weight-drift-rms 0.1
                                    :max-abs-weight-drift 0.12
                                    :predicted-return 0.18
                                    :predicted-volatility 0.32
                                    :realized-return 0.03
                                    :rows [{:instrument-id "perp:BTC"
                                            :current-weight 0.5
                                            :target-weight 0.6
                                            :weight-drift -0.1
                                            :signed-notional-usdc 500}]}]}}}})
        panel (node-by-role view-node "portfolio-optimizer-tracking-panel")
        refresh-button (node-by-role view-node
                                     "portfolio-optimizer-refresh-tracking")
        reoptimize-button (node-by-role view-node
                                        "portfolio-optimizer-reoptimize-current")
        strings (set (collect-strings view-node))]
    (is (some? panel))
    (is (= [[:actions/refresh-portfolio-optimizer-tracking]]
           (click-actions refresh-button)))
    (is (= [[:actions/run-portfolio-optimizer-from-draft]]
           (click-actions reoptimize-button)))
    (is (contains? strings "Tracking"))
    (is (contains? strings "Weight Drift RMS"))
    (is (contains? strings "Predicted Return"))
    (is (contains? strings "Predicted Vol"))
    (is (contains? strings "Drift Chart"))
    (is (contains? strings "Realized vs Predicted"))
    (is (contains? strings "Re-optimize From Current"))
    (is (contains? strings "perp:BTC"))))

(deftest saved-scenario-renders-manual-tracking-enable-panel-test
  (let [view-node
        (portfolio-view/portfolio-view
         {:router {:path "/portfolio/optimize/scn_track"}
          :portfolio {:optimizer
                      {:active-scenario {:loaded-id "scn_track"
                                         :status :saved}
                       :tracking {:status :idle
                                  :scenario-id "scn_track"
                                  :snapshots []}}}})
        panel (node-by-role view-node "portfolio-optimizer-tracking-panel")
        enable-button (node-by-role view-node
                                    "portfolio-optimizer-enable-manual-tracking")
        strings (set (collect-strings view-node))]
    (is (some? panel))
    (is (= [[:actions/enable-portfolio-optimizer-manual-tracking]]
           (click-actions enable-button)))
    (is (contains? strings "Tracking Not Active"))
    (is (contains? strings "Enable Manual Tracking"))))

(deftest unsaved-computed-scenario-disables-manual-tracking-enable-test
  (let [view-node
        (portfolio-view/portfolio-view
         {:router {:path "/portfolio/optimize/new"}
          :portfolio {:optimizer
                      {:active-scenario {:loaded-id nil
                                         :status :computed}
                       :draft {:status :computed}
                       :tracking {:status :idle
                                  :snapshots []}}}})
        enable-button (node-by-role view-node
                                    "portfolio-optimizer-enable-manual-tracking")
        strings (set (collect-strings view-node))]
    (is (= true (get-in enable-button [1 :disabled])))
    (is (nil? (click-actions enable-button)))
    (is (contains? strings "Save scenario before enabling manual tracking."))))
