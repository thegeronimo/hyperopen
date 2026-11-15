(ns hyperopen.views.portfolio.optimize.setup-view-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [change-actions click-actions collect-nodes collect-strings
                     input-actions node-by-role]]))

(defn- portfolio-optimizer-setup-view
  [objective]
  (portfolio-view/portfolio-view
   {:router {:path "/portfolio/optimize/new"}
    :portfolio {:optimizer {:draft {:objective objective}}}}))

(defn- portfolio-optimizer-setup-view-with-draft
  [draft]
  (portfolio-view/portfolio-view
   {:router {:path "/portfolio/optimize/new"}
    :portfolio {:optimizer {:draft draft}}}))

(defn- unavailable-control?
  [node event-actions]
  (or (nil? node)
      (and (true? (get-in node [1 :disabled]))
           (nil? (event-actions node)))))

(defn- available-control?
  [node event-actions]
  (and (some? node)
       (not (true? (get-in node [1 :disabled])))
       (some? (event-actions node))))

(deftest portfolio-view-delegates-optimizer-new-route-to-setup-only-surface-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :wallet {:address "0x1111111111111111111111111111111111111111"}
                    :webdata2 {:clearinghouseState {:marginSummary {:accountValue "100"}}}})]
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-surface")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-workspace")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-scenario-detail-surface")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-scenario-tabs")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-provenance-strip")))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-header")))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-status-tag")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-setup-preset-row")))
    (is (= [[:actions/apply-portfolio-optimizer-setup-preset :conservative]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-objective-minimum-variance"))))
    (is (= [[:actions/apply-portfolio-optimizer-setup-preset :max-sharpe]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-objective-max-sharpe"))))
    (is (= [[:actions/set-portfolio-optimizer-objective-kind :equal-risk]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-objective-equal-risk"))))
    (is (nil? (node-by-role view-node "portfolio-optimizer-setup-preset-risk-adjusted")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-setup-preset-use-my-views")))
    (is (some? (node-by-role view-node "portfolio-optimizer-draft-state")))
    (is (= true
           (get-in (node-by-role view-node "portfolio-optimizer-run-draft")
                   [1 :disabled])))
    (is (= false
           (boolean
            (get-in (node-by-role view-node "portfolio-optimizer-save-scenario")
                    [1 :disabled]))))
    (is (nil? (node-by-role view-node "portfolio-optimizer-load-history")))
    (is (some? (node-by-role view-node "portfolio-optimizer-universe-panel")))
    (is (= [[:actions/set-portfolio-optimizer-universe-from-current]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-universe-use-current"))))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-model-grid")))
    (is (some? (node-by-role view-node "portfolio-optimizer-setup-model-column")))
    (is (some? (node-by-role view-node "portfolio-optimizer-objective-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-return-model-panel")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-black-litterman-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-risk-model-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-constraints-panel")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-execution-assumptions-panel")))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :long-only?
             true]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-long-only-input"))))
    (is (= "false"
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-constraint-long-only-input")
                   [1 :aria-checked])))
    (is (= "false"
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-constraint-include-spot-input")
                   [1 :aria-checked])))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :include-spot?
             true]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-include-spot-input"))))
    (is (= "0.5"
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-constraint-max-asset-weight-input")
                   [1 :value])))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :max-asset-weight
             [:event.target/value]]]
           (change-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-max-asset-weight-input"))))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :gross-max
             [:event.target/value]]]
           (change-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-gross-max-input"))))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :net-min
             [:event.target/value]]]
           (change-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-net-min-input"))))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :net-max
             [:event.target/value]]]
           (change-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-net-max-input"))))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :dust-usdc
             [:event.target/value]]]
           (change-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-dust-usdc-input"))))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :max-turnover
             [:event.target/value]]]
           (change-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-max-turnover-input"))))
    (is (= "true"
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-constraint-max-turnover-toggle")
                   [1 :aria-checked])))
    (is (= [[:actions/set-portfolio-optimizer-constraint :max-turnover nil]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-max-turnover-toggle"))))
    (is (= [[:actions/set-portfolio-optimizer-constraint
             :rebalance-tolerance
             [:event.target/value]]]
           (change-actions
            (node-by-role view-node
                          "portfolio-optimizer-constraint-rebalance-tolerance-input"))))
    (is (= [[:actions/set-portfolio-optimizer-objective-kind :target-volatility]]
           (click-actions (node-by-role view-node "portfolio-optimizer-objective-target-volatility"))))
    (is (= [[:actions/set-portfolio-optimizer-return-model-kind :black-litterman]]
           (click-actions (node-by-role view-node "portfolio-optimizer-return-model-black-litterman"))))
    (is (= [[:actions/set-portfolio-optimizer-risk-model-kind :sample-covariance]]
           (click-actions (node-by-role view-node "portfolio-optimizer-risk-model-sample-covariance"))))
    (is (= [[:actions/set-portfolio-optimizer-risk-model-kind :diagonal-shrink]]
           (click-actions (node-by-role view-node "portfolio-optimizer-risk-model-diagonal-shrink"))))
    (is (= [[:actions/set-portfolio-optimizer-risk-model-kind :mixed-frequency]]
           (click-actions (node-by-role view-node "portfolio-optimizer-risk-model-mixed-frequency"))))
    (doseq [role ["portfolio-optimizer-objective-target-return-input" "portfolio-optimizer-objective-target-volatility-input"]]
      (is (nil? (node-by-role view-node role))))
    (is (some? (node-by-role view-node "portfolio-optimizer-advanced-overrides-shell")))
    (is (some? (node-by-role view-node "portfolio-optimizer-instrument-overrides-panel")))
    (let [strings (set (collect-strings view-node))]
      (is (contains? strings "Minimum risk"))
      (is (contains? strings "Historical Mean"))
      (is (contains? strings "Diagonal Shrink"))
      (is (contains? strings "Draft clean"))
      (is (not (contains? strings "Load History")))
      (is (contains? strings "Max Asset Weight"))
      (is (contains? strings "Gross Leverage"))
      (is (contains? strings "Include Spot Assets"))
      (is (contains? strings "Rebalance Tolerance"))
      (is (not (contains? strings "Execution Assumptions")))
      (is (not (contains? strings "Fallback Slippage")))
      (is (not (contains? strings "Manual Capital Base")))
      (is (not (contains? strings "Default Order: Market")))
      (is (not (contains? strings "Fee Mode: Taker"))))))

(deftest portfolio-optimizer-turnover-cap-toggle-disables-cap-input-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer {:draft {:constraints {:max-turnover nil}}}}})
        toggle (node-by-role view-node
                             "portfolio-optimizer-constraint-max-turnover-toggle")
        input (node-by-role view-node
                            "portfolio-optimizer-constraint-max-turnover-input")
        strings (set (collect-strings view-node))]
    (is (= "false" (get-in toggle [1 :aria-checked])))
    (is (= [[:actions/set-portfolio-optimizer-constraint :max-turnover 1.0]]
           (click-actions toggle)))
    (is (= true (get-in input [1 :disabled])))
    (is (= "" (get-in input [1 :value])))
    (is (nil? (input-actions input)))
    (is (contains? strings "no cap"))))

