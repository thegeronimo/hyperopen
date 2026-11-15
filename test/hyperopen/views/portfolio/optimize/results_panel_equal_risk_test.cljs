(ns hyperopen.views.portfolio.optimize.results-panel-equal-risk-test
  "Equal Risk results-panel behavior: the diverging risk-contribution balance
  chart replaces the frontier chart (and the frontier-density refinement
  card), the Risk/Return context disclosure replaces frontier framing, the
  why-card and confidence rail speak in risk contributions, and everything
  degrades gracefully on persisted pre-redesign payloads. The
  :risk-structure tab coverage lives in
  results-panel-equal-risk-structure-test; shared fixtures in
  results-panel-equal-risk-fixtures."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.results-panel-equal-risk-fixtures
             :refer [approximate-result render]]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [collect-nodes collect-strings data-role-order index-of
                     node-by-role]]))

(deftest results-panel-equal-risk-shows-balance-chart-and-hides-frontier-test
  (let [view-node (render approximate-result)
        strings (set (collect-strings view-node))]
    (testing "no frontier machinery renders for equal-risk"
      (is (nil? (node-by-role view-node "portfolio-optimizer-frontier-panel")))
      (is (nil? (node-by-role view-node "portfolio-optimizer-refinement-card")))
      (is (nil? (node-by-role view-node "portfolio-optimizer-result-confidence-panel")))
      (is (nil? (node-by-role view-node "portfolio-optimizer-result-confidence-quality-panel"))
          "the frontier-quality/selection-stability/stop-reason detail is refinement-specific, not equal-risk's own confidence rail")
      (is (some? (node-by-role view-node "portfolio-optimizer-leverage-impact-slot"))
          "the stable under-chart slot remains mounted when Equal Risk does not clear the leverage gate")
      (is (nil? (node-by-role view-node "portfolio-optimizer-leverage-impact"))
          "the unlevered Equal Risk result leaves its stable slot empty"))
    (testing "the diverging balance chart is the centerpiece"
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-contributions")))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-contribution-chart")))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-contribution-row")))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-contribution-bar")))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-contribution-current"))
          "current contributions render as muted markers when present")
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-contribution-recommended")))
      (is (contains? strings "Risk contribution balance"))
      (is (contains? strings "Approximate"))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-contributions-quality")))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-contributions-rms")))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-contributions-max")))
      (is (contains? strings "50.0% per asset"))
      (is (some #(re-find #"Converged · 7 iterations" %) strings)))
    (testing "the designer-spec chrome renders: KPI strip, tabs, columns, reading row"
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-balance-kpis")))
      (is (contains? strings "Status"))
      (is (contains? strings "Negative contributors"))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-view-tabs")))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-view-tab-contribution")))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-view-tab-risk-return")))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-contribution-target")))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-contribution-deviation")))
      (is (contains? strings "Reading this"))
      (is (contains? strings "Contribution to Total Volatility (%)")))
    (testing "risk/return context is the card's second tab, never a frontier"
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-return-context")))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-return-target")))
      (is (some #(str/includes? % "did not determine the Equal Risk allocation") strings)))
    (testing "the risk/return tab is a real chart (designer spec 2026-07-11)"
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-return-svg")))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-return-zero-line")))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-return-crosshair"))
          "the dashed crosshair drops through the current book's volatility")
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-return-current")))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-return-legend")))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-return-current-box")))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-return-recommended-box")))
      (is (some? (node-by-role view-node "portfolio-optimizer-risk-return-context-box"))))
    (testing "the why-card speaks in risk contributions"
      (is (some? (node-by-role view-node "portfolio-optimizer-equal-risk-context")))
      (is (nil? (node-by-role view-node "portfolio-optimizer-target-context")))
      (is (contains? strings "Why this risk allocation"))
      (is (contains? strings "Largest risk contributor"))
      (is (contains? strings "Limited · 2 binding caps"))
      (is (some #(str/includes? % "Limited by constraints") strings)
          "the confidence rail keeps the long-form freedom label"))
    (testing "the equal-risk confidence rail replaces frontier language"
      (is (some? (node-by-role view-node "portfolio-optimizer-equal-risk-confidence-panel")))
      (is (contains? strings "Equal-risk fit"))
      (is (contains? strings "Allocation freedom"))
      (is (contains? strings "Solution stability"))
      (is (contains? strings "Projected step tolerance reached")))
    (testing "the trust rail reads contributions, not weight-based effective N"
      (is (contains? strings "Negative contributors"))
      (is (not (contains? strings "Diversification"))))))

