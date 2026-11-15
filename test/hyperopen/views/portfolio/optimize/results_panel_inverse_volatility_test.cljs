(ns hyperopen.views.portfolio.optimize.results-panel-inverse-volatility-test
  "Results-surface coverage for the Risk-weighted sizing (:inverse-volatility)
  objective (ExecPlan optimizer-inverse-volatility-objective, items 9 and 10):
  the sizing-fidelity card replaces the frontier chart and the equal-risk
  rails, the KPI strip goes neutral with no Exact/Approximate quality label,
  and an Equal Risk result with a floored side-locked asset renders the
  one-click switch-to-Risk-weighted-sizing suggestion."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.portfolio.optimize.results-panel :as results-panel]
            [hyperopen.views.portfolio.optimize.risk-contributions-card
             :as risk-contributions-card]
            [hyperopen.views.portfolio.optimize.scenario-kpi-strip :as kpi-strip]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [collect-strings node-by-role solved-result]]))

(def ^:private inverse-volatility-result
  (-> solved-result
      (assoc :solver {:strategy :inverse-volatility
                      :objective-kind :inverse-volatility})
      (assoc :inverse-volatility
             {:sizing-rows [{:instrument-id "perp:BTC"
                             :weight 0.35
                             :sigma 0.4
                             :risk-weight 0.14
                             :moved-off-seed? false}
                            {:instrument-id "spot:PURR"
                             :weight -0.2
                             :sigma 0.7
                             :risk-weight 0.14
                             :moved-off-seed? false}]
              :seed-weights [0.35 -0.2]
              :max-sizing-deviation 0.0})
      (assoc :risk-contributions
             {:method :signed-euler-volatility
              :instrument-ids ["perp:BTC" "spot:PURR"]
              :relative-contributions [1.12 -0.12]
              :target-relative-contributions [0.5 0.5]
              :relative-contributions-by-instrument {"perp:BTC" 1.12
                                                     "spot:PURR" -0.12}
              :target-relative-contributions-by-instrument {"perp:BTC" 0.5
                                                            "spot:PURR" 0.5}
              :sum-relative-contributions 1.0
              :rms-error 0.62
              :max-absolute-error 0.62
              :negative-contribution-count 1
              :quality :diagnostic})
      (assoc :current-risk-contributions
             {:relative-contributions-by-instrument {"perp:BTC" 0.9
                                                     "spot:PURR" 0.1}
              :rms-error 0.4
              :max-absolute-error 0.4})
      (assoc :risk-structure
             {:method :signed-euler-decomposition
              :portfolio-volatility 0.42
              :current-diversification {:modeled-volatility 0.24
                                        :all-move-together-volatility 0.32
                                        :zero-correlation-volatility 0.20
                                        :reduction-vs-all-move-together 0.08
                                        :reduction-ratio-vs-all-move-together 0.25
                                        :modeled-minus-zero-correlation 0.04}
              :target-diversification {:modeled-volatility 0.42
                                       :all-move-together-volatility 0.60
                                       :zero-correlation-volatility 0.35
                                       :reduction-vs-all-move-together 0.18
                                       :reduction-ratio-vs-all-move-together 0.3
                                       :modeled-minus-zero-correlation 0.07}
              :standalone-share-by-instrument {"perp:BTC" 0.75
                                               "spot:PURR" 0.5}
              :diversification-share-by-instrument {"perp:BTC" 0.37
                                                    "spot:PURR" -0.62}
              :pnl-portfolio-correlation-by-instrument {"perp:BTC" 0.9
                                                        "spot:PURR" -0.55}
              :correlation {:instrument-ids ["perp:BTC" "spot:PURR"]
                            :matrix [[1.0 0.6]
                                     [0.6 1.0]]
                            :hidden-count 0}})))

(defn- render
  [result]
  (results-panel/results-panel
   {:result result
    :computed-at-ms 2600}
   {:objective {:kind :inverse-volatility}}
   {:frontier-overlay-mode :standalone}))

(deftest results-panel-inverse-volatility-shows-sizing-card-and-hides-frontier-test
  (let [view-node (render inverse-volatility-result)
        strings (set (collect-strings view-node))]
    (testing "the sizing-fidelity card is the centerpiece"
      (is (some? (node-by-role view-node
                               "portfolio-optimizer-risk-weighted-sizing-card"))
          "an :inverse-volatility solved payload renders the sizing card"))
    (testing "no frontier machinery renders — this objective is not selected
              from a frontier and must not be plotted as if it were"
      (is (nil? (node-by-role view-node "portfolio-optimizer-frontier-panel")))
      (is (nil? (node-by-role view-node "portfolio-optimizer-refinement-card")))
      (is (nil? (node-by-role view-node
                              "portfolio-optimizer-result-confidence-panel"))))
    (testing "the equal-risk rails stay out — this is not an equal-risk result"
      (is (nil? (node-by-role view-node "portfolio-optimizer-risk-contributions")))
      (is (nil? (node-by-role view-node
                              "portfolio-optimizer-equal-risk-confidence-panel")))
      (is (nil? (node-by-role view-node
                              "portfolio-optimizer-equal-risk-context"))))
    (testing "no Exact/Approximate balance-quality label leaks onto this objective"
      (is (not (contains? strings "Exact")))
      (is (not (contains? strings "Approximate"))))))