(deftest portfolio-optimizer-objective-parameter-inputs-follow-selected-objective-test
  (let [return-role "portfolio-optimizer-objective-target-return-input"
        volatility-role "portfolio-optimizer-objective-target-volatility-input"
        view-for #(portfolio-optimizer-setup-view {:kind % :target-return 0.18 :target-volatility 0.25})
        action-for #(change-actions (node-by-role (view-for %1) %2))]
    (is (= [[:actions/set-portfolio-optimizer-objective-parameter-percent :target-return [:event.target/value]]] (action-for :target-return return-role)))
    (is (= "18" (get-in (node-by-role (view-for :target-return) return-role) [1 :value])))
    (is (= [[:actions/set-portfolio-optimizer-objective-parameter-percent :target-volatility [:event.target/value]]]
           (change-actions (node-by-role (view-for :target-volatility) volatility-role)))
        "sigma text input commits on change so the dial clamp cannot rewrite mid-typing")
    (doseq [[kind role] [[:target-return volatility-role] [:target-volatility return-role] [:max-sharpe return-role] [:max-sharpe volatility-role]]]
      (is (nil? (node-by-role (view-for kind) role))))))

(deftest portfolio-optimizer-equal-risk-setup-makes-net-controls-output-only-test
  (let [constraints {:gross-max 2.2
                     :net-min -0.4
                     :net-max 0.6
                     :net-band-pct 0.15
                     :max-asset-weight 0.5}
        view-for-kind #(portfolio-optimizer-setup-view-with-draft
                        {:objective {:kind %}
                         :constraints constraints})
        equal-risk-view (view-for-kind :equal-risk)
        other-view (view-for-kind :minimum-variance)
        equal-risk-strings (set (collect-strings equal-risk-view))]
    (testing "Equal Risk keeps gross controls available"
      (is (available-control?
           (node-by-role equal-risk-view
                         "portfolio-optimizer-exposure-gross-band")
           input-actions))
      (is (available-control?
           (node-by-role equal-risk-view
                         "portfolio-optimizer-constraint-gross-max-input")
           change-actions)))
    (testing "Equal Risk explains net as resulting output and makes net controls unavailable"
      (is (contains? equal-risk-strings "Resulting net"))
      (is (contains? equal-risk-strings
                     "Resulting net is determined by Equal Risk from covariance and selected sides."))
      (is (unavailable-control?
           (node-by-role equal-risk-view
                         "portfolio-optimizer-exposure-net-band")
           input-actions))
      (is (unavailable-control?
           (node-by-role equal-risk-view
                         "portfolio-optimizer-exposure-net-band-input")
           change-actions))
      (is (unavailable-control?
           (node-by-role equal-risk-view
                         "portfolio-optimizer-constraint-net-min-input")
           change-actions))
      (is (unavailable-control?
           (node-by-role equal-risk-view
                         "portfolio-optimizer-constraint-net-max-input")
           change-actions)))
    (testing "non Equal Risk objectives restore the existing net controls and values"
      (is (available-control?
           (node-by-role other-view
                         "portfolio-optimizer-exposure-net-band")
           input-actions))
      (is (available-control?
           (node-by-role other-view
                         "portfolio-optimizer-exposure-net-band-input")
           change-actions))
      (is (= [[:actions/set-portfolio-optimizer-constraint
               :net-min
               [:event.target/value]]]
             (change-actions
              (node-by-role other-view
                            "portfolio-optimizer-constraint-net-min-input"))))
      (is (= [[:actions/set-portfolio-optimizer-constraint
               :net-max
               [:event.target/value]]]
             (change-actions
              (node-by-role other-view
                            "portfolio-optimizer-constraint-net-max-input"))))
      (is (= "-0.4"
             (get-in (node-by-role other-view
                                   "portfolio-optimizer-constraint-net-min-input")
                     [1 :value])))
      (is (= "0.6"
             (get-in (node-by-role other-view
                                   "portfolio-optimizer-constraint-net-max-input")
                     [1 :value]))))))

