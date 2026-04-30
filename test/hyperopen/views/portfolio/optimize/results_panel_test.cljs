(ns hyperopen.views.portfolio.optimize.results-panel-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.views.portfolio.optimize.frontier-callout :as frontier-callout]
            [hyperopen.views.portfolio.optimize.results-panel :as results-panel]))

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

(defn- collect-nodes
  [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)
          matches (when (pred node) [node])]
      (concat matches (mapcat #(collect-nodes % pred) children)))

    (seq? node)
    (mapcat #(collect-nodes % pred) node)

    :else []))

(defn- node-by-role
  [node role]
  (find-first-node node #(= role (get-in % [1 :data-role]))))

(defn- text-node
  [node value]
  (find-first-node
   node
   #(and (= :text (first %))
         (some #{value} (collect-strings %)))))

(defn- click-actions
  [node]
  (get-in node [1 :on :click]))

(defn- node-attr
  [node attr]
  (get-in node [1 attr]))

(defn- data-role-order
  [node]
  (keep #(get-in % [1 :data-role])
        (collect-nodes node #(some? (get-in % [1 :data-role])))))

(defn- index-of
  [coll value]
  (first (keep-indexed (fn [idx item]
                         (when (= value item) idx))
                       coll)))

(defn- drag-start-actions
  [node]
  (get-in node [1 :on :drag-start]))

(defn- drag-enter-actions
  [node]
  (get-in node [1 :on :drag-enter]))

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

(def solved-result
  (fixtures/sample-solved-result
   {:instrument-ids ["perp:BTC" "spot:PURR"]
    :current-weights [0.2 0.1]
    :target-weights [0.35 -0.02]
    :target-weights-by-instrument {"perp:BTC" 0.35
                                   "spot:PURR" -0.02}
    :current-weights-by-instrument {"perp:BTC" 0.2
                                    "spot:PURR" 0.1}
    :expected-return 0.18
    :volatility 0.42
    :performance {:in-sample-sharpe 0.43
                  :shrunk-sharpe 0.215}
    :history-summary {:return-observations 2
                      :stale? false}
    :frontier [{:id 0
                :expected-return 0.12
                :volatility 0.24
                :sharpe 0.5
                :weights [0.5 0.5]}
               {:id 1
                :expected-return 0.18
                :volatility 0.42
                :sharpe 0.43
                :weights [0.5 0.5]}]
    :frontier-summary {:source :display-sweep
                       :point-count 2}
    :frontier-overlays
    {:standalone [{:instrument-id "perp:BTC"
                   :label "BTC"
                   :target-weight 0.35
                   :expected-return 0.12
                   :volatility 0.4}
                  {:instrument-id "spot:PURR"
                   :label "PURR"
                   :target-weight -0.02
                   :expected-return 0.08
                   :volatility 0.22}]
     :contribution [{:instrument-id "perp:BTC"
                     :label "BTC"
                     :target-weight 0.35
                     :expected-return 0.042
                     :volatility 0.14}
                    {:instrument-id "spot:PURR"
                     :label "PURR"
                     :target-weight -0.02
                     :expected-return -0.0016
                     :volatility -0.01}]}
    :return-decomposition-by-instrument
    {"perp:BTC" {:return-component 0.12
                 :funding-component 0.04
                 :funding-source :market-funding-history}
     "spot:PURR" {:return-component 0.08
                  :funding-component 0
                  :funding-source :missing}}
    :diagnostics {:gross-exposure 0.37
                  :net-exposure 0.33
                  :effective-n 2.2
                  :turnover 0.135
                  :covariance-conditioning {:status :watch
                                            :condition-number 12000
                                            :min-eigenvalue 0.001}
                  :weight-sensitivity-by-instrument
                  {"perp:BTC" {:base-expected-return 0.18
                               :down-expected-return 0.17
                               :up-expected-return 0.19}}
                  :binding-constraints [{:instrument-id "perp:BTC"
                                         :constraint :upper-bound}]}
    :warnings [{:code :low-invested-exposure
                :message "Minimum variance selected a near-cash signed portfolio."}]
    :rebalance-preview {:status :partially-blocked
                        :capital-usd 10000
                        :summary {:ready-count 1
                                  :blocked-count 1
                                  :gross-trade-notional-usd 2700
                                  :estimated-fees-usd 1.2
                                  :estimated-slippage-usd 2.4}
                        :rows [{:instrument-id "perp:BTC"
                                :status :ready
                                :side :buy
                                :delta-notional-usd 1500}
                               {:instrument-id "spot:PURR"
                                :status :blocked
                                :reason :spot-submit-unsupported
                                :side :sell
                                :delta-notional-usd -1200}]}}))

(deftest results-panel-renders-v1-results-workspace-contract-test
  (let [draft {:objective {:kind :target-volatility}
               :metadata {:dirty? true}}
        view-node (results-panel/results-panel
                   {:result solved-result
                    :computed-at-ms 2600}
                   draft
                   {:stale? true
                    :frontier-overlay-mode :standalone})
        contribution-view-node (results-panel/results-panel
                                {:result solved-result
                                 :computed-at-ms 2600}
                                draft
                                {:frontier-overlay-mode :contribution})
        frontier-point (node-by-role view-node "portfolio-optimizer-frontier-point-1")
        standalone-toggle (node-by-role view-node
                                        "portfolio-optimizer-frontier-overlay-mode-standalone")
        contribution-toggle (node-by-role view-node
                                          "portfolio-optimizer-frontier-overlay-mode-contribution")
        standalone-path (node-by-role view-node
                                      "portfolio-optimizer-frontier-path")
        contribution-path (node-by-role contribution-view-node
                                        "portfolio-optimizer-frontier-path")
        target-marker (node-by-role view-node
                                    "portfolio-optimizer-frontier-target-marker")
        current-marker (node-by-role view-node
                                     "portfolio-optimizer-frontier-current-marker")
        target-callout (node-by-role view-node
                                     "portfolio-optimizer-frontier-callout-target")
        target-core (node-by-role view-node
                                  "portfolio-optimizer-frontier-target-core")
        target-halo (node-by-role view-node
                                  "portfolio-optimizer-frontier-target-halo")
        target-ring (node-by-role view-node
                                  "portfolio-optimizer-frontier-target-ring")
        target-highlight (node-by-role view-node
                                       "portfolio-optimizer-frontier-target-highlight")
        target-label (node-by-role view-node
                                   "portfolio-optimizer-frontier-target-label")
        target-leader-line (node-by-role view-node
                                          "portfolio-optimizer-frontier-target-leader-line")
        target-defs (node-by-role view-node
                                  "portfolio-optimizer-frontier-target-defs")
        target-orb-gradient (first (collect-nodes
                                    target-defs
                                    #(= "portfolioOptimizerTargetOrbGradient"
                                        (node-attr % :id))))
        target-orb-stops (vec (collect-nodes target-orb-gradient #(= :stop (first %))))
        legend (node-by-role view-node
                             "portfolio-optimizer-frontier-legend")
        legend-target-dot (node-by-role view-node
                                        "portfolio-optimizer-frontier-legend-target-dot")
        frontier-callout (node-by-role view-node
                                      "portfolio-optimizer-frontier-callout-frontier-1")
        standalone-callout (node-by-role view-node
                                        "portfolio-optimizer-frontier-callout-standalone-perp:BTC")
        standalone-symbol (node-by-role view-node
                                        "portfolio-optimizer-frontier-overlay-symbol-standalone-perp:BTC")
        standalone-purr-marker (node-by-role view-node
                                             "portfolio-optimizer-frontier-overlay-standalone-spot:PURR")
        contribution-callout (node-by-role contribution-view-node
                                          "portfolio-optimizer-frontier-callout-contribution-perp:BTC")
        contribution-symbol (node-by-role contribution-view-node
                                          "portfolio-optimizer-frontier-overlay-symbol-contribution-perp:BTC")
        x-axis-label (node-by-role view-node
                                   "portfolio-optimizer-frontier-x-axis-label")
        y-axis-label (node-by-role view-node
                                   "portfolio-optimizer-frontier-y-axis-label")
        x-axis-ticks (node-by-role view-node
                                   "portfolio-optimizer-frontier-x-axis-ticks")
        y-axis-ticks (node-by-role view-node
                                   "portfolio-optimizer-frontier-y-axis-ticks")
        svg-role-order (vec (data-role-order
                             (node-by-role view-node
                                           "portfolio-optimizer-frontier-svg")))
        frontier-point-actions [[:actions/set-portfolio-optimizer-objective-kind :target-volatility]
                                [:actions/set-portfolio-optimizer-objective-parameter
                                 :target-volatility
                                 0.42]]
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-results-surface")))
    (is (some? (node-by-role view-node "portfolio-optimizer-stale-result-banner")))
   (is (= [[:actions/run-portfolio-optimizer-from-draft]]
           (click-actions
            (node-by-role view-node "portfolio-optimizer-rerun-stale-result"))))
    (is (some? (node-by-role view-node "portfolio-optimizer-results-grid")))
    (is (some? (node-by-role view-node "portfolio-optimizer-results-left-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-results-center-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-results-right-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-trust-caution-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-frontier-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-frontier-svg")))
    (is (some? (node-by-role view-node "portfolio-optimizer-frontier-path")))
    (is (= "Volatility (Annualized)" (first (collect-strings x-axis-label))))
    (is (= "Expected Return (Annualized)" (first (collect-strings y-axis-label))))
    (is (= "middle" (node-attr x-axis-label :text-anchor)))
    (is (= "middle" (node-attr y-axis-label :text-anchor)))
    (is (= "middle" (node-attr x-axis-label :dominant-baseline)))
    (is (= "middle" (node-attr y-axis-label :dominant-baseline)))
    (is (nil? (node-attr x-axis-label :textAnchor)))
    (is (nil? (node-attr y-axis-label :textAnchor)))
    (is (<= 5 (count (collect-nodes x-axis-ticks #(= :text (first %))))))
    (is (<= 5 (count (collect-nodes y-axis-ticks #(= :text (first %))))))
    (is (contains? (set (collect-strings x-axis-ticks)) "0%"))
    (is (contains? (set (collect-strings y-axis-ticks)) "0%"))
    (is (= (node-attr standalone-path :d)
           (node-attr contribution-path :d))
        "Overlay mode should not move the efficient frontier.")
    (is (some? target-marker))
    (is (= 0 (node-attr target-marker :tabIndex)))
    (is (nil? (node-attr target-marker :opacity)))
    (is (nil? (node-by-role target-marker "portfolio-optimizer-frontier-target-visual")))
    (is (< (index-of svg-role-order "portfolio-optimizer-frontier-point-1")
           (index-of svg-role-order "portfolio-optimizer-frontier-callout-frontier-1"))
        "Frontier point callouts must paint above frontier points.")
    (is (< (index-of svg-role-order "portfolio-optimizer-frontier-target-marker")
           (index-of svg-role-order "portfolio-optimizer-frontier-callout-frontier-1"))
        "Frontier point callouts must paint above the target marker.")
    (is (< (index-of svg-role-order "portfolio-optimizer-frontier-overlay-standalone-perp:BTC")
           (index-of svg-role-order "portfolio-optimizer-frontier-callout-frontier-1"))
        "Frontier point callouts must paint above overlay markers.")
    (is (< (index-of svg-role-order "portfolio-optimizer-frontier-overlay-standalone-spot:PURR")
           (index-of svg-role-order "portfolio-optimizer-frontier-callout-target"))
        "Target callouts must paint above overlay markers.")
    (is (< (index-of svg-role-order "portfolio-optimizer-frontier-overlay-standalone-perp:BTC")
           (index-of svg-role-order "portfolio-optimizer-frontier-callout-standalone-perp:BTC"))
        "Overlay callouts must paint above their own marker.")
    (is (< (index-of svg-role-order "portfolio-optimizer-frontier-overlay-standalone-spot:PURR")
           (index-of svg-role-order "portfolio-optimizer-frontier-callout-standalone-perp:BTC"))
        "Overlay callouts must paint above later overlay markers.")
    (is (= "frontier-1" (node-attr frontier-point :data-frontier-callout-trigger)))
    (is (= "frontier-1" (node-attr frontier-callout :data-frontier-callout-id)))
    (is (some? target-defs))
    (is (some? target-core))
    (is (= "url(#portfolioOptimizerTargetOrbGradient)"
           (node-attr target-core :fill)))
    (is (= "rgba(246, 235, 255, 0.58)"
           (node-attr target-core :stroke)))
    (is (= 6 (count target-orb-stops)))
    (is (= ["#ffffff" "#7cecff" "#5d7cff" "#8b5cff" "#b54cff" "#ff4fd8"]
           (mapv #(node-attr % :stop-color) target-orb-stops)))
    (is (empty? (filter #{"#fff4c6" "#ffb86b"}
                        (map #(node-attr % :stop-color) target-orb-stops))))
    (is (some? target-halo))
    (is (= "url(#portfolioOptimizerTargetHaloGradient)"
           (node-attr target-halo :fill)))
    (is (= 17 (node-attr target-halo :r)))
    (is (= 0.52 (node-attr target-halo :opacity)))
    (is (some? target-ring))
    (is (= "url(#portfolioOptimizerTargetRingGradient)"
           (node-attr target-ring :stroke)))
    (is (= 11.5 (node-attr target-ring :r)))
    (is (= 1.15 (node-attr target-ring :strokeWidth)))
    (is (= 0.74 (node-attr target-ring :opacity)))
    (is (some? target-highlight))
    (is (some? target-label))
    (is (some? target-leader-line))
    (is (= "3 3" (node-attr target-leader-line :stroke-dasharray)))
    (is (nil? current-marker))
    (is (some? target-callout))
    (is (= "url(#portfolioOptimizerTargetTooltipBorderGradient)"
           (node-attr (first (collect-nodes target-callout
                                            #(and (= :rect (first %))
                                                  (= "portfolio-optimizer-frontier-callout-target-border"
                                                     (node-attr % :data-role)))))
                      :fill)))
    (is (some? frontier-callout))
    (is (some? standalone-callout))
    (is (some? contribution-callout))
    (is (= "portfolio-frontier-asset-icon-marker"
           (node-attr standalone-symbol :class)))
    (is (= "https://app.hyperliquid.xyz/coins/BTC.svg"
           (node-attr (first (collect-nodes standalone-symbol #(= :image (first %)))) :href)))
    (is (= "portfolio-frontier-asset-icon-marker"
           (node-attr contribution-symbol :class)))
    (is (= "https://app.hyperliquid.xyz/coins/BTC.svg"
           (node-attr (first (collect-nodes contribution-symbol #(= :image (first %)))) :href)))
    (is (nil? (first (collect-nodes
                      (node-by-role view-node
                                    "portfolio-optimizer-frontier-overlay-standalone-perp:BTC")
                      #(and (= :rect (first %))
                            (some? (node-attr % :transform))))))
        "Standalone asset markers should use symbol text instead of diamond rects.")
    (is (nil? (first (collect-nodes
                      (node-by-role contribution-view-node
                                    "portfolio-optimizer-frontier-overlay-contribution-perp:BTC")
                      #(= :path (first %)))))
        "Contribution asset markers should use symbol text instead of triangle paths.")
    (is (empty? (collect-nodes view-node #(= :title (first %))))
        "SVG native title nodes should not create a second browser tooltip.")
    (is (= "var(--optimizer-accent)"
           (node-attr (first (collect-nodes frontier-callout #(= :rect (first %)))) :stroke)))
    (is (= "none" (node-attr (first (collect-nodes standalone-callout #(= :rect (first %)))) :stroke)))
    (is (some? (first (collect-nodes standalone-callout #(= :line (first %)))))
        "Callouts should visually separate the title from metric rows.")
    (is (= "none" (node-attr standalone-callout :pointer-events))
        "Callouts should not intercept marker hover targets.")
    (is (= "end" (node-attr (text-node standalone-callout "40.00%") :text-anchor)))
    (is (nil? (node-attr (text-node standalone-callout "40.00%") :textAnchor)))
    (is (= #{"Target"
             "Expected Return"
             "Volatility"
             "Sharpe"
             "Gross Exposure"
             "Net Exposure"
             "18.00%"
             "42.00%"
             "0.43"
             "37.00%"
             "33.00%"}
           (set (collect-strings target-callout))))
    (is (not (contains? strings "Current Portfolio")))
    (is (not (contains? strings "Where you are now")))
    (is (= #{"Frontier Point 2"
             "Expected Return"
             "Volatility"
             "Sharpe"
             "ALLOCATIONS"
             "BTC"
             "PURR"
             "Sum"
             "18.00%"
             "42.00%"
             "0.43"
             "50.0%"
             "100.0%"}
           (set (collect-strings frontier-callout))))
    (is (= #{"BTC"
             "Expected Return"
             "Volatility"
             "Sharpe"
             "Target Weight"
             "12.00%"
             "40.00%"
             "0.3"
             "35.00%"}
           (set (collect-strings standalone-callout))))
    (is (= #{"BTC"
             "Return Contribution"
             "Volatility Contribution"
             "Sharpe"
             "Target Weight"
             "4.20%"
             "14.00%"
             "0.3"
             "35.00%"}
           (set (collect-strings contribution-callout))))
    (is (some? legend))
    (is (= "radial-gradient(circle at 35% 30%, #ffffff 0%, #7cecff 14%, #5d7cff 38%, #8b5cff 66%, #ff4fd8 100%)"
           (get-in legend-target-dot [1 :style :background])))
    (is (= #{"Target"
             "Efficient frontier"
             "Standalone assets"}
           (set (collect-strings legend))))
    (is (some? standalone-toggle))
    (is (= "true" (get-in standalone-toggle [1 :aria-pressed])))
    (is (= [[:actions/set-portfolio-optimizer-frontier-overlay-mode :contribution]]
           (click-actions contribution-toggle)))
    (is (some? (node-by-role view-node
                             "portfolio-optimizer-frontier-overlay-standalone-perp:BTC")))
    (is (some? standalone-purr-marker))
    (is (nil? (node-by-role view-node
                            "portfolio-optimizer-frontier-overlay-contribution-perp:BTC")))
    (is (some? (node-by-role contribution-view-node
                             "portfolio-optimizer-frontier-overlay-contribution-perp:BTC")))
    (is (some? (node-by-role view-node "portfolio-optimizer-target-exposure-table")))
    (is (some? (node-by-role view-node "portfolio-optimizer-target-exposure-asset-BTC")))
    (is (some? (node-by-role view-node "portfolio-optimizer-target-exposure-group-BTC")))
    (is (some? (node-by-role view-node "portfolio-optimizer-target-exposure-asset-PURR")))
    (is (some? (node-by-role view-node "portfolio-optimizer-target-exposure-group-PURR")))
    (is (some? (node-by-role view-node "portfolio-optimizer-result-warnings")))
    (is (some? (node-by-role view-node "portfolio-optimizer-diagnostics-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-rebalance-preview")))
    (is (= "true"
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-target-exposure-row-0")
                   [1 :data-binding])))
    (is (= "short"
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-target-exposure-row-1")
                   [1 :data-target-sign])))
    (is (= frontier-point-actions
           (click-actions frontier-point)))
    (is (= true
           (get-in frontier-point [1 :draggable])))
    (is (= frontier-point-actions
           (drag-start-actions frontier-point)))
    (is (= frontier-point-actions
           (drag-enter-actions frontier-point)))
    (is (contains? strings "Allocation"))
    (is (contains? strings "By asset · click to expand legs"))
    (is (contains? strings "How much to trust this"))
    (is (contains? strings "Weight Stability"))
    (is (contains? strings "Effective N · 2 of 2"))
    (is (contains? strings "Target"))
    (is (not (contains? strings "Recommended target")))
    (is (contains? strings "Standalone"))
    (is (contains? strings "Contribution"))
    (is (contains? strings "watch"))
    (is (not (contains? strings "Funding Decomposition")))
    (is (contains? strings "low-invested-exposure"))
    (is (contains? strings "partially-blocked"))
    (is (contains? strings "spot-submit-unsupported"))
    (is (contains? strings "perp:BTC"))))

(deftest results-panel-renders-constrain-frontier-checkbox-above-chart-test
  (let [draft {:objective {:kind :minimum-variance}}
        result (assoc solved-result
                      :frontiers
                      {:unconstrained [{:id 0
                                        :expected-return 0.08
                                        :volatility 0.16
                                        :sharpe 0.5}
                                       {:id 1
                                        :expected-return 0.18
                                        :volatility 0.42
                                        :sharpe 0.43}
                                       {:id 2
                                        :expected-return 0.3
                                        :volatility 0.8
                                        :sharpe 0.375}]
                       :constrained (:frontier solved-result)}
                      :frontier-summaries
                      {:unconstrained {:source :display-sweep
                                       :constraint-mode :unconstrained
                                       :point-count 3}
                       :constrained {:source :display-sweep
                                     :constraint-mode :constrained
                                     :point-count 2}})
        default-view (results-panel/results-panel
                      {:result result
                       :computed-at-ms 2600}
                      draft
                      {:frontier-overlay-mode :standalone})
        constrained-view (results-panel/results-panel
                          {:result result
                           :computed-at-ms 2600}
                          draft
                          {:frontier-overlay-mode :standalone
                           :constrain-frontier? true})
        checkbox (node-by-role default-view
                               "portfolio-optimizer-constrain-frontier-checkbox")
        constrained-checkbox (node-by-role
                              constrained-view
                              "portfolio-optimizer-constrain-frontier-checkbox")]
    (is (some? checkbox))
    (is (= false (node-attr checkbox :checked)))
    (is (= true (node-attr constrained-checkbox :checked)))
    (is (= [[:actions/set-portfolio-optimizer-constrain-frontier
             :event.target/checked]]
           (get-in checkbox [1 :on :change])))
    (is (some #{"Constrain Frontier"} (collect-strings default-view)))
    (is (some #{"3 points"} (collect-strings default-view)))
    (is (some #{"2 points"} (collect-strings constrained-view)))))
