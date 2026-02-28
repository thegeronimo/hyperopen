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

(deftest portfolio-vm-chart-line-path-uses-direct-segments-test
  (let [points [{:x-ratio 0
                 :y-ratio 1}
                {:x-ratio 0.5
                 :y-ratio 0.25}
                {:x-ratio 1
                 :y-ratio 0}]
        path (@#'vm/chart-line-path points)]
    (is (= "M 0 100 L 50 25 L 100 0" path))))

(deftest portfolio-vm-chart-line-path-extends-single-point-to-right-edge-test
  (let [points [{:x-ratio 0
                 :y-ratio 0.4}]
        path (@#'vm/chart-line-path points)]
    (is (= "M 0 40 L 100 40" path))))

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
      (is (= 3 (count (get-in view-model [:chart :points]))))
      (is (seq (get-in view-model [:chart :path]))))))

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

(deftest portfolio-vm-derives-three-month-window-from-all-time-when-range-slice-missing-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 0
                                                               :perps-value 0
                                                               :cross-account-value 0
                                                               :unrealized-pnl 0})]
    (let [t0 (.getTime (js/Date. "2024-01-01T00:00:00.000Z"))
          t1 (.getTime (js/Date. "2024-03-01T00:00:00.000Z"))
          t2 (.getTime (js/Date. "2024-04-01T00:00:00.000Z"))
          t3 (.getTime (js/Date. "2024-05-15T00:00:00.000Z"))
          t4 (.getTime (js/Date. "2024-06-30T00:00:00.000Z"))
          state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :three-month
                                :chart-tab :pnl}
                 :portfolio {:summary-by-key {:all-time {:pnlHistory [[t0 10] [t1 20] [t2 30] [t3 45] [t4 60]]
                                                         :accountValueHistory [[t0 100] [t1 110] [t2 120] [t3 130] [t4 150]]}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 0}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)]
      (is (= :three-month (get-in view-model [:summary :selected-key])))
      (is (= "3M" (get-in view-model [:selectors :summary-time-range :label])))
      (is (= [t2 t3 t4]
             (mapv :time-ms (get-in view-model [:chart :points]))))
      (is (= [0 15 30]
             (mapv :value (get-in view-model [:chart :points]))))
      (is (= 30 (get-in view-model [:summary :pnl]))))))

(deftest portfolio-vm-normalizes-perp-two-year-summary-key-variants-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 0
                                                               :perps-value 0
                                                               :cross-account-value 0
                                                               :unrealized-pnl 0})]
    (let [state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :perps
                                :summary-time-range :two-year
                                :chart-tab :pnl}
                 :portfolio {:summary-by-key {"perp2Y" {:pnlHistory [[1 0] [2 5]]
                                                        :accountValueHistory [[1 100] [2 110]]
                                                        :vlm 10}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 0}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)]
      (is (= :perp-two-year (get-in view-model [:summary :selected-key])))
      (is (= [0 5]
             (mapv :value (get-in view-model [:chart :points]))))
      (is (= 5 (get-in view-model [:summary :pnl]))))))

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

(deftest portfolio-vm-chart-hover-selects-and-clamps-strategy-point-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [base-state {:account {:mode :classic}
                      :portfolio-ui {:summary-scope :all
                                     :summary-time-range :month
                                     :chart-tab :pnl
                                     :chart-hover-index 1}
                      :portfolio {:summary-by-key {:month {:pnlHistory [[10 -1] [20 5] [30 9]]
                                                           :accountValueHistory [[10 100] [20 120] [30 130]]
                                                           :vlm 10}}}
                      :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                                :totalVaultEquity 0}
                      :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm base-state)
          hover (get-in view-model [:chart :hover])]
      (is (true? (:active? hover)))
      (is (= 1 (:index hover)))
      (is (= 20 (get-in hover [:point :time-ms])))
      (is (= 5 (get-in hover [:point :value])))
      (is (= [10 20 30]
             (mapv :time-ms (get-in view-model [:chart :points]))))
      (let [clamped-view-model (vm/portfolio-vm (assoc-in base-state [:portfolio-ui :chart-hover-index] 99))
            clamped-hover (get-in clamped-view-model [:chart :hover])]
        (is (= 2 (:index clamped-hover)))
        (is (= 30 (get-in clamped-hover [:point :time-ms])))))))

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
             (mapv :value (get-in view-model [:chart :points]))))
      (is (seq (get-in view-model [:chart :path]))))))