(deftest portfolio-optimizer-target-sigma-setup-block-renders-percent-controls-test
  (let [view-node (portfolio-optimizer-setup-view {:kind :target-volatility
                                                   :target-volatility 0.22})
        input (node-by-role view-node "portfolio-optimizer-objective-target-volatility-input")
        slider (node-by-role view-node "portfolio-optimizer-objective-target-volatility-slider")
        strings (set (collect-strings view-node))]
    (is (= "22" (get-in input [1 :value])))
    (is (= "range" (get-in slider [1 :type])))
    (is (= 4 (get-in slider [1 :min])))
    (is (= 40 (get-in slider [1 :max])))
    (is (= "22" (get-in slider [1 :value])))
    (is (= [[:actions/set-portfolio-optimizer-objective-parameter-percent
             :target-volatility
             [:event.target/value]]]
           (input-actions slider)))
    (is (contains? strings "Target σ (annualized)"))
    (is (contains? strings "4% · defensive"))
    (is (contains? strings "40% · aggressive"))
    (is (contains? strings "Solver maximizes expected return at exactly σ = 22%. Sits between min-vol and max-return on the frontier."))
    (is (contains? strings "Pin σ to a fixed level, max return at that σ"))))

(deftest portfolio-optimizer-setup-route-shows-use-my-views-context-for-black-litterman-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"
                                                     :icon-url "https://app.hyperliquid.xyz/coins/BTC.svg"}
                                                    {:instrument-id "perp:ETH"
                                                     :market-type :perp
                                                     :coin "ETH"}]
                                         :objective {:kind :max-sharpe}
                                         :return-model {:kind :black-litterman
                                                        :views [{:id "view-1"
                                                                 :kind :absolute
                                                                 :instrument-id "perp:BTC"
                                                                 :return 0.7
                                                                 :confidence-level :high
                                                                 :confidence 0.75
                                                                 :weights {"perp:BTC" 1}}]}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? false}}}}})
        blend-shell (node-by-role view-node "portfolio-optimizer-views-blend-shell")
        inline-editor (node-by-role view-node
                                    "portfolio-optimizer-setup-use-my-views-editor")
        editor-strings (set (collect-strings inline-editor))
        btc-row (node-by-role view-node
                              "portfolio-optimizer-objective-menu-view-row-perp:BTC")
        eth-row (node-by-role view-node
                              "portfolio-optimizer-objective-menu-view-row-perp:ETH")
        btc-icon (node-by-role view-node
                               "portfolio-optimizer-objective-menu-view-perp:BTC-icon")
        btc-icon-img (first (collect-nodes btc-icon #(= :img (first %))))
        btc-return (node-by-role view-node
                                 "portfolio-optimizer-objective-menu-view-perp:BTC-return")
        btc-confidence-high (node-by-role
                             view-node
                             "portfolio-optimizer-objective-menu-view-perp:BTC-confidence-high")
        run-button (node-by-role view-node "portfolio-optimizer-run-draft")
        strings (set (collect-strings view-node))]
    (is (some? blend-shell))
    (is (some? (node-by-role blend-shell
                             "portfolio-optimizer-setup-use-my-views-context")))
    (is (contains? strings "How your views shape the forecast"))
    (is (nil? (node-by-role view-node "portfolio-optimizer-black-litterman-panel")))
    (is (= "true"
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-objective-max-sharpe")
                   [1 :aria-pressed])))
    (is (= "false"
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-objective-minimum-variance")
                   [1 :aria-pressed])))
    (is (nil? (node-by-role view-node "portfolio-optimizer-setup-preset-use-my-views")))
    (is (contains? strings "1 your view · 1 implied"))
    (is (contains? strings "Return views"))
    (is (some? inline-editor))
    (is (= :details (first inline-editor)))
    (is (true? (get-in inline-editor [1 :open])))
    (is (contains? editor-strings "Used by Maximum Sharpe"))
    (is (some? (node-by-role inline-editor
                             "portfolio-optimizer-setup-use-my-views-editor-summary")))
    (is (contains? editor-strings "1 your view · 1 implied"))
    (is (some? (node-by-role inline-editor
                             "portfolio-optimizer-setup-use-my-views-editor-filter-all")))
    (is (some? btc-row))
    (is (= "user" (get-in btc-row [1 :data-source])))
    (is (= "implied" (get-in eth-row [1 :data-source])))
    (is (= "https://app.hyperliquid.xyz/coins/BTC.svg"
           (get-in btc-icon-img [1 :src])))
    (is (= "70" (get-in btc-return [1 :value])))
    (is (= [[:actions/set-portfolio-optimizer-objective-menu-view-return
             "perp:BTC"
             [:event.target/value]]]
           (input-actions btc-return)))
    (is (= "true" (get-in btc-confidence-high [1 :data-selected])))
    (is (= [[:actions/set-portfolio-optimizer-objective-menu-view-confidence
             "perp:BTC"
             :high]]
           (click-actions btc-confidence-high)))
    (is (= [[:actions/run-portfolio-optimizer-from-draft]]
           (click-actions run-button)))))

(deftest portfolio-optimizer-equal-risk-with-black-litterman-demotes-views-editor-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}
                                                    {:instrument-id "perp:ETH"
                                                     :market-type :perp
                                                     :coin "ETH"}]
                                         :objective {:kind :equal-risk}
                                         :return-model {:kind :black-litterman
                                                        :views [{:id "view-1"
                                                                 :kind :absolute
                                                                 :instrument-id "perp:BTC"
                                                                 :return 0.7
                                                                 :confidence-level :high
                                                                 :confidence 0.75
                                                                 :weights {"perp:BTC" 1}}]}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? false}}}}})
        inactive-note (node-by-role view-node
                                    "portfolio-optimizer-return-views-inactive")
        activate (node-by-role view-node
                               "portfolio-optimizer-return-views-activate")]
    (is (nil? (node-by-role view-node
                            "portfolio-optimizer-setup-use-my-views-editor")))
    (is (nil? (node-by-role view-node
                            "portfolio-optimizer-objective-menu-view-row-perp:BTC")))
    (is (some? inactive-note))
    (is (contains? (set (collect-strings inactive-note)) "Not used by Equal Risk"))
    (is (= [[:actions/apply-portfolio-optimizer-setup-preset :max-sharpe]]
           (click-actions activate)))))
