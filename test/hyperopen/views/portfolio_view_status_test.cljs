(ns hyperopen.views.portfolio-view-status-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.chart.d3.hover-state :as chart-hover-state]
            [hyperopen.views.portfolio.test-support :refer [collect-strings
                                                            find-first-node]]
            [hyperopen.views.portfolio.vm :as portfolio-vm]
            [hyperopen.views.portfolio-view :as portfolio-view]))

(use-fixtures :each
  (fn [f]
    (chart-hover-state/clear-hover-state!)
    (f)
    (chart-hover-state/clear-hover-state!)))

(deftest portfolio-view-renders-background-status-banner-when-pending-work-exists-test
  (with-redefs [portfolio-vm/portfolio-vm (fn [_]
                                             {:volume-14d-usd 0
                                              :fees {:taker 0 :maker 0}
                                              :background-status {:visible? true
                                                                  :title "Portfolio analytics are still syncing"
                                                                  :detail "The chart is ready. The remaining analytics will fill in automatically."
                                                                  :items [{:id :benchmark-history
                                                                           :label "Benchmark history"}
                                                                          {:id :performance-metrics
                                                                           :label "Performance metrics"}]}
                                              :chart {:selected-tab :returns
                                                      :axis-kind :percent
                                                      :tabs [{:value :returns :label "Returns"}
                                                             {:value :account-value :label "Account Value"}
                                                             {:value :pnl :label "PNL"}]
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
                                              :performance-metrics {:loading? false
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
          banner-node (find-first-node view-node #(= "portfolio-background-status" (get-in % [1 :data-role])))
          benchmark-item (find-first-node view-node #(= "portfolio-background-status-item-benchmark-history" (get-in % [1 :data-role])))
          metrics-item (find-first-node view-node #(= "portfolio-background-status-item-performance-metrics" (get-in % [1 :data-role])))
          banner-strings (set (collect-strings banner-node))]
      (is (some? banner-node))
      (is (contains? banner-strings "Portfolio analytics are still syncing"))
      (is (contains? banner-strings "The chart is ready. The remaining analytics will fill in automatically."))
      (is (= "Benchmark history" (first (collect-strings benchmark-item))))
      (is (= "Performance metrics" (first (collect-strings metrics-item)))))))