(deftest portfolio-vm-returns-tab-uses-pnl-deltas-to-separate-perps-flows-from-performance-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :perps
                                :summary-time-range :month
                                :chart-tab :returns}
                 :portfolio {:summary-by-key {:perp-month {:pnlHistory [[1 0] [2 0] [3 0]]
                                                           :accountValueHistory [[1 100] [2 150] [3 150]]
                                                           :vlm 10}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)]
      (is (= :returns (get-in view-model [:chart :selected-tab])))
      (is (= [0 0 0]
             (mapv :value (get-in view-model [:chart :points])))))))

(deftest portfolio-vm-returns-tab-uses-shared-account-and-pnl-timestamps-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :chart-tab :returns}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[1 0] [3 10] [4 20]]
                                                      :accountValueHistory [[1 100] [2 120] [4 140]]
                                                      :vlm 10}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)]
      (is (= :returns (get-in view-model [:chart :selected-tab])))
      (is (= [1 4]
             (mapv :time-ms (get-in view-model [:chart :points]))))
      (is (= [0 18.18]
             (mapv :value (get-in view-model [:chart :points])))))))

(deftest portfolio-vm-builds-returns-benchmark-options-from-asset-selector-markets-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :chart-tab :returns}
                 :asset-selector {:markets [{:coin "BTC"
                                             :symbol "BTC-USD"
                                             :market-type :perp
                                             :openInterest "900"
                                             :cache-order 1}
                                            {:coin "SPY"
                                             :symbol "SPY"
                                             :market-type :spot
                                             :openInterest "100"
                                             :cache-order 2}]}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[1 0] [2 0]]
                                                      :accountValueHistory [[1 100] [2 110]]
                                                      :vlm 10}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)
          benchmark-selector (get-in view-model [:selectors :returns-benchmark])]
      (is (= [] (:selected-coins benchmark-selector)))
      (is (= [] (:selected-options benchmark-selector)))
      (is (= "BTC" (:top-coin benchmark-selector)))
      (is (= "" (:coin-search benchmark-selector)))
      (is (= ["BTC-USD (PERP)" "SPY (SPOT)"]
             (mapv :label (:candidates benchmark-selector))))
      (is (= ["BTC" "SPY"]
             (mapv :value (:candidates benchmark-selector))))))

(deftest portfolio-vm-limits-vault-benchmark-options-to-top-100-by-tvl-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [address-for (fn [idx]
                        (let [suffix (str idx)
                              zero-count (max 0 (- 40 (count suffix)))]
                          (str "0x"
                               (apply str (repeat zero-count "0"))
                               suffix)))
          top-address (address-for 105)
          cutoff-address (address-for 6)
          excluded-address (address-for 5)
          vault-rows (mapv (fn [idx]
                             {:name (str "Vault " idx)
                              :vault-address (address-for idx)
                              :relationship {:type :normal}
                              :tvl idx})
                           (range 1 106))
          state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :chart-tab :returns
                                :returns-benchmark-coins [(str "vault:" excluded-address)]
                                :returns-benchmark-coin (str "vault:" excluded-address)
                                :returns-benchmark-search "vault"}
                 :asset-selector {:markets [{:coin "BTC"
                                             :symbol "BTC-USD"
                                             :market-type :perp
                                             :openInterest "900"
                                             :cache-order 1}]}
                 :vaults {:merged-index-rows (conj vault-rows
                                                   {:name "Child Vault"
                                                    :vault-address "0x3333333333333333333333333333333333333333"
                                                    :relationship {:type :child}
                                                    :tvl 1000})}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[1 0] [2 0]]
                                                      :accountValueHistory [[1 100] [2 110]]
                                                      :vlm 10}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          benchmark-selector (get-in (vm/portfolio-vm state) [:selectors :returns-benchmark])
          vault-candidates (->> (:candidates benchmark-selector)
                                (filter (fn [{:keys [value]}]
                                          (str/starts-with? value "vault:")))
                                vec)]
      (is (= 100 (count vault-candidates)))
      (is (= (str "vault:" top-address)
             (some-> vault-candidates first :value)))
      (is (= (str "vault:" cutoff-address)
             (some-> vault-candidates last :value)))
      (is (not-any? #(= (str "vault:" excluded-address) (:value %))
                    vault-candidates))
      (is (= [] (:selected-coins benchmark-selector))))))

