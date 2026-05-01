(ns hyperopen.views.portfolio-view-performance-metrics-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.chart.d3.hover-state :as chart-hover-state]
            [hyperopen.views.portfolio.test-support :refer [class-values
                                                            collect-strings
                                                            find-first-node]]
            [hyperopen.views.portfolio.vm :as portfolio-vm]
            [hyperopen.views.portfolio-view :as portfolio-view]))

(use-fixtures :each
  (fn [f]
    (chart-hover-state/clear-hover-state!)
    (f)
    (chart-hover-state/clear-hover-state!)))

(deftest portfolio-view-performance-metrics-renders-formatting-and-fallbacks-test
  (with-redefs [portfolio-vm/portfolio-vm (fn [_]
                                             {:volume-14d-usd 0
                                              :fees {:taker 0 :maker 0}
                                              :chart {:selected-tab :pnl
                                                      :axis-kind :number
                                                      :tabs [{:value :account-value :label "Account Value"}
                                                             {:value :pnl :label "PNL"}
                                                             {:value :returns :label "Returns"}]
                                                      :points [{:time-ms 1 :value 0 :x-ratio 0 :y-ratio 1}
                                                               {:time-ms 2 :value 10 :x-ratio 1 :y-ratio 0}]
                                                      :path "M 0 100 L 100 0"
                                                      :series [{:id :strategy
                                                                :label "Portfolio"
                                                                :stroke "#f5f7f8"
                                                                :has-data? true
                                                                :points [{:time-ms 1 :value 0 :x-ratio 0 :y-ratio 1}
                                                                         {:time-ms 2 :value 10 :x-ratio 1 :y-ratio 0}]
                                                                :path "M 0 100 L 100 0"}]
                                                      :y-ticks [{:value 10 :y-ratio 0}
                                                                {:value 6 :y-ratio (/ 1 3)}
                                                                {:value 3 :y-ratio (/ 2 3)}
                                                                {:value 0 :y-ratio 1}]
                                                      :has-data? true}
                                              :selectors {:summary-scope {:value :all
                                                                          :label "Perps + Spot + Vaults"
                                                                          :open? false
                                                                          :options [{:value :all :label "Perps + Spot + Vaults"}]}
                                                          :summary-time-range {:value :month
                                                                               :label "30D"
                                                                               :open? false
                                                                               :options [{:value :month :label "30D"}]}
                                                          :returns-benchmark {:selected-coins []
                                                                              :selected-options []
                                                                              :coin-search ""
                                                                              :suggestions-open? false
                                                                              :candidates []
                                                                              :top-coin nil
                                                                              :empty-message nil
                                                                              :label-by-coin {}}}
                                              :summary {:selected-key :month
                                                        :pnl 0
                                                        :volume 0
                                                        :max-drawdown-pct nil
                                                        :total-equity 0
                                                        :show-perps-account-equity? false
                                                        :perps-account-equity 0
                                                        :spot-equity-label "Spot Account Equity"
                                                        :spot-account-equity 0
                                                        :show-vault-equity? false
                                                        :vault-equity 0
                                                        :show-earn-balance? false
                                                        :earn-balance 0
                                                        :show-staking-account? false
                                                        :staking-account-hype 0}
                                              :performance-metrics {:benchmark-selected? true
                                                                    :benchmark-coin "SPY"
                                                                    :benchmark-label "SPY (SPOT)"
                                                                    :benchmark-columns [{:coin "SPY"
                                                                                         :label "SPY (SPOT)"}
                                                                                        {:coin "QQQ"
                                                                                         :label "QQQ (SPOT)"}]
                                                                    :values {}
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
                                                                                      :value nil}]}]}})
                account-info-view/account-info-view (fn
                                                      ([_]
                                                       [:div {:data-role "stub-account-info"}])
                                                      ([_ {:keys [extra-tabs]}]
                                                       (or (some (fn [{:keys [id content render]}]
                                                                   (when (= id :performance-metrics)
                                                                     (or content
                                                                         (when (fn? render)
                                                                           (render nil)))))
                                                                 extra-tabs)
                                                           [:div {:data-role "stub-account-info"}])))]
    (let [view-node (portfolio-view/portfolio-view {})
          all-text (set (collect-strings view-node))
          benchmark-label (find-first-node view-node #(= "portfolio-performance-metrics-benchmark-label" (get-in % [1 :data-role])))
          benchmark-label-qqq (find-first-node view-node #(= "portfolio-performance-metrics-benchmark-label-QQQ" (get-in % [1 :data-role])))
          estimated-banner (find-first-node view-node #(= "portfolio-performance-metrics-estimated-banner"
                                                          (get-in % [1 :data-role])))
          estimated-banner-tooltip (find-first-node view-node #(= "portfolio-performance-metrics-estimated-banner-tooltip"
                                                                  (get-in % [1 :data-role])))
          daily-var-label-tooltip (find-first-node view-node #(= "portfolio-performance-metric-daily-var-label-tooltip"
                                                                 (get-in % [1 :data-role])))
          estimated-mark (find-first-node view-node #(= "portfolio-performance-metric-daily-var-estimated-mark"
                                                        (get-in % [1 :data-role])))
          portfolio-low-confidence-cell (find-first-node view-node #(= "portfolio-performance-metric-daily-var-portfolio-value"
                                                                       (get-in % [1 :data-role])))
          benchmark-low-confidence-cell (find-first-node view-node #(= "portfolio-performance-metric-daily-var-benchmark-value-SPY"
                                                                       (get-in % [1 :data-role])))
          badge-node (find-first-node view-node #(= "portfolio-performance-metric-daily-var-portfolio-value-status-badge"
                                                    (get-in % [1 :data-role])))
          nil-row (find-first-node view-node #(= "portfolio-performance-metric-r2" (get-in % [1 :data-role])))]
      (is (contains? all-text "Metric"))
      (is (contains? all-text "Portfolio"))
      (is (= "SPY (SPOT)" (first (collect-strings benchmark-label))))
      (is (= "QQQ (SPOT)" (first (collect-strings benchmark-label-qqq))))
      (is (contains? all-text "+12.30%"))
      (is (contains? all-text "+11.10%"))
      (is (contains? all-text "+10.10%"))
      (is (contains? all-text "-4.50%"))
      (is (contains? all-text "-3.30%"))
      (is (contains? all-text "-2.20%"))
      (is (contains? all-text "1.23"))
      (is (contains? all-text "2024-01-02"))
      (is (contains? all-text "7"))
      (is (some? estimated-banner))
      (is (contains? (set (collect-strings estimated-banner))
                     "Some metrics are estimated from incomplete daily data."))
      (is (contains? (set (collect-strings estimated-banner-tooltip))
                     "Estimated rows stay visible when the selected range does not meet the usual reliability gates."))
      (is (contains? (set (collect-strings estimated-banner-tooltip))
                     "Estimated from incomplete daily coverage."))
      (is (contains? (set (collect-strings daily-var-label-tooltip))
                     "Daily Value-at-Risk"))
      (is (contains? (set (collect-strings daily-var-label-tooltip))
                     "Expected one-day loss threshold at the configured confidence level."))
      (is (= "~" (first (collect-strings estimated-mark))))
      (is (contains? (set (class-values portfolio-low-confidence-cell))
                     "text-trading-text-secondary"))
      (is (contains? (set (class-values benchmark-low-confidence-cell))
                     "text-trading-text-secondary"))
      (is (nil? badge-node))
      (is (nil? nil-row)))))

