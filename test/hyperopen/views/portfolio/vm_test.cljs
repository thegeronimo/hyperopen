(ns hyperopen.views.portfolio.vm-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.system :as system]
            [hyperopen.views.account-equity-view :as account-equity-view]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.views.portfolio.vm :as vm]))

(def ^:private day-ms
  (* 24 60 60 1000))

(defn- approx=
  [left right tolerance]
  (and (number? left)
       (number? right)
       (<= (js/Math.abs (- left right)) tolerance)))

(def ^:private fixture-start-ms
  (.getTime (js/Date. "2024-01-01T00:00:00.000Z")))

(use-fixtures :each
  (fn [f]
    (vm/reset-portfolio-vm-cache!)
    (reset! @#'vm/last-metrics-request nil)
    (f)
    (vm/reset-portfolio-vm-cache!)
    (reset! @#'vm/last-metrics-request nil)))

(defn- performance-metric-row
  [view-model metric-key]
  (some (fn [{:keys [rows]}]
          (some (fn [row]
                  (when (= metric-key (:key row))
                    row))
                rows))
        (get-in view-model [:performance-metrics :groups])))

(deftest volume-14d-usd-uses-last-14-days-when-timestamps-available-test
  (let [now (.now js/Date)
        within (- now (* 2 day-ms))
        outside (- now (* 30 day-ms))
        state {:orders {:fills [{:time within :sz "2" :px "100"}
                                {:time outside :sz "5" :px "100"}]}}]
    (is (= 200 (vm/volume-14d-usd state)))
    (testing "Cache correctly skips calculation if fills are identical"
      (is (= 200 (vm/volume-14d-usd state))))))

(deftest volume-14d-usd-falls-back-to-all-values-when-row-times-missing-test
  (let [state {:orders {:fills [{:sz "1" :px "50"}
                                {:sz "2" :px "25"}]}}]
    (is (= 100 (vm/volume-14d-usd state)))))

(deftest portfolio-vm-derives-selected-summary-and-drawdown-from-portfolio-payload-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 40
                                                               :perps-value 60
                                                               :cross-account-value 60
                                                               :unrealized-pnl 11})]
    (let [state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[1 10] [2 30] [3 15]]
                                                      :accountValueHistory [[1 100] [2 100] [3 100]]
                                                      :vlm 123}}
                             :user-fees nil}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 60}}
                           :totalVaultEquity 5}
                 :borrow-lend {:total-supplied-usd 7}}
          view-model (vm/portfolio-vm state)]
      (is (= 0 (:volume-14d-usd view-model)))
      (is (= 5 (get-in view-model [:summary :pnl])))
      (is (= 123 (get-in view-model [:summary :volume])))
      (is (< (js/Math.abs (- 0.15 (get-in view-model [:summary :max-drawdown-pct]))) 1e-12))
      (is (= 112 (get-in view-model [:summary :total-equity])))
      (is (true? (get-in view-model [:summary :show-perps-account-equity?])))
      (is (= "Spot Account Equity" (get-in view-model [:summary :spot-equity-label])))
      (is (true? (get-in view-model [:summary :show-earn-balance?])))
      (is (= :returns (get-in view-model [:chart :selected-tab])))
      (is (= 3 (count (get-in view-model [:chart :points])))))))

(deftest portfolio-vm-uses-user-fees-payload-for-14d-volume-and-fee-rates-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 20
                                                               :cross-account-value 20
                                                               :unrealized-pnl 1})]
    (let [state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[1 1] [2 2]]
                                                      :accountValueHistory [[1 100] [2 100]]
                                                      :vlm 44}}
                             :user-fees {:userCrossRate 0.0005
                                         :userAddRate 0.0001
                                         :activeReferralDiscount 0.1
                                         :dailyUserVlm [{:exchange 100
                                                         :userCross 60
                                                         :userAdd 20}
                                                        {:exchange 10
                                                         :userCross 1
                                                         :userAdd 1}]}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 20}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)]
      (is (= 80 (:volume-14d-usd view-model)))
      (is (< (js/Math.abs (- 0.045 (get-in view-model [:fees :taker]))) 1e-12))
      (is (< (js/Math.abs (- 0.009 (get-in view-model [:fees :maker]))) 1e-12)))))

(deftest portfolio-vm-hides-perps-and-earn-rows-when-unified-account-mode-enabled-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 50
                                                               :perps-value 200
                                                               :cross-account-value 200
                                                               :unrealized-pnl 0})]
    (let [state {:account {:mode :unified}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[1 1] [2 1]]
                                                      :accountValueHistory [[1 100] [2 100]]
                                                      :vlm 10}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 200}}
                           :totalVaultEquity 20}
                 :borrow-lend {:total-supplied-usd 100}}
          view-model (vm/portfolio-vm state)]
      (is (= 70 (get-in view-model [:summary :total-equity])))
      (is (false? (get-in view-model [:summary :show-perps-account-equity?])))
      (is (= "Trading Equity" (get-in view-model [:summary :spot-equity-label])))
      (is (false? (get-in view-model [:summary :show-earn-balance?]))))))

