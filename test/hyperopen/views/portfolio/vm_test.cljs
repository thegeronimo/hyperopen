(ns hyperopen.views.portfolio.vm-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.account-equity-view :as account-equity-view]
            [hyperopen.views.portfolio.vm :as vm]))

(def ^:private day-ms
  (* 24 60 60 1000))

(deftest volume-14d-usd-uses-last-14-days-when-timestamps-available-test
  (let [now (.now js/Date)
        within (- now (* 2 day-ms))
        outside (- now (* 30 day-ms))
        state {:orders {:fills [{:time within :sz "2" :px "100"}
                                {:time outside :sz "5" :px "100"}]}}]
    (is (= 200 (vm/volume-14d-usd state)))))

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
      (is (= :pnl (get-in view-model [:chart :selected-tab])))
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
        (is (= "30D" (get-in view-model [:selectors :summary-time-range :label]))))
      (testing "missing data fallback behavior"
        (is (= 0 (get-in view-model [:summary :pnl])))
        (is (= 0 (get-in view-model [:summary :volume])))
        (is (nil? (get-in view-model [:summary :max-drawdown-pct])))
        (is (= :pnl (get-in view-model [:chart :selected-tab])))
        (is (empty? (get-in view-model [:chart :points])))
        (is (= [{:value 3 :y-ratio 0}
                {:value 2 :y-ratio (/ 1 3)}
                {:value 1 :y-ratio (/ 2 3)}
                {:value 0 :y-ratio 1}]
               (get-in view-model [:chart :y-ticks])))))))

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
      (is (= ["Account Value" "PNL" "Returns"]
             (mapv :label (get-in view-model [:chart :tabs])))))))

(deftest portfolio-vm-returns-tab-uses-flow-adjusted-time-weighted-returns-to-avoid-cashflow-spikes-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :chart-tab :returns}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[1 0] [2 0] [3 -1] [4 -2]]
                                                      :accountValueHistory [[1 4] [2 205] [3 204] [4 205]]
                                                      :vlm 10}}
                             :ledger-updates [{:time 2
                                               :hash "0xabc"
                                               :delta {:type "deposit"
                                                       :usdc "201.0"}}]}
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

(deftest portfolio-vm-returns-tab-treats-account-class-transfer-as-flow-for-perps-scope-test
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
                                                           :vlm 10}}
                             :ledger-updates [{:time 2
                                               :hash "0xdef"
                                               :delta {:type "accountClassTransfer"
                                                       :usdc "50"
                                                       :toPerp true}}]}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)]
      (is (= :returns (get-in view-model [:chart :selected-tab])))
      (is (= [0 0 0]
             (mapv :value (get-in view-model [:chart :points])))))))

(deftest portfolio-vm-returns-tab-keeps-distinct-same-hash-ledger-events-for-flow-adjustment-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :chart-tab :returns}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[1 0] [2 0]]
                                                      :accountValueHistory [[1 100] [2 200]]
                                                      :vlm 10}}
                             :ledger-updates [{:time 2
                                               :hash "0xshared"
                                               :delta {:type "deposit"
                                                       :usdc "100"}}]}
                 :orders {:ledger [{:time 2
                                    :hash "0xshared"
                                    :delta {:type "withdraw"
                                            :usdc "50"
                                            :fee "0"}}]}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)]
      (is (= :returns (get-in view-model [:chart :selected-tab])))
      (is (= [0 50]
             (mapv :value (get-in view-model [:chart :points])))))))

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
             (mapv :value (get-in view-model [:chart :y-ticks])))))))
