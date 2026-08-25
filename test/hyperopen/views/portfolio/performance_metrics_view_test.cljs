(ns hyperopen.views.portfolio.performance-metrics-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.portfolio.performance-metrics-view :as performance-metrics-view]))

(def ^:private metrics-card-input
  {:benchmark-selected? true
   :benchmark-coin "SPY"
   :benchmark-label "SPY (SPOT)"
   :benchmark-columns [{:coin "SPY"
                        :label "SPY (SPOT)"}
                       {:coin "QQQ"
                        :label "QQQ (SPOT)"}]
   :groups [{:id :sample
             :rows [{:key :expected-monthly
                     :label "Expected Monthly"
                     :kind :percent
                     :value 0.123
                     :benchmark-values {"SPY" 0.111
                                        "QQQ" 0.101}}
                    {:key :daily-var
                     :label "Daily Value-at-Risk"
                     :description "Expected one-day loss threshold at the configured confidence level."
                     :kind :percent
                     :value -0.045
                     :portfolio-status :low-confidence
                     :portfolio-reason :daily-coverage-gate-failed
                     :benchmark-values {"SPY" -0.033
                                        "QQQ" -0.022}
                     :benchmark-statuses {"SPY" :low-confidence
                                          "QQQ" :ok}
                     :benchmark-reasons {"SPY" :daily-coverage-gate-failed}}
                    {:key :information-ratio
                     :label "Information Ratio"
                     :kind :ratio
                     :value 1.2345}
                    {:key :max-dd-date
                     :label "Max DD Date"
                     :kind :date
                     :value "2024-01-02"}
                    {:key :max-consecutive-wins
                     :label "Max Consecutive Wins"
                     :kind :integer
                     :value 7}
                    {:key :r2
                     :label "R^2"
                     :kind :ratio
                     :value nil}]}]
   :time-range-selector {:value :month
                         :label "30D"
                         :open? true
                         :options [{:value :month :label "30D"}
                                   {:value :day :label "24H"}]}})

(deftest performance-metrics-card-renders-low-confidence-contracts-test
  (let [view (performance-metrics-view/performance-metrics-card metrics-card-input)
        time-range-trigger (hiccup/find-by-data-role view "portfolio-performance-metrics-time-range-selector-trigger")
        time-range-month-option (hiccup/find-by-data-role view "portfolio-performance-metrics-time-range-selector-option-month")
        benchmark-label (hiccup/find-by-data-role view "portfolio-performance-metrics-benchmark-label")
        benchmark-label-qqq (hiccup/find-by-data-role view "portfolio-performance-metrics-benchmark-label-QQQ")
        estimated-banner (hiccup/find-by-data-role view "portfolio-performance-metrics-estimated-banner")
        estimated-banner-tooltip (hiccup/find-by-data-role view "portfolio-performance-metrics-estimated-banner-tooltip")
        daily-var-label-tooltip (hiccup/find-by-data-role view "portfolio-performance-metric-daily-var-label-tooltip")
        estimated-mark (hiccup/find-by-data-role view "portfolio-performance-metric-daily-var-estimated-mark")
        portfolio-low-confidence-cell (hiccup/find-by-data-role view "portfolio-performance-metric-daily-var-portfolio-value")
        benchmark-low-confidence-cell (hiccup/find-by-data-role view "portfolio-performance-metric-daily-var-benchmark-value-SPY")
        nil-row (hiccup/find-by-data-role view "portfolio-performance-metric-r2")
        all-text (set (hiccup/collect-strings view))]
    (is (= [[:actions/toggle-portfolio-performance-metrics-time-range-dropdown]]
           (get-in time-range-trigger [1 :on :click])))
    (is (= [[:actions/select-portfolio-summary-time-range :month]]
           (get-in time-range-month-option [1 :on :click])))
    (is (= "SPY (SPOT)" (first (hiccup/collect-strings benchmark-label))))
    (is (= "QQQ (SPOT)" (first (hiccup/collect-strings benchmark-label-qqq))))
    (is (contains? all-text "Metric"))
    (is (contains? all-text "Portfolio"))
    (is (contains? all-text "+12.30%"))
    (is (contains? all-text "+11.10%"))
    (is (contains? all-text "+10.10%"))
    (is (contains? all-text "-4.50%"))
    (is (contains? all-text "-3.30%"))
    (is (contains? all-text "-2.20%"))
    (is (contains? all-text "1.23"))
    (is (contains? all-text "2024-01-02"))
    (is (contains? all-text "7"))
    (is (contains? (set (hiccup/collect-strings estimated-banner))
                   "Some metrics are estimated from incomplete daily data."))
    (is (contains? (set (hiccup/collect-strings estimated-banner-tooltip))
                   "Estimated rows stay visible when the selected range does not meet the usual reliability gates."))
    (is (contains? (set (hiccup/collect-strings estimated-banner-tooltip))
                   "Estimated from incomplete daily coverage."))
    (is (contains? (set (hiccup/collect-strings daily-var-label-tooltip))
                   "Daily Value-at-Risk"))
    (is (contains? (set (hiccup/collect-strings daily-var-label-tooltip))
                   "Expected one-day loss threshold at the configured confidence level."))
    (is (= "~" (first (hiccup/collect-strings estimated-mark))))
    (is (contains? (hiccup/node-class-set portfolio-low-confidence-cell)
                   "text-trading-text-secondary"))
    (is (contains? (hiccup/node-class-set benchmark-low-confidence-cell)
                   "text-trading-text-secondary"))
    (is (nil? nil-row))))

