(ns hyperopen.portfolio.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.actions :as actions]))

(def ^:private replace-shareable-route-query-effect
  [:effects/replace-shareable-route-query])

(deftest toggle-portfolio-summary-scope-dropdown-opens-and-closes-test
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope-dropdown-open?] true]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]]
         (actions/toggle-portfolio-summary-scope-dropdown
          {:portfolio-ui {:summary-scope-dropdown-open? false
                          :summary-time-range-dropdown-open? true
                          :performance-metrics-time-range-dropdown-open? true}})))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]]
         (actions/toggle-portfolio-summary-scope-dropdown
          {:portfolio-ui {:summary-scope-dropdown-open? true
                          :summary-time-range-dropdown-open? false
                          :performance-metrics-time-range-dropdown-open? false}}))))

(deftest toggle-portfolio-summary-time-range-dropdown-opens-and-closes-test
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] true]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]]
         (actions/toggle-portfolio-summary-time-range-dropdown
          {:portfolio-ui {:summary-scope-dropdown-open? true
                          :summary-time-range-dropdown-open? false
                          :performance-metrics-time-range-dropdown-open? true}})))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]]
         (actions/toggle-portfolio-summary-time-range-dropdown
          {:portfolio-ui {:summary-scope-dropdown-open? false
                          :summary-time-range-dropdown-open? true
                          :performance-metrics-time-range-dropdown-open? false}}))))

(deftest toggle-portfolio-performance-metrics-time-range-dropdown-opens-and-closes-test
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] true]]]]
         (actions/toggle-portfolio-performance-metrics-time-range-dropdown
          {:portfolio-ui {:summary-scope-dropdown-open? true
                          :summary-time-range-dropdown-open? true
                          :performance-metrics-time-range-dropdown-open? false}})))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]]
         (actions/toggle-portfolio-performance-metrics-time-range-dropdown
          {:portfolio-ui {:summary-scope-dropdown-open? false
                          :summary-time-range-dropdown-open? false
                          :performance-metrics-time-range-dropdown-open? true}}))))

