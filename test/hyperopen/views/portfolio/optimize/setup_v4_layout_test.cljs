(ns hyperopen.views.portfolio.optimize.setup-v4-layout-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
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

(defn- child-roles
  [node]
  (->> (node-children node)
       (keep #(get-in % [1 :data-role]))
       vec))

(defn- node-text
  [node]
  (apply str (collect-strings node)))

(defn- click-actions
  [node]
  (get-in node [1 :on :click]))

(defn- input-actions
  [node]
  (get-in node [1 :on :input]))

(defn- class-token-set
  [node]
  (set (get-in node [1 :class])))

(defn- count-nodes
  [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)]
      (+ (if (pred node) 1 0)
         (reduce + 0 (map #(count-nodes % pred) children))))

    (seq? node)
    (reduce + 0 (map #(count-nodes % pred) node))

    :else 0))

(deftest setup-new-route-uses-v4-grid-instead-of-old-left-rail-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :wallet {:address "0x1111111111111111111111111111111111111111"}})
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-route-surface")))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-header")))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-preset-row")))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-surface")))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-control-rail")))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-summary-pane")))
    (is (some? (node-by-role view-node "portfolio-optimizer-assumptions-rail")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-trust-freshness-panel")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-left-rail")))
    (is (contains? strings "Optimizer · portfolio / optimize / new"))
    (is (contains? strings "Start with"))
    (is (contains? strings "From holdings"))
    (is (contains? strings "Custom"))
    (is (contains? strings "What this scenario will solve for"))
    (is (contains? strings "Why this preset is safe"))
    (is (not (contains? strings "Execution Assumptions")))))

(deftest setup-v4-control-rail-orders-objective-before-return-risk-model-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? true}}}}})
        control-rail (node-by-role view-node "portfolio-optimizer-setup-control-rail")]
    (is (= ["portfolio-optimizer-universe-panel"
            "portfolio-optimizer-objective-panel"
            "portfolio-optimizer-return-risk-panel"
            "portfolio-optimizer-constraints-panel"
            "portfolio-optimizer-advanced-overrides-shell"]
           (child-roles control-rail)))
    (is (str/includes? (node-text (node-by-role view-node "portfolio-optimizer-objective-panel"))
                       "02Objective"))
    (is (str/includes? (node-text (node-by-role view-node "portfolio-optimizer-return-risk-panel"))
                       "03Return / Risk Model"))))

(deftest setup-v4-return-risk-and-constraints-panels-are-collapsed-disclosures-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? true
                                                       :max-asset-weight 0.25
                                                       :gross-max 2}}}}})
        model-panel (node-by-role view-node "portfolio-optimizer-return-risk-panel")
        constraints-panel (node-by-role view-node "portfolio-optimizer-constraints-panel")
        advanced-panel (node-by-role view-node "portfolio-optimizer-advanced-overrides-shell")]
    (doseq [panel [model-panel constraints-panel advanced-panel]]
      (is (= :details (first panel)))
      (is (not (contains? (second panel) :open)))
      (is (= :summary (first (first (node-children panel))))))
    (is (some? (node-by-role model-panel "portfolio-optimizer-return-model-panel")))
    (is (some? (node-by-role model-panel "portfolio-optimizer-risk-model-panel")))
    (is (some? (node-by-role constraints-panel
                              "portfolio-optimizer-constraint-gross-max-input")))))