(deftest portfolio-vm-memoizes-benchmark-selector-options-by-markets-identity-and-signature-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [build-count (atom 0)
          original-builder vm/*build-benchmark-selector-options*
          markets-a [{:coin "BTC"
                      :symbol "BTC-USD"
                      :market-type :perp
                      :openInterest "900"
                      :cache-order 1}
                     {:coin "ETH"
                      :symbol "ETH-USD"
                      :market-type :perp
                      :openInterest "800"
                      :cache-order 2}]
          markets-b (mapv identity markets-a)
          markets-c (assoc-in markets-b [1 :openInterest] "1200")
          base-state {:account {:mode :classic}
                      :portfolio-ui {:summary-scope :all
                                     :summary-time-range :month
                                     :chart-tab :returns}
                      :portfolio {:summary-by-key {:month {:pnlHistory [[1 0] [2 0]]
                                                           :accountValueHistory [[1 100] [2 110]]
                                                           :vlm 10}}}
                      :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                                :totalVaultEquity 0}
                      :borrow-lend {:total-supplied-usd 0}}
          build-vm (fn [markets]
                     (vm/portfolio-vm (assoc base-state :asset-selector {:markets markets})))]
      (is (false? (identical? markets-a markets-b)))
      (with-redefs [vm/*build-benchmark-selector-options*
                    (fn [markets]
                      (swap! build-count inc)
                      (original-builder markets))]
        (let [selector-a (get-in (build-vm markets-a) [:selectors :returns-benchmark])]
          (is (= 1 @build-count))
          (let [selector-identity-hit (get-in (build-vm markets-a) [:selectors :returns-benchmark])]
            (is (= 1 @build-count))
            (let [selector-signature-hit (get-in (build-vm markets-b) [:selectors :returns-benchmark])
                  selector-invalidated (get-in (build-vm markets-c) [:selectors :returns-benchmark])]
              (is (= 2 @build-count))
              (is (= ["BTC" "ETH"] (mapv :value (:candidates selector-a))))
              (is (= ["BTC" "ETH"] (mapv :value (:candidates selector-identity-hit))))
              (is (= ["BTC" "ETH"] (mapv :value (:candidates selector-signature-hit))))
              (is (= ["ETH" "BTC"] (mapv :value (:candidates selector-invalidated))))
              (is (= "BTC" (:top-coin selector-a)))
              (is (= "ETH" (:top-coin selector-invalidated))))))))))

(deftest portfolio-vm-returns-benchmark-series-aligns-to-portfolio-return-timestamps-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :chart-tab :returns
                                :returns-benchmark-coin "SPY"}
                 :asset-selector {:markets [{:coin "SPY"
                                             :symbol "SPY"
                                             :market-type :spot
                                             :cache-order 1}]}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[1 0] [2 0] [3 0] [4 0]]
                                                      :accountValueHistory [[1 100] [2 110] [3 120] [4 130]]
                                                      :vlm 10}}}
                 :candles {"SPY" {:1h [{:t 1 :c 50}
                                       {:t 3 :c 55}
                                       {:t 4 :c 60}]}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)
          series (get-in view-model [:chart :series])
          strategy-series (first series)
          benchmark-series (second series)]
      (is (= [:strategy :benchmark-0]
             (mapv :id series)))
      (is (= [1 2 3 4]
             (mapv :time-ms (:points strategy-series))))
      (is (= [1 2 3 4]
             (mapv :time-ms (:points benchmark-series))))
      (is (= [0 0 10 20]
             (mapv :value (:points benchmark-series))))
      (is (= "SPY (SPOT)"
             (:label benchmark-series))))))

(deftest portfolio-vm-builds-vault-benchmark-series-and-performance-columns-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [vault-address "0x1234567890abcdef1234567890abcdef12345678"
          vault-ref (str "vault:" vault-address)
          state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :chart-tab :returns
                                :returns-benchmark-coins [vault-ref]
                                :returns-benchmark-coin vault-ref}
                 :vaults {:merged-index-rows [{:name "Growi HF"
                                               :vault-address vault-address
                                               :relationship {:type :normal}
                                               :tvl 200
                                               :snapshot-by-key {:month [0.05 0.15]}}]}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[1 0] [2 0] [3 0]]
                                                      :accountValueHistory [[1 100] [2 110] [3 120]]
                                                      :vlm 10}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)
          benchmark-series (->> (get-in view-model [:chart :series])
                                (filter #(= :benchmark-0 (:id %)))
                                first)]
      (is (= "Growi HF (VAULT)" (:label benchmark-series)))
      (is (= [5 15 15]
             (mapv :value (:points benchmark-series))))
      (is (= [vault-ref]
             (get-in view-model [:performance-metrics :benchmark-coins])))
      (is (= "Growi HF (VAULT)"
             (get-in view-model [:performance-metrics :benchmark-label]))))))

(deftest portfolio-vm-performance-metrics-include-all-selected-benchmarks-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [t0 fixture-start-ms
          t1 (+ fixture-start-ms day-ms)
          t2 (+ fixture-start-ms (* 2 day-ms))
          state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :returns-benchmark-coins ["SPY" "QQQ"]}
                 :asset-selector {:markets [{:coin "SPY"
                                             :symbol "SPY"
                                             :market-type :spot
                                             :openInterest "200"
                                             :cache-order 1}
                                            {:coin "QQQ"
                                             :symbol "QQQ"
                                             :market-type :spot
                                             :openInterest "100"
                                             :cache-order 2}]}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[t0 0]
                                                                   [t1 11]
                                                                   [t2 19]]
                                                      :accountValueHistory [[t0 100]
                                                                            [t1 111]
                                                                            [t2 119]]
                                                      :vlm 0}}}
                 :candles {"SPY" {:1h [{:t t0 :c 50}
                                       {:t t1 :c 53}
                                       {:t t2 :c 57}]}
                           "QQQ" {:1h [{:t t0 :c 100}
                                       {:t t1 :c 101}
                                       {:t t2 :c 103}]}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)
          cumulative-return-row (performance-metric-row view-model :cumulative-return)
          r2-row (performance-metric-row view-model :r2)
          information-ratio-row (performance-metric-row view-model :information-ratio)]
      (is (true? (get-in view-model [:performance-metrics :benchmark-selected?])))
      (is (= ["SPY" "QQQ"] (get-in view-model [:performance-metrics :benchmark-coins])))
      (is (= [{:coin "SPY"
               :label "SPY (SPOT)"}
              {:coin "QQQ"
               :label "QQQ (SPOT)"}]
             (get-in view-model [:performance-metrics :benchmark-columns])))
      (is (= "SPY" (get-in view-model [:performance-metrics :benchmark-coin])))
      (is (= "SPY (SPOT)" (get-in view-model [:performance-metrics :benchmark-label])))
      (is (number? (:portfolio-value cumulative-return-row)))
      (is (number? (:benchmark-value cumulative-return-row)))
      (is (number? (get-in cumulative-return-row [:benchmark-values "SPY"])))
      (is (number? (get-in cumulative-return-row [:benchmark-values "QQQ"])))
      (is (number? (:value r2-row)))
      (is (number? (:value information-ratio-row))))))

(deftest portfolio-vm-builds-cumulative-only-benchmark-worker-requests-test
  (let [t0 fixture-start-ms
        t1 (+ fixture-start-ms day-ms)
        t2 (+ fixture-start-ms (* 2 day-ms))
        strategy-cumulative-rows [[t0 0]
                                  [t1 11]
                                  [t2 19]]
        spy-cumulative-rows [[t0 0]
                             [t1 6]
                             [t2 14]]
        qqq-cumulative-rows [[t0 0]
                             [t1 2]
                             [t2 7]]
        request-data (@#'vm/build-metrics-request-data strategy-cumulative-rows
                                                       {"SPY" spy-cumulative-rows
                                                        "QQQ" qqq-cumulative-rows}
                                                       ["SPY" "QQQ"])
        portfolio-request (:portfolio-request request-data)
        benchmark-requests (:benchmark-requests request-data)]
    (is (= strategy-cumulative-rows
           (:strategy-cumulative-rows portfolio-request)))
    (is (seq (:strategy-daily-rows portfolio-request)))
    (is (= spy-cumulative-rows
           (:benchmark-cumulative-rows portfolio-request)))
    (is (not (contains? portfolio-request :benchmark-daily-rows)))
    (is (= [{:coin "SPY"
             :request {:strategy-cumulative-rows spy-cumulative-rows}}
            {:coin "QQQ"
             :request {:strategy-cumulative-rows qqq-cumulative-rows}}]
           benchmark-requests))
    (is (every? (fn [{:keys [request]}]
                  (not (contains? request :strategy-daily-rows)))
                benchmark-requests))))

(deftest portfolio-vm-sync-metrics-derives-benchmark-daily-rows-from-cumulative-test
  (let [t0 fixture-start-ms
        t1 (+ fixture-start-ms day-ms)
        t2 (+ fixture-start-ms (* 2 day-ms))
        strategy-cumulative-rows [[t0 0]
                                  [t1 11]
                                  [t2 19]]
        spy-cumulative-rows [[t0 0]
                             [t1 6]
                             [t2 14]]
        expected-strategy-daily (portfolio-metrics/daily-compounded-returns strategy-cumulative-rows)
        expected-spy-daily (portfolio-metrics/daily-compounded-returns spy-cumulative-rows)
        captured-requests (atom [])
        request-data {:portfolio-request {:strategy-cumulative-rows strategy-cumulative-rows
                                          :strategy-daily-rows expected-strategy-daily
                                          :benchmark-cumulative-rows spy-cumulative-rows}
                      :benchmark-requests [{:coin "SPY"
                                            :request {:strategy-cumulative-rows spy-cumulative-rows}}]}]
    (with-redefs [portfolio-metrics/compute-performance-metrics (fn [request]
                                                                   (swap! captured-requests conj request)
                                                                   {:metric-status {}
                                                                    :metric-reason {}})]
      (@#'vm/compute-metrics-sync request-data)
      (is (= 2 (count @captured-requests)))
      (is (= expected-spy-daily
             (:benchmark-daily-rows (first @captured-requests))))
      (is (= expected-spy-daily
             (:strategy-daily-rows (second @captured-requests)))))))

(deftest portfolio-vm-request-metrics-computation-dedupes-by-lightweight-signature-test
  (let [write-count (atom 0)
        store (atom {:portfolio-ui {}})
        signature-a {:summary-time-range :month
                     :selected-benchmark-coins ["SPY"]
                     :strategy-source-version 101
                     :benchmark-source-versions [["SPY" 201]]}
        signature-b (assoc signature-a :strategy-source-version 102)
        request-a {:portfolio-request {:strategy-cumulative-rows [[1 0]
                                                                  [2 1]]}
                   :benchmark-requests [{:coin "SPY"
                                         :request {:strategy-cumulative-rows [[1 0]
                                                                              [2 3]]}}]}
        request-b {:portfolio-request {:strategy-cumulative-rows [[1 0]
                                                                  [2 50]
                                                                  [3 99]]}
                   :benchmark-requests [{:coin "SPY"
                                         :request {:strategy-cumulative-rows [[1 0]
                                                                              [2 60]
                                                                              [3 120]]}}]}]
    (add-watch store ::metrics-request-writes
               (fn [_ _ _ _]
                 (swap! write-count inc)))
    (try
      (with-redefs [system/store store]
        (@#'vm/request-metrics-computation! request-a signature-a)
        (@#'vm/request-metrics-computation! request-b signature-a)
        (@#'vm/request-metrics-computation! request-b signature-b)
        (is (= 2 @write-count))
        (is (= signature-b
               (get (deref @#'vm/last-metrics-request) :signature)))
        (is (true? (get-in @store [:portfolio-ui :metrics-loading?]))))
      (finally
        (remove-watch store ::metrics-request-writes)))))

(deftest portfolio-vm-request-metrics-computation-keeps-existing-metrics-visible-test
  (let [write-count (atom 0)
        store (atom {:portfolio-ui {:metrics-loading? false
                                    :metrics-result {:portfolio-values {:metric-status {}
                                                                        :metric-reason {}}}}})
        signature {:summary-time-range :month
                   :selected-benchmark-coins ["SPY"]
                   :strategy-source-version 101
                   :benchmark-source-versions [["SPY" 201]]}
        request-data {:portfolio-request {:strategy-cumulative-rows [[1 0]
                                                                     [2 1]]}
                      :benchmark-requests [{:coin "SPY"
                                            :request {:strategy-cumulative-rows [[1 0]
                                                                                 [2 3]]}}]}]
    (add-watch store ::metrics-request-writes
               (fn [_ _ _ _]
                 (swap! write-count inc)))
    (try
      (with-redefs [system/store store]
        (@#'vm/request-metrics-computation! request-data signature)
        (is (= 0 @write-count))
        (is (false? (get-in @store [:portfolio-ui :metrics-loading?])))
        (is (= signature
               (get (deref @#'vm/last-metrics-request) :signature))))
      (finally
        (remove-watch store ::metrics-request-writes)))))

(deftest portfolio-vm-metrics-request-signature-captures-time-range-coins-and-source-versions-test
  (let [signature-a (@#'vm/metrics-request-signature :month
                                                      ["SPY" "QQQ"]
                                                      101
                                                      {"SPY" 201
                                                       "QQQ" 301})
        signature-b (@#'vm/metrics-request-signature :week
                                                      ["SPY" "QQQ"]
                                                      101
                                                      {"SPY" 201
                                                       "QQQ" 301})
        signature-c (@#'vm/metrics-request-signature :month
                                                      ["SPY" "IWM"]
                                                      101
                                                      {"SPY" 201
                                                       "IWM" 401})
        signature-d (@#'vm/metrics-request-signature :month
                                                      ["SPY" "QQQ"]
                                                      102
                                                      {"SPY" 201
                                                       "QQQ" 301})]
    (is (= :month (:summary-time-range signature-a)))
    (is (= ["SPY" "QQQ"] (:selected-benchmark-coins signature-a)))
    (is (= [["SPY" 201] ["QQQ" 301]]
           (:benchmark-source-versions signature-a)))
    (is (not= signature-a signature-b))
    (is (not= signature-a signature-c))
    (is (not= signature-a signature-d))))

(deftest portfolio-vm-reuses-benchmark-candle-request-for-chart-and-metrics-test
  (let [original-request portfolio-actions/returns-benchmark-candle-request
        request-calls (atom 0)]
    (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                                {:spot-equity 10
                                                                 :perps-value 10
                                                                 :cross-account-value 10
                                                                 :unrealized-pnl 0})
                  portfolio-actions/returns-benchmark-candle-request (fn [summary-time-range]
                                                                        (swap! request-calls inc)
                                                                        (original-request summary-time-range))]
      (let [t0 fixture-start-ms
            t1 (+ fixture-start-ms day-ms)
            t2 (+ fixture-start-ms (* 2 day-ms))
            state {:account {:mode :classic}
                   :portfolio-ui {:summary-scope :all
                                  :summary-time-range :month
                                  :chart-tab :returns
                                  :returns-benchmark-coins ["SPY" "QQQ"]}
                   :asset-selector {:markets [{:coin "SPY"
                                               :symbol "SPY"
                                               :market-type :spot
                                               :openInterest "200"
                                               :cache-order 1}
                                              {:coin "QQQ"
                                               :symbol "QQQ"
                                               :market-type :spot
                                               :openInterest "100"
                                               :cache-order 2}]}
                   :portfolio {:summary-by-key {:month {:pnlHistory [[t0 0]
                                                                     [t1 11]
                                                                     [t2 19]]
                                                        :accountValueHistory [[t0 100]
                                                                              [t1 111]
                                                                              [t2 119]]
                                                        :vlm 0}}}
                   :candles {"SPY" {:1h [{:t t0 :c 50}
                                         {:t t1 :c 53}
                                         {:t t2 :c 57}]}
                             "QQQ" {:1h [{:t t0 :c 100}
                                         {:t t1 :c 101}
                                         {:t t2 :c 103}]}}
                   :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                             :totalVaultEquity 0}
                   :borrow-lend {:total-supplied-usd 0}}
            view-model (vm/portfolio-vm state)]
        (is (= 1 @request-calls))
        (is (= [:strategy :benchmark-0 :benchmark-1]
               (mapv :id (get-in view-model [:chart :series]))))
        (is (= ["SPY" "QQQ"]
               (get-in view-model [:performance-metrics :benchmark-coins])))))))

(deftest portfolio-vm-skips-benchmark-request-when-no-benchmarks-selected-test
  (let [original-request portfolio-actions/returns-benchmark-candle-request
        request-calls (atom 0)]
    (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                                {:spot-equity 10
                                                                 :perps-value 10
                                                                 :cross-account-value 10
                                                                 :unrealized-pnl 0})
                  portfolio-actions/returns-benchmark-candle-request (fn [summary-time-range]
                                                                        (swap! request-calls inc)
                                                                        (original-request summary-time-range))]
      (let [t0 fixture-start-ms
            t1 (+ fixture-start-ms day-ms)
            t2 (+ fixture-start-ms (* 2 day-ms))
            state {:account {:mode :classic}
                   :portfolio-ui {:summary-scope :all
                                  :summary-time-range :month
                                  :chart-tab :returns}
                   :portfolio {:summary-by-key {:month {:pnlHistory [[t0 0]
                                                                     [t1 11]
                                                                     [t2 19]]
                                                        :accountValueHistory [[t0 100]
                                                                              [t1 111]
                                                                              [t2 119]]
                                                        :vlm 0}}}
                   :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                             :totalVaultEquity 0}
                   :borrow-lend {:total-supplied-usd 0}}
            view-model (vm/portfolio-vm state)]
        (is (= 0 @request-calls))
        (is (= [:strategy]
               (mapv :id (get-in view-model [:chart :series]))))
        (is (= [] (get-in view-model [:performance-metrics :benchmark-columns])))
        (is (false? (get-in view-model [:performance-metrics :benchmark-selected?])))))))

(deftest portfolio-vm-computes-returns-history-once-per-build-test
  (let [t0 fixture-start-ms
        t1 (+ fixture-start-ms day-ms)
        t2 (+ fixture-start-ms (* 2 day-ms))
        returns-history-calls (atom 0)]
    (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                                {:spot-equity 10
                                                                 :perps-value 10
                                                                 :cross-account-value 10
                                                                 :unrealized-pnl 0})
                  portfolio-metrics/returns-history-rows (fn [_state _summary _summary-scope]
                                                           (swap! returns-history-calls inc)
                                                           [[t0 0]
                                                            [t1 11]
                                                            [t2 19]])]
      (let [state {:account {:mode :classic}
                   :portfolio-ui {:summary-scope :all
                                  :summary-time-range :month
                                  :chart-tab :returns
                                  :returns-benchmark-coins ["SPY"]}
                   :asset-selector {:markets [{:coin "SPY"
                                               :symbol "SPY"
                                               :market-type :spot
                                               :openInterest "200"
                                               :cache-order 1}]}
                   :portfolio {:summary-by-key {:month {:pnlHistory [[t0 0]
                                                                     [t1 11]
                                                                     [t2 19]]
                                                        :accountValueHistory [[t0 100]
                                                                              [t1 111]
                                                                              [t2 119]]
                                                        :vlm 0}}}
                   :candles {"SPY" {:1h [{:t t0 :c 50}
                                         {:t t1 :c 53}
                                         {:t t2 :c 57}]}}
                   :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                             :totalVaultEquity 0}
                   :borrow-lend {:total-supplied-usd 0}}
            view-model (vm/portfolio-vm state)]
        (is (= 1 @returns-history-calls))
        (is (= [:strategy :benchmark-0]
               (mapv :id (get-in view-model [:chart :series]))))
        (is (true? (get-in view-model [:performance-metrics :benchmark-selected?])))))))

(deftest portfolio-vm-non-returns-chart-tab-keeps-benchmark-metrics-and-single-request-test
  (let [original-request portfolio-actions/returns-benchmark-candle-request
        request-calls (atom 0)]
    (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                                {:spot-equity 10
                                                                 :perps-value 10
                                                                 :cross-account-value 10
                                                                 :unrealized-pnl 0})
                  portfolio-actions/returns-benchmark-candle-request (fn [summary-time-range]
                                                                        (swap! request-calls inc)
                                                                        (original-request summary-time-range))]
      (let [t0 fixture-start-ms
            t1 (+ fixture-start-ms day-ms)
            t2 (+ fixture-start-ms (* 2 day-ms))
            state {:account {:mode :classic}
                   :portfolio-ui {:summary-scope :all
                                  :summary-time-range :month
                                  :chart-tab :pnl
                                  :returns-benchmark-coins ["SPY" "QQQ"]}
                   :asset-selector {:markets [{:coin "SPY"
                                               :symbol "SPY"
                                               :market-type :spot
                                               :openInterest "200"
                                               :cache-order 1}
                                              {:coin "QQQ"
                                               :symbol "QQQ"
                                               :market-type :spot
                                               :openInterest "100"
                                               :cache-order 2}]}
                   :portfolio {:summary-by-key {:month {:pnlHistory [[t0 0]
                                                                     [t1 11]
                                                                     [t2 19]]
                                                        :accountValueHistory [[t0 100]
                                                                              [t1 111]
                                                                              [t2 119]]
                                                        :vlm 0}}}
                   :candles {"SPY" {:1h [{:t t0 :c 50}
                                         {:t t1 :c 53}
                                         {:t t2 :c 57}]}
                             "QQQ" {:1h [{:t t0 :c 100}
                                         {:t t1 :c 101}
                                         {:t t2 :c 103}]}}
                   :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                             :totalVaultEquity 0}
                   :borrow-lend {:total-supplied-usd 0}}
            view-model (vm/portfolio-vm state)]
        (is (= 1 @request-calls))
        (is (= :pnl (get-in view-model [:chart :selected-tab])))
        (is (= [:strategy]
               (mapv :id (get-in view-model [:chart :series]))))
        (is (false? (get-in view-model [:chart :benchmark-selected?])))
        (is (true? (get-in view-model [:performance-metrics :benchmark-selected?])))
        (is (= ["SPY" "QQQ"]
               (get-in view-model [:performance-metrics :benchmark-coins])))))))

(deftest portfolio-vm-chart-y-axis-uses-readable-step-ticks-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :chart-tab :pnl}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[1 0] [2 90000]]
                                                      :accountValueHistory [[1 100] [2 200]]
                                                      :vlm 10}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)]
      (is (= [90000 60000 30000 0]
             (mapv :value (get-in view-model [:chart :y-ticks]))))))))