(deftest portfolio-volume-history-popover-actions-open-close-and-escape-test
  (let [anchor {:left "100"
                :right 160
                :top "240.5"
                :bottom 264
                :width 60
                :height "24"
                :viewport-width 800
                :viewport-height "700"}
        normalized-anchor {:left 100
                           :right 160
                           :top 240.5
                           :bottom 264
                           :width 60
                           :height 24
                           :viewport-width 800
                           :viewport-height 700}]
    (is (= [[:effects/save-many [[[:portfolio-ui :volume-history-open?] true]
                                 [[:portfolio-ui :volume-history-anchor] normalized-anchor]
                                 [[:portfolio-ui :summary-scope-dropdown-open?] false]
                                 [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                                 [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]]
           (actions/open-portfolio-volume-history
            {:portfolio-ui {:summary-scope-dropdown-open? true
                            :summary-time-range-dropdown-open? true
                            :performance-metrics-time-range-dropdown-open? true}}
            anchor))))
  (is (= [[:effects/save-many [[[:portfolio-ui :volume-history-open?] false]
                               [[:portfolio-ui :volume-history-anchor] nil]]]]
         (actions/close-portfolio-volume-history {})))
  (is (= [[:effects/save-many [[[:portfolio-ui :volume-history-open?] false]
                               [[:portfolio-ui :volume-history-anchor] nil]]]]
         (actions/handle-portfolio-volume-history-keydown {} "Escape")))
  (is (= []
         (actions/handle-portfolio-volume-history-keydown {} "Enter"))))

(deftest portfolio-fee-schedule-actions-batch-modal-and-selector-state-test
  (is (= [[:effects/save-many [[[:portfolio-ui :fee-schedule-open?] true]
                               [[:portfolio-ui :fee-schedule-anchor] nil]
                               [[:portfolio-ui :fee-schedule-referral-discount] nil]
                               [[:portfolio-ui :fee-schedule-staking-tier] nil]
                               [[:portfolio-ui :fee-schedule-maker-rebate-tier] nil]
                               [[:portfolio-ui :fee-schedule-referral-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-staking-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-maker-rebate-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-market-dropdown-open?] false]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]]
         (actions/open-portfolio-fee-schedule
          {:portfolio-ui {:summary-scope-dropdown-open? true
                          :summary-time-range-dropdown-open? true
                          :performance-metrics-time-range-dropdown-open? true
                          :fee-schedule-referral-dropdown-open? true
                          :fee-schedule-staking-dropdown-open? true
                          :fee-schedule-maker-rebate-dropdown-open? true
                          :fee-schedule-market-dropdown-open? true}})))
  (is (= [[:effects/save-many [[[:portfolio-ui :fee-schedule-open?] false]
                               [[:portfolio-ui :fee-schedule-anchor] nil]
                               [[:portfolio-ui :fee-schedule-referral-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-staking-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-maker-rebate-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-market-dropdown-open?] false]]]
          [:effects/restore-dialog-focus]]
         (actions/close-portfolio-fee-schedule
          {:portfolio-ui {:fee-schedule-open? true
                          :fee-schedule-referral-dropdown-open? true
                          :fee-schedule-staking-dropdown-open? true
                          :fee-schedule-maker-rebate-dropdown-open? true
                          :fee-schedule-market-dropdown-open? true}})))
  (is (= [[:effects/save-many [[[:portfolio-ui :fee-schedule-open?] true]
                               [[:portfolio-ui :fee-schedule-referral-dropdown-open?] true]
                               [[:portfolio-ui :fee-schedule-staking-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-maker-rebate-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-market-dropdown-open?] false]]]]
         (actions/toggle-portfolio-fee-schedule-referral-dropdown
          {:portfolio-ui {:fee-schedule-open? true
                          :fee-schedule-referral-dropdown-open? false
                          :fee-schedule-staking-dropdown-open? true
                          :fee-schedule-maker-rebate-dropdown-open? true
                          :fee-schedule-market-dropdown-open? true}})))
  (is (= [[:effects/save-many [[[:portfolio-ui :fee-schedule-open?] true]
                               [[:portfolio-ui :fee-schedule-referral-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-staking-dropdown-open?] true]
                               [[:portfolio-ui :fee-schedule-maker-rebate-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-market-dropdown-open?] false]]]]
         (actions/toggle-portfolio-fee-schedule-staking-dropdown
          {:portfolio-ui {:fee-schedule-open? true
                          :fee-schedule-referral-dropdown-open? true
                          :fee-schedule-staking-dropdown-open? false
                          :fee-schedule-maker-rebate-dropdown-open? true
                          :fee-schedule-market-dropdown-open? true}})))
  (is (= [[:effects/save-many [[[:portfolio-ui :fee-schedule-open?] true]
                               [[:portfolio-ui :fee-schedule-referral-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-staking-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-maker-rebate-dropdown-open?] true]
                               [[:portfolio-ui :fee-schedule-market-dropdown-open?] false]]]]
         (actions/toggle-portfolio-fee-schedule-maker-rebate-dropdown
          {:portfolio-ui {:fee-schedule-open? true
                          :fee-schedule-referral-dropdown-open? true
                          :fee-schedule-staking-dropdown-open? true
                          :fee-schedule-maker-rebate-dropdown-open? false
                          :fee-schedule-market-dropdown-open? true}})))
  (is (= [[:effects/save-many [[[:portfolio-ui :fee-schedule-open?] true]
                               [[:portfolio-ui :fee-schedule-referral-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-staking-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-maker-rebate-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-market-dropdown-open?] true]]]]
         (actions/toggle-portfolio-fee-schedule-market-dropdown
          {:portfolio-ui {:fee-schedule-open? true
                          :fee-schedule-referral-dropdown-open? true
                          :fee-schedule-staking-dropdown-open? true
                          :fee-schedule-maker-rebate-dropdown-open? true
                          :fee-schedule-market-dropdown-open? false}})))
  (is (= [[:effects/save-many [[[:portfolio-ui :fee-schedule-open?] true]
                               [[:portfolio-ui :fee-schedule-referral-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-staking-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-maker-rebate-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-market-dropdown-open?] false]]]]
         (actions/toggle-portfolio-fee-schedule-market-dropdown
          {:portfolio-ui {:fee-schedule-open? true
                          :fee-schedule-referral-dropdown-open? true
                          :fee-schedule-staking-dropdown-open? true
                          :fee-schedule-maker-rebate-dropdown-open? true
                          :fee-schedule-market-dropdown-open? true}})))
  (is (= [[:effects/save-many [[[:portfolio-ui :fee-schedule-referral-discount] :referral-4]
                               [[:portfolio-ui :fee-schedule-referral-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-staking-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-maker-rebate-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-market-dropdown-open?] false]]]]
         (actions/select-portfolio-fee-schedule-referral-discount
          {}
          "4%")))
  (is (= [[:effects/save-many [[[:portfolio-ui :fee-schedule-staking-tier] :diamond]
                               [[:portfolio-ui :fee-schedule-referral-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-staking-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-maker-rebate-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-market-dropdown-open?] false]]]]
         (actions/select-portfolio-fee-schedule-staking-tier
          {}
          "Diamond")))
  (is (= [[:effects/save-many [[[:portfolio-ui :fee-schedule-maker-rebate-tier] :tier-2]
                               [[:portfolio-ui :fee-schedule-referral-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-staking-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-maker-rebate-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-market-dropdown-open?] false]]]]
         (actions/select-portfolio-fee-schedule-maker-rebate-tier
          {}
          "tier2")))
  (is (= [[:effects/save-many [[[:portfolio-ui :fee-schedule-market-type]
                                :spot-aligned-stable-pair]
                               [[:portfolio-ui :fee-schedule-referral-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-staking-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-maker-rebate-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-market-dropdown-open?] false]]]]
         (actions/select-portfolio-fee-schedule-market-type
          {}
          "spotAlignedStablePair")))
  (is (= [[:effects/save-many [[[:portfolio-ui :fee-schedule-market-type]
                                :hip3-perps-growth-mode]
                               [[:portfolio-ui :fee-schedule-referral-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-staking-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-maker-rebate-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-market-dropdown-open?] false]]]]
         (actions/select-portfolio-fee-schedule-market-type
          {}
          "HIP-3 Perps + Growth mode")))
  (is (= [[:effects/save-many [[[:portfolio-ui :fee-schedule-open?] false]
                               [[:portfolio-ui :fee-schedule-anchor] nil]
                               [[:portfolio-ui :fee-schedule-referral-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-staking-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-maker-rebate-dropdown-open?] false]
                               [[:portfolio-ui :fee-schedule-market-dropdown-open?] false]]]
          [:effects/restore-dialog-focus]]
         (actions/handle-portfolio-fee-schedule-keydown {} "Escape")))
  (is (= [] (actions/handle-portfolio-fee-schedule-keydown {} "Enter"))))

(deftest open-portfolio-fee-schedule-normalizes-anchor-bounds-test
  (let [anchor {:left "24"
                :right 190
                :top "220"
                :bottom 248
                :width 166
                :height "28"
                :viewportWidth 900
                :viewportHeight "700"}]
    (is (= [[:effects/save-many [[[:portfolio-ui :fee-schedule-open?] true]
                                 [[:portfolio-ui :fee-schedule-anchor]
                                  {:left 24
                                   :right 190
                                   :top 220
                                   :bottom 248
                                   :width 166
                                   :height 28
                                   :viewport-width 900
                                   :viewport-height 700}]
                                 [[:portfolio-ui :fee-schedule-referral-discount] nil]
                                 [[:portfolio-ui :fee-schedule-staking-tier] nil]
                                 [[:portfolio-ui :fee-schedule-maker-rebate-tier] nil]
                                 [[:portfolio-ui :fee-schedule-referral-dropdown-open?] false]
                                 [[:portfolio-ui :fee-schedule-staking-dropdown-open?] false]
                                 [[:portfolio-ui :fee-schedule-maker-rebate-dropdown-open?] false]
                                 [[:portfolio-ui :fee-schedule-market-dropdown-open?] false]
                                 [[:portfolio-ui :summary-scope-dropdown-open?] false]
                                 [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                                 [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]]
           (actions/open-portfolio-fee-schedule {} anchor)))))

(deftest portfolio-anchor-normalization-boundaries-test
  (is (= {:left 10
          :top 20}
         (get-in (first (actions/open-portfolio-volume-history
                         {}
                         #js {:left "10"
                              :top "20"}))
                 [1 1 1])))
  (is (= {:left 16
          :viewport-width 960}
         (get-in (first (actions/open-portfolio-fee-schedule
                         {}
                         #js {:left "16"
                              :viewportWidth "960"}))
                 [1 1 1])))
  (is (nil? (get-in (first (actions/open-portfolio-volume-history
                            {}
                            {:left js/NaN
                             :top "abc"
                             :height ""}))
                    [1 1 1])))
  (is (nil? (get-in (first (actions/open-portfolio-fee-schedule
                            {}
                            {:left js/NaN
                             :top "abc"
                             :height ""}))
                    [1 1 1]))))

(deftest select-portfolio-summary-scope-normalizes-and-closes-dropdowns-test
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope] :perps]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-summary-scope {} "perp")))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-scope] :all]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-summary-scope {} :unknown))))

