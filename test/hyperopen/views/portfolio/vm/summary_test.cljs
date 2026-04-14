(ns hyperopen.views.portfolio.vm.summary-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.views.portfolio.vm :as vm]
            [hyperopen.views.account-equity-view :as account-equity-view]))

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

(deftest portfolio-vm-exposes-summary-source-metadata-when-requested-range-falls-back-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 0
                                                               :perps-value 0
                                                               :cross-account-value 0
                                                               :unrealized-pnl 0})]
    (let [state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :chart-tab :pnl}
                 :portfolio {:summary-by-key {:week {:pnlHistory [[1 2] [2 7]]
                                                     :accountValueHistory [[1 100] [2 104]]
                                                     :vlm 11}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 0}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)]
      (is (= :month (get-in view-model [:summary :selected-key])))
      (is (= :month (get-in view-model [:summary :requested-key])))
      (is (= :week (get-in view-model [:summary :effective-key])))
      (is (= :week (get-in view-model [:summary :source-key])))
      (is (= :fallback (get-in view-model [:summary :source]))))))