(deftest portfolio-view-performance-metrics-loading-overlay-renders-explainer-copy-test
  (with-redefs [portfolio-vm/portfolio-vm (fn [_]
                                             {:volume-14d-usd 0
                                              :fees {:taker 0 :maker 0}
                                              :background-status {:visible? false
                                                                  :items []}
                                              :chart {:selected-tab :pnl
                                                      :axis-kind :number
                                                      :tabs [{:value :account-value :label "Account Value"}
                                                             {:value :pnl :label "PNL"}
                                                             {:value :returns :label "Returns"}]
                                                      :points [{:time-ms 1 :value 0 :x-ratio 0 :y-ratio 1}
                                                               {:time-ms 2 :value 10 :x-ratio 1 :y-ratio 0}]
                                                      :path "M 0 100 L 100 0"
                                                      :series [{:id :strategy
                                                                :label "Portfolio"
                                                                :stroke "#f5f7f8"
                                                                :has-data? true
                                                                :points [{:time-ms 1 :value 0 :x-ratio 0 :y-ratio 1}
                                                                         {:time-ms 2 :value 10 :x-ratio 1 :y-ratio 0}]
                                                                :path "M 0 100 L 100 0"}]
                                                      :y-ticks [{:value 10 :y-ratio 0}
                                                                {:value 6 :y-ratio (/ 1 3)}
                                                                {:value 3 :y-ratio (/ 2 3)}
                                                                {:value 0 :y-ratio 1}]
                                                      :has-data? true}
                                              :selectors {:summary-scope {:value :all
                                                                          :label "Perps + Spot + Vaults"
                                                                          :open? false
                                                                          :options [{:value :all :label "Perps + Spot + Vaults"}]}
                                                          :summary-time-range {:value :month
                                                                               :label "30D"
                                                                               :open? false
                                                                               :options [{:value :month :label "30D"}]}
                                                          :performance-metrics-time-range {:value :month
                                                                                           :label "30D"
                                                                                           :open? false
                                                                                           :options [{:value :month :label "30D"}]}
                                                          :returns-benchmark {:selected-coins []
                                                                              :selected-options []
                                                                              :coin-search ""
                                                                              :suggestions-open? false
                                                                              :candidates []
                                                                              :top-coin nil
                                                                              :empty-message nil
                                                                              :label-by-coin {}}}
                                              :summary {:selected-key :month
                                                        :pnl 0
                                                        :volume 0
                                                        :max-drawdown-pct nil
                                                        :total-equity 0
                                                        :show-perps-account-equity? false
                                                        :perps-account-equity 0
                                                        :spot-equity-label "Spot Account Equity"
                                                        :spot-account-equity 0
                                                        :show-vault-equity? false
                                                        :vault-equity 0
                                                        :show-earn-balance? false
                                                        :earn-balance 0
                                                        :show-staking-account? false
                                                        :staking-account-hype 0}
                                              :performance-metrics {:loading? true
                                                                    :benchmark-selected? false
                                                                    :benchmark-columns []
                                                                    :values {}
                                                                    :groups []}})
                account-info-view/account-info-view (fn
                                                      ([_]
                                                       [:div {:data-role "stub-account-info"}])
                                                      ([_ {:keys [extra-tabs]}]
                                                       (or (some (fn [{:keys [id content render]}]
                                                                   (when (= id :performance-metrics)
                                                                     (or content
                                                                         (when (fn? render)
                                                                           (render nil)))))
                                                                 extra-tabs)
                                                           [:div {:data-role "stub-account-info"}])))]
    (let [view-node (portfolio-view/portfolio-view {})
          overlay-node (find-first-node view-node #(= "portfolio-performance-metrics-loading-overlay" (get-in % [1 :data-role])))
          overlay-strings (set (collect-strings overlay-node))]
      (is (some? overlay-node))
      (is (= "status" (get-in overlay-node [1 :role])))
      (is (contains? overlay-strings "Calculating performance metrics"))
      (is (contains? overlay-strings "Returns stay visible while the remaining analytics finish in the background.")))))
