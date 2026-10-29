(ns hyperopen.views.portfolio.vm.history-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.views.portfolio.vm :as vm]
            [hyperopen.views.account-equity-view :as account-equity-view]))

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
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}}
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
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)]
      (is (= :returns (get-in view-model [:chart :selected-tab])))
      (is (= [1 4]
             (mapv :time-ms (get-in view-model [:chart :points]))))
      (is (= [0 18.18]
             (mapv :value (get-in view-model [:chart :points])))))))