(deftest select-portfolio-summary-time-range-normalizes-and-closes-dropdowns-test
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-time-range] :three-month]
                               [[:portfolio-ui :summary-custom-range] nil]
                               [[:portfolio-ui :summary-range-strip] nil]
                               [[:portfolio-ui :summary-range-drag] nil]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]
          [:effects/local-storage-set "portfolio-summary-time-range" "three-month"]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-summary-time-range {} "3M")))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-time-range] :all-time]
                               [[:portfolio-ui :summary-custom-range] nil]
                               [[:portfolio-ui :summary-range-strip] nil]
                               [[:portfolio-ui :summary-range-drag] nil]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]
          [:effects/local-storage-set "portfolio-summary-time-range" "all-time"]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-summary-time-range {} "allTime")))
  (is (= [[:effects/save-many [[[:portfolio-ui :summary-time-range] :one-year]
                               [[:portfolio-ui :summary-custom-range] nil]
                               [[:portfolio-ui :summary-range-strip] nil]
                               [[:portfolio-ui :summary-range-drag] nil]
                               [[:portfolio-ui :summary-scope-dropdown-open?] false]
                               [[:portfolio-ui :summary-time-range-dropdown-open?] false]
                               [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]]]
          [:effects/local-storage-set "portfolio-summary-time-range" "one-year"]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-summary-time-range {} :unknown))))