(deftest results-panel-inverse-volatility-leverage-distribution-when-gated-test
  (let [gated-result (-> inverse-volatility-result
                         (assoc :volatility 1.2
                                :expected-return 0.25))
        view-node (render gated-result)]
    (is (some? (node-by-role view-node
                             "portfolio-optimizer-leverage-impact-distribution"))
        "a gate-passing :inverse-volatility result renders the ending-wealth distribution")
    (testing "and stays hidden below the gate — matching every other objective"
      (is (nil? (node-by-role (render inverse-volatility-result)
                              "portfolio-optimizer-leverage-impact-distribution"))))))

(deftest kpi-strip-inverse-volatility-neutral-deltas-and-no-quality-label-test
  (let [strip (kpi-strip/kpi-strip inverse-volatility-result)
        strings (set (collect-strings strip))]
    (testing "vol/return direction is not success or failure for a sizing objective"
      (let [vol-card (node-by-role strip
                                   "portfolio-optimizer-scenario-kpi-volatility")
            delta-node (last vol-card)]
        (is (some #(= "text-trading-muted" %) (get-in delta-node [1 :class]))
            "the volatility delta renders neutrally")))
    (testing "the Sharpe tile is not this objective's success metric"
      (is (nil? (node-by-role strip "portfolio-optimizer-scenario-kpi-sharpe"))))
    (testing "no Exact/Approximate quality label rides the strip"
      (is (not (contains? strings "Exact")))
      (is (not (contains? strings "Approximate"))))))

(deftest sizing-card-analytics-tabs-render-as-target-free-diagnostics-test
  (let [view-node (render inverse-volatility-result)
        strings (set (collect-strings view-node))]
    (testing "every tab label rides the sizing card header"
      (doseq [view ["sizing" "contributions" "breakdown" "correlation"
                    "risk-return"]]
        (is (some? (node-by-role view-node
                                 (str "portfolio-optimizer-sizing-view-tab-"
                                      view)))
            (str "expected the " view " tab")))
      (is (contains? strings "Sizing Fidelity"))
      (is (contains? strings "Contributions"))
      (is (contains? strings "Diversification"))
      (is (contains? strings "Correlation Drivers"))
      (is (contains? strings "Risk / Return")))
    (testing "the contributions tab body renders the diagnostic chart"
      (is (some? (node-by-role view-node
                               "portfolio-optimizer-sizing-contributions-chart")))
      (is (some? (node-by-role view-node
                               "portfolio-optimizer-sizing-contribution-row"))))
    (testing "no equal-target chrome leaks into the diagnostic tab"
      (is (nil? (node-by-role view-node
                              "portfolio-optimizer-risk-contribution-target-tick"))
          "no per-row target tick anywhere on the surface")
      (is (nil? (node-by-role view-node
                              "portfolio-optimizer-risk-contribution-target"))
          "no Target column cell anywhere on the surface")
      (is (nil? (node-by-role view-node
                              "portfolio-optimizer-risk-contribution-deviation"))
          "no Deviation column cell anywhere on the surface"))
    (testing "the risk/return tab names this objective, not Equal Risk"
      (is (contains? strings "Recommended (Risk-weighted sizing)"))
      (is (not (contains? strings "Recommended (Equal Risk)"))))
    (testing "the volatility-intuition rail context renders for this objective"
      (is (some? (node-by-role view-node
                              "portfolio-optimizer-volatility-intuition"))))))

(def ^:private floored-equal-risk-result
  (-> solved-result
      (assoc :solver {:strategy :sequential-equal-risk
                      :objective-kind :equal-risk})
      (assoc :target-weights [0.35 0])
      (assoc :target-weights-by-instrument {"perp:BTC" 0.35
                                            "spot:PURR" 0})
      (assoc :risk-contributions
             {:method :signed-euler-volatility
              :instrument-ids ["perp:BTC" "spot:PURR"]
              :relative-contributions [1.0 0.0]
              :target-relative-contributions [0.5 0.5]
              :relative-contributions-by-instrument {"perp:BTC" 1.0
                                                     "spot:PURR" 0.0}
              :target-relative-contributions-by-instrument {"perp:BTC" 0.5
                                                            "spot:PURR" 0.5}
              :sum-relative-contributions 1.0
              :rms-error 0.5
              :max-absolute-error 0.5
              :negative-contribution-count 0
              :quality :approximate})
      (assoc-in [:diagnostics :binding-constraints]
                [{:instrument-id "spot:PURR"
                  :constraint :lower-bound
                  :bound 0}])))

(deftest risk-contributions-card-offers-risk-weighted-sizing-for-floored-assets-test
  (let [card (risk-contributions-card/risk-contributions-card
              floored-equal-risk-result)
        suggestion (node-by-role
                    card
                    "portfolio-optimizer-switch-to-risk-weighted-sizing")]
    (is (some? suggestion)
        "a floored side-locked asset surfaces the Risk-weighted sizing suggestion")
    (is (= :actions/switch-portfolio-optimizer-objective-and-run
           (first (first (get-in suggestion [1 :on :click]))))
        "one click switches the objective and reruns")))

(deftest risk-contributions-card-hides-the-suggestion-without-floored-assets-test
  (let [balanced (-> floored-equal-risk-result
                     (assoc :target-weights [0.35 -0.2])
                     (assoc :target-weights-by-instrument {"perp:BTC" 0.35
                                                           "spot:PURR" -0.2})
                     (assoc-in [:diagnostics :binding-constraints] []))
        card (risk-contributions-card/risk-contributions-card balanced)]
    (is (nil? (node-by-role
               card
               "portfolio-optimizer-switch-to-risk-weighted-sizing"))
        "no floored asset, no cross-link nudge")))