(deftest results-panel-equal-risk-places-leverage-impact-below-risk-contributions-test
  (let [levered-result (assoc-in approximate-result [:diagnostics :gross-exposure] 2.0)
        view-node (render levered-result)
        order (data-role-order view-node)
        leverage-slot (node-by-role view-node "portfolio-optimizer-leverage-impact-slot")
        contributions-index (index-of order "portfolio-optimizer-risk-contributions")
        leverage-index (index-of order "portfolio-optimizer-leverage-impact")
        context-index (index-of order "portfolio-optimizer-equal-risk-context")]
    (is (some? leverage-slot)
        "the stable Equal Risk slot remains the leverage component's parent")
    (is (some? (node-by-role leverage-slot "portfolio-optimizer-leverage-impact"))
        "2.0x gross exposure clears the gate and renders the modeled panel inside its slot")
    (is (and (some? contributions-index)
             (some? leverage-index)
             (some? context-index)
             (< contributions-index leverage-index context-index))
        "the center-column reading order is balance chart, leverage impact, then Equal Risk context")
    (is (nil? (node-by-role view-node "portfolio-optimizer-frontier-panel")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-refinement-card")))))

(deftest results-panel-equal-risk-places-quality-below-volatility-intuition-test
  (let [view-node (render approximate-result)
        order (data-role-order view-node)
        quality-panel (node-by-role view-node
                                    "portfolio-optimizer-equal-risk-confidence-quality-panel")
        quality-strings (set (collect-strings quality-panel))]
    (is (every? quality-strings
                ["Equal-risk fit"
                 "Allocation freedom"
                 "Solution stability"
                 "Stop reason"])
        "The Equal Risk diagnostic rows render together in their own quality block.")
    (is (and (< (index-of order "portfolio-optimizer-equal-risk-confidence-panel")
               (index-of order "portfolio-optimizer-volatility-risk-cards"))
             (< (index-of order "portfolio-optimizer-equal-risk-next-step")
               (index-of order "portfolio-optimizer-volatility-risk-cards"))
             (< (index-of order "portfolio-optimizer-volatility-risk-cards")
               (index-of order "portfolio-optimizer-equal-risk-confidence-quality-panel")))
        "The Equal Risk lead stays above volatility intuition; its quality block follows it.")))

(deftest results-panel-equal-risk-clamps-off-scale-current-markers-test
  (let [result (assoc approximate-result
                      :current-risk-contributions
                      {:relative-contributions-by-instrument {"perp:BTC" 3.0
                                                              "spot:PURR" 0.1}
                       :rms-error 1.2
                       :max-absolute-error 2.5})
        view-node (render result)
        currents (collect-nodes
                  view-node
                  #(= "portfolio-optimizer-risk-contribution-current"
                      (get-in % [1 :data-role])))]
    (is (= 2 (count currents)))
    (is (= #{"right" nil}
           (set (map #(get-in % [1 :data-offscale]) currents)))
        "the 300% current pins to the right edge; the in-range one stays a circle")))

(deftest results-panel-equal-risk-distinguishes-exact-quality-and-hedges-test
  (let [exact-result (-> approximate-result
                         (assoc :risk-contributions
                                {:method :signed-euler-volatility
                                 :instrument-ids ["perp:BTC" "spot:PURR"]
                                 :relative-contributions [1.1 -0.1]
                                 :target-relative-contributions [0.5 0.5]
                                 :relative-contributions-by-instrument {"perp:BTC" 1.1
                                                                        "spot:PURR" -0.1}
                                 :target-relative-contributions-by-instrument {"perp:BTC" 0.5
                                                                               "spot:PURR" 0.5}
                                 :sum-relative-contributions 1.0
                                 :rms-error 0.0
                                 :max-absolute-error 0.0
                                 :negative-contribution-count 1
                                 :quality :exact}))
        view-node (render exact-result)
        strings (set (collect-strings view-node))]
    (is (contains? strings "Exact"))
    (is (some #(re-find #"hedges the book" %) strings)
        "negative contributions are named, never absolute-valued away")))

(deftest results-panel-equal-risk-exact-fit-shows-shift-columns-test
  (let [exact-result (-> approximate-result
                         (update :risk-contributions merge
                                 {:relative-contributions [0.5 0.5]
                                  :relative-contributions-by-instrument
                                  {"perp:BTC" 0.5 "spot:PURR" 0.5}
                                  :rms-error 0.0
                                  :max-absolute-error 0.0
                                  :negative-contribution-count 0
                                  :quality :exact}))
        view-node (render exact-result)
        strings (set (collect-strings view-node))]
    (testing "the columns tell the move, not the (zero) misfit"
      (is (some? (node-by-role view-node
                               "portfolio-optimizer-risk-contribution-current-cell")))
      (is (some? (node-by-role view-node
                               "portfolio-optimizer-risk-contribution-shift")))
      (is (nil? (node-by-role view-node
                              "portfolio-optimizer-risk-contribution-target")))
      (is (nil? (node-by-role view-node
                              "portfolio-optimizer-risk-contribution-deviation")))
      (is (every? strings ["Current" "Shift"]))
      (is (contains? strings "-40.0 pts")
          "BTC sheds 40 pts of risk share (90% current → 50% target)")
      (is (contains? strings "+40.0 pts")
          "PURR gains 40 pts of risk share (10% current → 50% target)"))
    (testing "the KPI strip swaps dead zeros for the before/after story"
      (is (nil? (node-by-role view-node
                              "portfolio-optimizer-risk-contributions-rms")))
      (is (nil? (node-by-role view-node
                              "portfolio-optimizer-risk-contributions-max")))
      (is (some? (node-by-role view-node
                               "portfolio-optimizer-risk-contributions-imbalance")))
      (is (some? (node-by-role view-node
                               "portfolio-optimizer-risk-contributions-biggest-shift")))
      (is (contains? strings "Current imbalance"))
      (is (contains? strings "40.0 → 0.0 pts")
          "current RMS imbalance 40 pts flattens to 0")
      (is (contains? strings "Biggest shift")))
    (testing "uniform per-row target ticks vanish; the dashed line remains the target"
      (is (nil? (node-by-role view-node
                              "portfolio-optimizer-risk-contribution-target-tick"))))
    (testing "rows order by current share descending (risk drains top to bottom)"
      (is (= ["perp:BTC" "spot:PURR"]
             (->> (collect-nodes
                   view-node
                   #(= "portfolio-optimizer-risk-contribution-row"
                       (get-in % [1 :data-role])))
                  (mapv #(get-in % [1 :data-instrument-id]))))))
    (testing "the approximate fixture keeps the deviation columns and ticks"
      (let [approx-view (render approximate-result)]
        (is (some? (node-by-role approx-view
                                 "portfolio-optimizer-risk-contribution-target")))
        (is (some? (node-by-role approx-view
                                 "portfolio-optimizer-risk-contribution-deviation")))
        (is (nil? (node-by-role approx-view
                                "portfolio-optimizer-risk-contribution-current-cell")))
        (is (nil? (node-by-role approx-view
                                "portfolio-optimizer-risk-contribution-target-tick"))
            "uniform ticks are suppressed in deviation mode too — the dashed line carries the target")))))

(deftest results-panel-equal-risk-degrades-on-persisted-pre-redesign-results-test
  (let [old-result (-> approximate-result
                       (dissoc :current-risk-contributions)
                       (assoc :equal-risk-solver
                              {:strategy :sequential-equal-risk
                               :converged? true
                               :termination-reason :step-tolerance
                               :iterations 7}))
        view-node (render old-result)
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-risk-contributions")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-risk-contribution-current"))
        "no fabricated current markers without current contributions")
    (is (some #(str/includes? % "Not recorded on this result") strings)
        "allocation freedom degrades honestly")
    (is (contains? strings "No initialization record on this result"))))