(deftest performance-metrics-card-renders-loading-overlay-copy-test
  (let [view (performance-metrics-view/performance-metrics-card
              {:loading? true
               :benchmark-selected? false
               :groups []
               :time-range-selector {:value :month
                                     :label "30D"
                                     :open? false
                                     :options [{:value :month :label "30D"}]}})
        overlay (hiccup/find-by-data-role view "portfolio-performance-metrics-loading-overlay")
        overlay-text (set (hiccup/collect-strings overlay))]
    (is (some? overlay))
    (is (= "status" (get-in overlay [1 :role])))
    (is (= "polite" (get-in overlay [1 :aria-live])))
    (is (contains? overlay-text "Calculating performance metrics"))
    (is (contains? overlay-text "Returns stay visible while the remaining analytics finish in the background."))))

(deftest performance-metrics-card-labels-stale-numbers-instead-of-blanking-them-test
  ;; During a timeframe switch the previous window's numbers stay on screen —
  ;; blanking them is the content churn this work removed. The chip is what
  ;; keeps that honest: without it the old Sharpe sits under the new range label
  ;; with nothing saying which window it belongs to.
  (let [render (fn [stale?]
                 (performance-metrics-view/performance-metrics-card
                  {:loading? false
                   :stale? stale?
                   :benchmark-selected? false
                   :groups []
                   :time-range-selector {:value :two-year
                                         :label "2Y"
                                         :open? false
                                         :options [{:value :two-year :label "2Y"}]}}))
        stale-badge (fn [view]
                      (hiccup/find-by-data-role view "portfolio-performance-metrics-stale-badge"))
        hidden? (fn [node] (contains? (set (get-in node [1 :class])) "invisible"))]
    (is (= "Updating…" (first (hiccup/collect-strings (stale-badge (render true))))))
    (is (not (hidden? (stale-badge (render true))))
        "the badge is visible while the numbers belong to another window")
    ;; Always rendered, never a nil hole: toggling presence in a flex row would
    ;; reflow the header, which is exactly the class of movement being removed.
    (is (some? (stale-badge (render false))))
    (is (hidden? (stale-badge (render false)))
        "and hidden -- but still occupying its space, so toggling it cannot reflow the header row -- once the numbers match the window on screen")
    (is (nil? (hiccup/find-by-data-role (render true)
                                        "portfolio-performance-metrics-loading-overlay"))
        "a stale recompute must not raise the blocking overlay")))