(deftest portfolio-vm-defaults-selector-labels-and-fallbacks-when-summary-missing-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 0
                                                               :perps-value 0
                                                               :cross-account-value 0
                                                               :unrealized-pnl 0})]
    (let [view-model (vm/portfolio-vm {:portfolio {:summary-by-key {}}
                                       :portfolio-ui {}})]
      (testing "selector defaults"
        (is (= :all (get-in view-model [:selectors :summary-scope :value])))
        (is (= "Perps + Spot + Vaults" (get-in view-model [:selectors :summary-scope :label])))
        (is (= :month (get-in view-model [:selectors :summary-time-range :value])))
        (is (= "30D" (get-in view-model [:selectors :summary-time-range :label])))
        (is (= :month (get-in view-model [:selectors :performance-metrics-time-range :value])))
        (is (= "30D" (get-in view-model [:selectors :performance-metrics-time-range :label])))
        (is (false? (get-in view-model [:selectors :performance-metrics-time-range :open?])))
        (is (= ["24H" "7D" "30D" "3M" "6M" "1Y" "2Y" "All-time"]
               (mapv :label (get-in view-model [:selectors :summary-time-range :options])))))
      (testing "missing data fallback behavior"
        (is (= 0 (get-in view-model [:summary :pnl])))
        (is (= 0 (get-in view-model [:summary :volume])))
        (is (nil? (get-in view-model [:summary :max-drawdown-pct])))
        (is (= :returns (get-in view-model [:chart :selected-tab])))
        (is (empty? (get-in view-model [:chart :points])))
        (is (= [{:value 3 :y-ratio 0}
                {:value 2 :y-ratio (/ 1 3)}
                {:value 1 :y-ratio (/ 2 3)}
                {:value 0 :y-ratio 1}]
               (get-in view-model [:chart :y-ticks])))))))

(deftest portfolio-vm-background-status-reports-initial-data-loads-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 0
                                                               :perps-value 0
                                                               :cross-account-value 0
                                                               :unrealized-pnl 0})]
    (let [state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :chart-tab :returns}
                 :portfolio {:summary-by-key {}
                             :loading? true
                             :loaded-at-ms nil
                             :user-fees-loading? true
                             :user-fees-loaded-at-ms nil}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 0}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          background-status (:background-status (vm/portfolio-vm state))]
      (is (true? (:visible? background-status)))
      (is (= "Portfolio data is still syncing" (:title background-status)))
      (is (= "You can keep using the page while the remaining data finishes loading."
             (:detail background-status)))
      (is (= [{:id :portfolio-returns
               :label "Portfolio returns"}
              {:id :fees-volume
               :label "Fees & volume"}]
             (:items background-status))))))

(deftest portfolio-vm-background-status-reports-benchmark-and-metrics-after-chart-ready-test
  (let [store (atom {:portfolio-ui {}})]
    (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                                {:spot-equity 10
                                                                 :perps-value 10
                                                                 :cross-account-value 10
                                                                 :unrealized-pnl 0})
                  system/store store
                  vm/metrics-worker (delay #js {:postMessage (fn [_payload] nil)})]
      (let [state {:account {:mode :classic}
                   :portfolio-ui {:summary-scope :all
                                  :summary-time-range :month
                                  :chart-tab :returns
                                  :returns-benchmark-coins ["SPY"]
                                  :returns-benchmark-coin "SPY"
                                  :metrics-loading? true}
                   :asset-selector {:markets [{:coin "SPY"
                                               :symbol "SPY"
                                               :market-type :spot
                                               :cache-order 1}]}
                   :portfolio {:summary-by-key {:month {:pnlHistory [[1 0] [2 0] [3 0]]
                                                        :accountValueHistory [[1 100] [2 105] [3 110]]
                                                        :vlm 10}}
                               :loaded-at-ms 1
                               :user-fees-loaded-at-ms 1}
                   :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                             :totalVaultEquity 0}
                   :borrow-lend {:total-supplied-usd 0}}
            background-status (:background-status (vm/portfolio-vm state))]
        (is (true? (:visible? background-status)))
        (is (= "Portfolio analytics are still syncing" (:title background-status)))
        (is (= "The chart is ready. The remaining analytics will fill in automatically."
               (:detail background-status)))
               (is (= [{:id :benchmark-history
                 :label "Benchmark history"}
                {:id :performance-metrics
                 :label "Performance metrics"}]
               (:items background-status)))))))

