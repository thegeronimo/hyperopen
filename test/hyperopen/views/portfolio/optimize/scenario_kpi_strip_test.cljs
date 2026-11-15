(ns hyperopen.views.portfolio.optimize.scenario-kpi-strip-test
  "Objective-aware KPI strip: Equal Risk swaps the Sharpe tile for the Risk
  balance tile (max contribution deviation, current → recommended) and keeps
  vol/return deltas neutral; every other objective keeps the original tiles."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.portfolio.optimize.scenario-kpi-strip :as kpi-strip]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [collect-strings node-by-role solved-result]]))

(def ^:private equal-risk-result
  (-> solved-result
      (assoc :solver {:strategy :sequential-equal-risk
                      :objective-kind :equal-risk})
      (assoc :risk-contributions
             {:instrument-ids ["perp:BTC" "spot:PURR"]
              :relative-contributions [0.62 0.38]
              :target-relative-contributions [0.5 0.5]
              :rms-error 0.05
              :max-absolute-error 0.12
              :negative-contribution-count 0
              :quality :approximate})
      (assoc :current-risk-contributions
             {:rms-error 0.2
              :max-absolute-error 0.4})))

(deftest kpi-strip-swaps-sharpe-for-risk-balance-on-equal-risk-test
  (let [strip (kpi-strip/kpi-strip equal-risk-result)
        strings (set (collect-strings strip))]
    (is (some? (node-by-role strip "portfolio-optimizer-scenario-kpi-risk-balance")))
    (is (nil? (node-by-role strip "portfolio-optimizer-scenario-kpi-sharpe")))
    (is (contains? strings "Risk balance · current → target"))
    (is (contains? strings "40.0 pts"))
    (is (contains? strings "12.0 pts"))
    (is (some #(re-find #"rms 5.0 pts" %) strings))
    (testing "volatility/return deltas render neutrally for a balance objective"
      (let [vol-card (node-by-role strip "portfolio-optimizer-scenario-kpi-volatility")
            delta-node (last vol-card)]
        (is (some #(= "text-trading-muted" %) (get-in delta-node [1 :class])))))))

(deftest kpi-strip-keeps-sharpe-tile-for-frontier-objectives-test
  (let [strip (kpi-strip/kpi-strip solved-result)]
    (is (some? (node-by-role strip "portfolio-optimizer-scenario-kpi-sharpe")))
    (is (nil? (node-by-role strip "portfolio-optimizer-scenario-kpi-risk-balance")))))

(deftest recommendation-deltas-shape-is-stable-test
  (let [deltas (kpi-strip/recommendation-deltas solved-result)]
    (is (contains? deltas :at-target?))
    (is (contains? deltas :trade-count))
    (is (number? (:target-vol deltas)))))