(deftest restore-portfolio-summary-time-range-loads-normalized-local-storage-preference-test
  (let [store (atom {:portfolio-ui {:summary-time-range :month}})]
    (with-redefs [platform/local-storage-get (fn [_] "3M")]
      (actions/restore-portfolio-summary-time-range! store))
    (is (= :three-month (get-in @store [:portfolio-ui :summary-time-range]))))
  (let [store (atom {:portfolio-ui {:summary-time-range :three-month}})]
    (with-redefs [platform/local-storage-get (fn [_] "not-a-range")]
      (actions/restore-portfolio-summary-time-range! store))
    (is (= :one-year (get-in @store [:portfolio-ui :summary-time-range])))))

(deftest select-portfolio-chart-tab-normalizes-and-saves-selected-tab-test
  (is (= [[:effects/save-many
           [[[:portfolio-ui :chart-tab] :account-value]]]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-chart-tab {} "accountValue")))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :chart-tab] :returns]]]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-chart-tab {} "return")))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :chart-tab] :pnl]]]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-chart-tab {} :pnl)))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :chart-tab] :returns]]]
          replace-shareable-route-query-effect]
         (actions/select-portfolio-chart-tab {} :unknown))))

(deftest set-portfolio-account-info-tab-normalizes-and-saves-selected-tab-test
  (is (= [[:effects/save
           [:portfolio-ui :account-info-tab]
           :performance-metrics]
          replace-shareable-route-query-effect]
         (actions/set-portfolio-account-info-tab {} "performanceMetrics")))
  (is (= [[:effects/save
           [:portfolio-ui :account-info-tab]
           :performance-metrics]
          replace-shareable-route-query-effect]
         (actions/set-portfolio-account-info-tab {} " performanceMetric ")))
  (is (= [[:effects/save
           [:portfolio-ui :account-info-tab]
           :deposits-withdrawals]
          replace-shareable-route-query-effect]
         (actions/set-portfolio-account-info-tab {} "depositsWithdrawals")))
  (is (= [[:effects/save
           [:portfolio-ui :account-info-tab]
           :open-orders]
          replace-shareable-route-query-effect]
         (actions/set-portfolio-account-info-tab {} "openOrders")))
  (is (= [[:effects/save
           [:portfolio-ui :account-info-tab]
           :trade-history]
          replace-shareable-route-query-effect]
         (actions/set-portfolio-account-info-tab {} "tradeHistory")))
  (is (= [[:effects/save
           [:portfolio-ui :account-info-tab]
           :funding-history]
          replace-shareable-route-query-effect]
         (actions/set-portfolio-account-info-tab {} "fundingHistory")))
  (is (= [[:effects/save
           [:portfolio-ui :account-info-tab]
           :order-history]
          replace-shareable-route-query-effect]
         (actions/set-portfolio-account-info-tab {} "orderHistory")))
  (is (= [[:effects/save
           [:portfolio-ui :account-info-tab]
           :performance-metrics]
          replace-shareable-route-query-effect]
         (actions/set-portfolio-account-info-tab {} :unknown))))

(deftest normalize-summary-time-range-recognizes-common-aliases-test
  (is (= :three-month (actions/normalize-summary-time-range "3M")))
  (is (= :three-month (actions/normalize-summary-time-range "quarter")))
  (is (= :six-month (actions/normalize-summary-time-range "halfYear")))
  (is (= :one-year (actions/normalize-summary-time-range "1y")))
  (is (= :two-year (actions/normalize-summary-time-range "two_year"))))

(deftest normalize-summary-time-range-falls-back-for-invalid-values-test
  (is (= :one-year (actions/normalize-summary-time-range nil)))
  (is (= :one-year (actions/normalize-summary-time-range "not-a-range"))))

(deftest set-portfolio-metrics-result-saves-worker-payload-test
  (is (= [[:effects/save [:portfolio-ui :metrics-result]
           {:status :ok
            :series [1 2 3]}]]
         (actions/set-portfolio-metrics-result
          {}
          {:status :ok
           :series [1 2 3]}))))
