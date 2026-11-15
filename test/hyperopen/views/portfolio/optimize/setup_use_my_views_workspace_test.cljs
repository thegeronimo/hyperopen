(ns hyperopen.views.portfolio.optimize.setup-use-my-views-workspace-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.setup-sections :as setup-sections]
            [hyperopen.views.portfolio.optimize.setup-layout-fixtures :refer [node-children find-first-node collect-strings node-by-role child-roles node-text click-actions input-actions keydown-actions day-start-ms summary-from-points class-token-set count-nodes btc-instrument eth-instrument black-litterman-ready-readiness black-litterman-ready-draft black-litterman-empty-readiness black-litterman-empty-draft candle-rows]]))

(deftest setup-black-litterman-renders-views-blend-disclosure-in-standard-pane-tail-test
  (let [view-node (setup-sections/policy-pane
                   {:state {}
                    :highlighted-controls #{}
                    :draft (black-litterman-ready-draft)
                    :readiness (black-litterman-ready-readiness)
                    :running? false
                    :run-triggerable? true
                    :saving-scenario? false
                    :solved-run? false
                    :result-path "/portfolio/optimize/scenarios/draft"})
        blend-shell (node-by-role view-node "portfolio-optimizer-views-blend-shell")
        shell-text (node-text blend-shell)
        external-legend (node-by-role view-node
                                      "portfolio-optimizer-setup-use-my-views-legend")
        legend-text (node-text external-legend)
        chart-shell (node-by-role view-node
                                  "portfolio-optimizer-setup-use-my-views-chart-shell")
        insight-cards (node-by-role view-node
                                    "portfolio-optimizer-setup-use-my-views-insight-cards")
        market-card (node-by-role insight-cards
                                  "portfolio-optimizer-setup-use-my-views-card-market-reference")
        views-card (node-by-role insight-cards
                                 "portfolio-optimizer-setup-use-my-views-card-your-views")
        output-card (node-by-role insight-cards
                                  "portfolio-optimizer-setup-use-my-views-card-combined-output")
        market-text (node-text market-card)
        views-text (node-text views-card)
        output-text (node-text output-card)
        action-bar (node-by-role view-node "portfolio-optimizer-setup-bottom-actions")
        run-button (node-by-role action-bar "portfolio-optimizer-run-draft")
        save-button (node-by-role action-bar "portfolio-optimizer-save-scenario")]
    (is (nil? (node-by-role view-node
                            "portfolio-optimizer-setup-use-my-views-workspace")))
    (is (= ["portfolio-optimizer-objective-panel"
            "portfolio-optimizer-constraints-panel"
            "portfolio-optimizer-proxy-workflow-slot"
            "portfolio-optimizer-return-risk-panel"
            "portfolio-optimizer-advanced-overrides-shell"
            "portfolio-optimizer-views-blend-shell"
            "portfolio-optimizer-why-safe-note"
            "portfolio-optimizer-model-assumptions-stack"
            "portfolio-optimizer-setup-bottom-actions"]
           (child-roles view-node)))
    (is (some? blend-shell))
    (is (= :details (first blend-shell)))
    (is (not (contains? (second blend-shell) :open)))
    (is (= :summary (first (first (node-children blend-shell)))))
    (is (str/includes? shell-text "How your views shape the forecast"))
    (is (str/includes? shell-text "Implied baseline → your views → combined output"))
    (is (some? (node-by-role blend-shell
                             "portfolio-optimizer-setup-use-my-views-context")))
    (is (some? external-legend))
    (is (= ["portfolio-optimizer-setup-use-my-views-legend-market-reference"
            "portfolio-optimizer-setup-use-my-views-legend-your-view"
            "portfolio-optimizer-setup-use-my-views-legend-combined-output"]
           (child-roles external-legend)))
    (is (str/includes? legend-text "Implied baseline"))
    (is (not (str/includes? legend-text "Market reference")))
    (is (str/includes? legend-text "(prior)"))
    (is (str/includes? legend-text "Your view"))
    (is (str/includes? legend-text "Combined output"))
    (is (str/includes? legend-text "(posterior)"))
    (is (some? chart-shell))
    (is (some? (node-by-role chart-shell
                             "portfolio-optimizer-black-litterman-preview-panel")))
    (is (= ["portfolio-optimizer-setup-use-my-views-card-market-reference"
            "portfolio-optimizer-setup-use-my-views-card-your-views"
            "portfolio-optimizer-setup-use-my-views-card-combined-output"]
           (child-roles insight-cards)))
    (is (str/includes? market-text "Market reference"))
    (is (str/includes? market-text "What the model assumes before your views"))
    (is (str/includes? market-text "BTC"))
    (is (str/includes? market-text "20.0%"))
    (is (str/includes? market-text "ETH"))
    (is (str/includes? market-text "30.0%"))
    (is (str/includes? views-text "Your views"))
    (is (= "step" (get-in views-card [1 :aria-current])))
    (is (str/includes? views-text "What you're changing"))
    (is (str/includes? views-text "1 view active"))
    (is (str/includes? views-text "BTC > ETH by"))
    (is (str/includes? views-text "+10%"))
    (is (str/includes? views-text "medium"))
    (is (str/includes? output-text "Combined output"))
    (is (str/includes? output-text "How much your views actually matter"))
    (is (str/includes? output-text "BTC"))
    (is (str/includes? output-text "20.0%"))
    (is (str/includes? output-text "→"))
    (is (str/includes? output-text "(+"))
    (is (some? action-bar))
    (is (some? (node-by-role view-node "portfolio-optimizer-model-assumptions-panel")))
    (is (= 1 (count-nodes view-node #(= "portfolio-optimizer-setup-bottom-actions"
                                        (get-in % [1 :data-role])))))
    (is (= 1 (count-nodes view-node #(= "portfolio-optimizer-run-draft"
                                        (get-in % [1 :data-role])))))
    (is (= false (get-in run-button [1 :disabled])))
    (is (= [[:actions/run-portfolio-optimizer-from-draft]]
           (click-actions run-button)))
    (is (= [[:actions/open-portfolio-optimizer-scenario-save-modal]]
           (click-actions save-button)))))

(deftest setup-black-litterman-insight-cards-render-empty-view-state-test
  (let [view-node (setup-sections/policy-pane
                   {:state {}
                    :highlighted-controls #{}
                    :draft (black-litterman-empty-draft)
                    :readiness (black-litterman-empty-readiness)
                    :running? false
                    :run-triggerable? true
                    :saving-scenario? false
                    :solved-run? false
                    :result-path "/portfolio/optimize/scenarios/draft"})
        insight-cards (node-by-role view-node
                                    "portfolio-optimizer-setup-use-my-views-insight-cards")
        market-text (node-text
                     (node-by-role insight-cards
                                   "portfolio-optimizer-setup-use-my-views-card-market-reference"))
        views-text (node-text
                    (node-by-role insight-cards
                                  "portfolio-optimizer-setup-use-my-views-card-your-views"))
        output-text (node-text
                     (node-by-role insight-cards
                                   "portfolio-optimizer-setup-use-my-views-card-combined-output"))
        output-card (node-by-role insight-cards
                                  "portfolio-optimizer-setup-use-my-views-card-combined-output")]
    (is (str/includes? market-text "20.0%"))
    (is (str/includes? views-text "No views added yet. Add one in the editor below to see how it changes the recommendation."))
    (is (contains? (class-token-set output-card) "opacity-50"))
    (is (str/includes? output-text
                       "Add a view to see the combined output."))))