(deftest setup-v4-layout-preserves-optimizer-control-actions-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? true
                                                       :max-asset-weight 0.25
                                                       :gross-max 2}}}}})
        run-button (node-by-role view-node "portfolio-optimizer-run-draft")]
    (is (= false (get-in run-button [1 :disabled])))
    (is (= [[:actions/run-portfolio-optimizer-from-draft]]
           (click-actions run-button)))
    (is (= [[:actions/set-portfolio-optimizer-universe-from-current]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-universe-use-current"))))
    (is (= [[:actions/set-portfolio-optimizer-universe-search-query
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-universe-search-input"))))
    (is (= [[:actions/set-portfolio-optimizer-objective-kind :max-sharpe]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-objective-max-sharpe"))))
    (is (= [[:actions/set-portfolio-optimizer-return-model-kind :black-litterman]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-return-model-black-litterman"))))
    (is (= [[:actions/set-portfolio-optimizer-risk-model-kind :sample-covariance]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-risk-model-sample-covariance"))))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :gross-max
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-gross-max-input"))))))

(deftest setup-v4-return-risk-model-buttons-expose-tooltips-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? true}}}}})
        expectations [["portfolio-optimizer-return-model-historical-mean"
                       "Uses the arithmetic mean of historical returns for each selected asset."]
                      ["portfolio-optimizer-return-model-ew-mean"
                       "Uses exponentially weighted historical returns so recent observations count more."]
                      ["portfolio-optimizer-return-model-black-litterman"
                       "Combines market-implied returns with your Black-Litterman views and confidence inputs."]
                      ["portfolio-optimizer-risk-model-diagonal-shrink"
                       "Shrinks the covariance estimate toward a diagonal model to reduce noisy cross-asset correlations."]
                      ["portfolio-optimizer-risk-model-sample-covariance"
                       "Uses the raw historical covariance matrix from the selected asset return history."]]]
    (doseq [[role copy] expectations]
      (let [button (node-by-role view-node role)
            tooltip-id (str role "-tooltip")
            tooltip (node-by-role view-node tooltip-id)]
        (is (= tooltip-id (get-in button [1 :aria-describedby])))
        (is (contains? (class-token-set button)
                       "focus:shadow-[inset_0_0_0_1px_rgba(212,181,88,0.75)]"))
        (is (= "tooltip" (get-in tooltip [1 :role])))
        (is (= copy (node-text tooltip)))))))

(deftest setup-v4-universe-search-renders-as-single-integrated-control-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio-ui {:optimizer {:universe-search-query "TIA"}}
                    :portfolio {:optimizer {:draft {:universe []
                                                     :constraints {:long-only? false}}}}})
        search-shell (node-by-role view-node "portfolio-optimizer-universe-search-shell")
        search-icon (node-by-role view-node "portfolio-optimizer-universe-search-icon")
        search-input (node-by-role view-node "portfolio-optimizer-universe-search-input")
        clear-button (node-by-role view-node "portfolio-optimizer-universe-search-clear")
        add-hint (node-by-role view-node "portfolio-optimizer-universe-search-add-hint")]
    (is (some? search-shell))
    (is (= "true" (get-in search-shell [1 :data-searching])))
    (is (contains? (class-token-set search-shell)
                   "portfolio-optimizer-universe-search-shell"))
    (is (some? search-icon))
    (is (contains? (class-token-set search-icon)
                   "portfolio-optimizer-universe-search-affordance"))
    (is (contains? (class-token-set search-input)
                   "portfolio-optimizer-universe-search-field"))
    (is (some? clear-button))
    (is (contains? (class-token-set clear-button)
                   "portfolio-optimizer-universe-search-affordance"))
    (is (some? add-hint))
    (is (= ["↵ add"] (collect-strings add-hint)))
    (is (contains? (class-token-set add-hint)
                   "portfolio-optimizer-universe-search-add-hint"))
    (is (not (contains? (class-token-set add-hint) "bg-warning/10")))
    (is (not (contains? (class-token-set add-hint) "bg-base-200")))
    (is (not (contains? (class-token-set add-hint) "uppercase")))
    (is (not (contains? (class-token-set add-hint) "tracking-[0.1em]")))))

(deftest setup-v4-run-action-renders-under-center-assumptions-panel-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? true
                                                       :max-asset-weight 0.25
                                                       :gross-max 2}}}}})
        route-surface (node-by-role view-node "portfolio-optimizer-setup-route-surface")
        header (node-by-role view-node "portfolio-optimizer-setup-header")
        summary-pane (node-by-role view-node "portfolio-optimizer-setup-summary-pane")
        assumptions-stack (node-by-role view-node "portfolio-optimizer-model-assumptions-stack")
        assumptions-panel (node-by-role view-node "portfolio-optimizer-model-assumptions-panel")
        action-bar (node-by-role view-node "portfolio-optimizer-setup-bottom-actions")
        run-button (node-by-role action-bar "portfolio-optimizer-run-draft")
        save-button (node-by-role action-bar "portfolio-optimizer-save-scenario")
        action-bar-children (vec (node-children action-bar))
        run-index (.indexOf action-bar-children run-button)
        save-index (.indexOf action-bar-children save-button)
        assumptions-stack-children (vec (node-children assumptions-stack))
        assumptions-index (.indexOf assumptions-stack-children assumptions-panel)
        action-bar-index (.indexOf assumptions-stack-children action-bar)
        route-child-action-index (.indexOf (vec (node-children route-surface)) action-bar)]
    (is (some? summary-pane))
    (is (some? assumptions-stack))
    (is (some? action-bar))
    (is (< assumptions-index action-bar-index))
    (is (= -1 route-child-action-index))
    (is (= 0 run-index))
    (is (= 1 save-index))
    (is (= 0 (count-nodes header #(= "portfolio-optimizer-run-draft"
                                     (get-in % [1 :data-role])))))
    (is (= 1 (count-nodes view-node #(= "portfolio-optimizer-run-draft"
                                        (get-in % [1 :data-role])))))
    (is (= false (get-in run-button [1 :disabled])))
    (is (= [[:actions/run-portfolio-optimizer-from-draft]]
           (click-actions run-button)))
    (is (= [[:actions/save-portfolio-optimizer-scenario-from-current]]
           (click-actions save-button)))))

(deftest setup-v4-constraints-explain-each-control-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:constraints {:long-only? true
                                                       :max-asset-weight 0.25
                                                       :gross-max 3
                                                       :net-max 1.5
                                                       :dust-usdc 50
                                                       :max-turnover 1
                                                       :rebalance-tolerance 0.03}}}}})
        strings (set (collect-strings view-node))
        max-weight (node-by-role
                    view-node
                    "portfolio-optimizer-constraint-max-asset-weight-input")
        max-weight-tooltip (node-by-role
                            view-node
                            "portfolio-optimizer-constraint-max-asset-weight-input-tooltip")
        long-only (node-by-role
                   view-node
                   "portfolio-optimizer-constraint-long-only-input")
        long-only-tooltip (node-by-role
                           view-node
                           "portfolio-optimizer-constraint-long-only-tooltip")]
    (is (= "portfolio-optimizer-constraint-max-asset-weight-input-tooltip"
           (get-in max-weight [1 :aria-describedby])))
    (is (= "tooltip" (get-in max-weight-tooltip [1 :role])))
    (is (= "portfolio-optimizer-constraint-long-only-tooltip"
           (get-in long-only [1 :aria-describedby])))
    (is (= "tooltip" (get-in long-only-tooltip [1 :role])))
    (is (contains? strings
                   "Maximum target portfolio weight any single asset can receive. 0.5 means no asset can exceed 50%."))
    (is (contains? strings
                   "Maximum total absolute exposure across all legs. 1 means long exposure plus short exposure can total up to 100% of capital."))
    (is (contains? strings
                   "Small rebalance trades below this USDC notional are ignored so the output avoids noisy dust orders."))
    (is (contains? strings
                   "Minimum target-vs-current weight difference before a rebalance row is considered actionable. 0.03 means 3 percentage points."))))