(deftest portfolio-vm-skips-heavy-derivations-on-unrelated-state-writes-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [base-state {:account {:mode :classic}
                      :portfolio-ui {:summary-scope :all
                                     :summary-time-range :month
                                     :chart-tab :returns}
                      :asset-selector {:markets [{:coin "SPY"
                                                  :symbol "SPY"
                                                  :market-type :spot
                                                  :cache-order 1}]}
                      :portfolio {:summary-by-key {:month {:pnlHistory [[1 0] [2 0] [3 0]]
                                                           :accountValueHistory [[1 100] [2 105] [3 110]]
                                                           :vlm 10}}
                                  :loaded-at-ms 1
                                  :user-fees-loaded-at-ms 1}
                      :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                                :totalVaultEquity 0}
                      :borrow-lend {:total-supplied-usd 0}}]
      (vm/portfolio-vm base-state)
      (let [benchmark-cache @#'vm/benchmark-computation-context-cache
            performance-cache @#'vm/performance-metrics-model-cache
            chart-cache @#'vm/chart-model-cache]
        (vm/portfolio-vm (assoc base-state :toast {:id 1}))
        (is (identical? (:context benchmark-cache)
                        (:context @#'vm/benchmark-computation-context-cache)))
        (is (identical? (:model performance-cache)
                        (:model @#'vm/performance-metrics-model-cache)))
        (is (identical? (:model chart-cache)
                        (:model @#'vm/chart-model-cache)))))))

(deftest portfolio-vm-builds-performance-metrics-groups-with-benchmark-fallbacks-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                             {:spot-equity 0
                                                              :perps-value 0
                                                              :cross-account-value 0
                                                              :unrealized-pnl 0})]
    (let [t0 fixture-start-ms
          t1 (+ fixture-start-ms day-ms)
          t2 (+ fixture-start-ms (* 2 day-ms))
          state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[t0 0]
                                                                   [t1 11]
                                                                   [t2 19]]
                                                      :accountValueHistory [[t0 100]
                                                                            [t1 111]
                                                                            [t2 119]]
                                                      :vlm 0}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 0}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)
          groups (get-in view-model [:performance-metrics :groups])]
      
      (testing "Worker data structured-clone integrity"
        (let [raw-metrics-result (get-in view-model [:performance-metrics :values])
              worker-result {:portfolio-values raw-metrics-result
                             :benchmark-values-by-coin {"SPY" raw-metrics-result}}
              deserialized (-> worker-result
                               clj->js
                               (js->clj :keywordize-keys true)
                               (@#'vm/normalize-worker-metrics-result))]
          (is (= :ok (get-in deserialized [:portfolio-values :metric-status :time-in-market]))
              "Metric status keywords survive worker structured-clone round-trip")
          (is (= :suppressed (get-in deserialized [:portfolio-values :metric-status :r2])))
          (is (= :benchmark-coverage-gate-failed (get-in deserialized [:portfolio-values :metric-reason :r2])))
          (is (contains? (:benchmark-values-by-coin deserialized) "SPY"))
          (is (not (contains? (:benchmark-values-by-coin deserialized) :SPY)))))
      
      (is (seq groups))
      (is (nil? (performance-metric-row view-model :time-in-market)))
      (is (= "Cumulative Return"
             (get-in groups [0 :rows 0 :label])))
      (is (= :ok (get-in (performance-metric-row view-model :cumulative-return)
                         [:portfolio-status])))
      (is (nil? (get-in (performance-metric-row view-model :cumulative-return)
                        [:benchmark-value])))
      (is (= [] (get-in view-model [:performance-metrics :benchmark-columns])))
      (is (false? (get-in view-model [:performance-metrics :benchmark-selected?])))
      (is (nil? (:value (performance-metric-row view-model :r2))))
      (is (= :suppressed (get-in (performance-metric-row view-model :r2) [:portfolio-status])))
      (is (= :benchmark-coverage-gate-failed
             (get-in (performance-metric-row view-model :r2) [:portfolio-reason])))
      (is (nil? (:value (performance-metric-row view-model :information-ratio)))))))
(deftest portfolio-vm-chart-tab-selection-switches-history-source-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :chart-tab "pnl"}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[1 -10] [2 5]]
                                                      :accountValueHistory [[1 100] [2 200]]
                                                      :vlm 10}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)]
      (is (= :pnl (get-in view-model [:chart :selected-tab])))
      (is (= [-10 5]
             (mapv :value (get-in view-model [:chart :points]))))
      (is (= ["Returns" "Account Value" "PNL"]
             (mapv :label (get-in view-model [:chart :tabs])))))))


(deftest portfolio-vm-returns-tab-uses-shared-portfolio-metrics-returns-source-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})
                portfolio-metrics/returns-history-rows (fn [_state _summary _summary-scope]
                                                         [[1 0]
                                                          [2 50]])]
    (let [state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :chart-tab :returns}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[1 0] [2 0]]
                                                      :accountValueHistory [[1 100] [2 100]]
                                                      :vlm 10}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)]
      (is (= [0 50]
             (mapv :value (get-in view-model [:chart :points])))))))

(deftest portfolio-vm-returns-tab-uses-implied-flow-returns-to-avoid-cashflow-spikes-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :chart-tab :returns}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[1 0] [2 0] [3 -1] [4 0]]
                                                      :accountValueHistory [[1 4] [2 205] [3 204] [4 205]]
                                                      :vlm 10}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)]
      (is (= :returns (get-in view-model [:chart :selected-tab])))
      (is (= :percent (get-in view-model [:chart :axis-kind])))
      (is (= [1 2 3 4]
             (mapv :time-ms (get-in view-model [:chart :points]))))
      (is (= [0 0 -0.49 0]
             (mapv :value (get-in view-model [:chart :points])))))))
