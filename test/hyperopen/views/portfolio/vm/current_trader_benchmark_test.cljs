(ns hyperopen.views.portfolio.vm.current-trader-benchmark-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.views.account-equity-view :as account-equity-view]
            [hyperopen.views.portfolio.vm :as vm]
            [hyperopen.views.portfolio.vm.benchmarks :as vm-benchmarks]))

(def ^:private day-ms
  (* 24 60 60 1000))

(def ^:private fixture-start-ms
  (.getTime (js/Date. "2024-01-01T00:00:00.000Z")))

(def ^:private trader-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdef1234")

(def ^:private trader-benchmark
  (str "trader:" trader-address))

(defn- approx=
  [left right]
  (< (js/Math.abs (- left right)) 1e-9))

(use-fixtures :each
  (fn [f]
    (vm/reset-portfolio-vm-cache!)
    (f)
    (vm/reset-portfolio-vm-cache!)))

(deftest portfolio-vm-reuses-active-summary-for-current-trader-benchmark-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [t0 fixture-start-ms
          t1 (+ fixture-start-ms day-ms)
          t2 (+ fixture-start-ms (* 2 day-ms))
          active-summary {:pnlHistory [[t0 0]
                                       [t1 10]
                                       [t2 25]]
                          :accountValueHistory [[t0 100]
                                                [t1 110]
                                                [t2 125]]
                          :vlm 10}
          stale-benchmark-summary {:pnlHistory [[t0 0]
                                                [t1 100]
                                                [t2 300]]
                                   :accountValueHistory [[t0 100]
                                                         [t1 200]
                                                         [t2 400]]
                                   :vlm 0}
          state {:account {:mode :classic}
                 :account-context {:spectate-mode {:active? true
                                                    :address trader-address}}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :chart-tab :returns
                                :returns-benchmark-coins [trader-benchmark]
                                :returns-benchmark-coin trader-benchmark}
                 :portfolio {:summary-by-key {:month active-summary}
                             :trader-benchmarks-by-address {trader-address
                                                            {:summary-by-key {:month stale-benchmark-summary}}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)
          benchmark-series (->> (get-in view-model [:chart :series])
                                (filter #(= :benchmark-0 (:id %)))
                                first)
          direct-benchmark-rows (get (vm-benchmarks/benchmark-cumulative-return-rows-by-coin
                                      state
                                      :all
                                      :month
                                      [trader-benchmark]
                                      [[t0 0] [t1 10] [t2 25]]
                                      t0
                                      t2)
                                     trader-benchmark)]
      (is (= [t0 t1 t2]
             (mapv first direct-benchmark-rows)))
      (is (every? true?
                  (map approx=
                       [0 10 25]
                       (mapv second direct-benchmark-rows))))
      (is (= [0 10 25]
             (mapv :value (:points benchmark-series))))
      (is (= (mapv :value (get-in view-model [:chart :points]))
             (mapv :value (:points benchmark-series)))))))

(deftest portfolio-vm-invalidates-current-trader-benchmark-cache-on-account-change-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [t0 fixture-start-ms
          t1 (+ fixture-start-ms day-ms)
          t2 (+ fixture-start-ms (* 2 day-ms))
          owner-summary {:pnlHistory [[t0 0]
                                      [t1 -10]
                                      [t2 -5]]
                         :accountValueHistory [[t0 100]
                                               [t1 90]
                                               [t2 95]]
                         :vlm 10}
          trader-summary {:pnlHistory [[t0 0]
                                       [t1 100]
                                       [t2 300]]
                          :accountValueHistory [[t0 100]
                                                [t1 200]
                                                [t2 400]]
                          :vlm 0}
          base-state {:account {:mode :classic}
                      :wallet {:address "0xffffffffffffffffffffffffffffffffffffffff"}
                      :portfolio-ui {:summary-scope :all
                                     :summary-time-range :month
                                     :chart-tab :returns
                                     :returns-benchmark-coins [trader-benchmark]
                                     :returns-benchmark-coin trader-benchmark}
                      :portfolio {:summary-by-key {:month owner-summary}
                                  :trader-benchmarks-by-address {trader-address
                                                                 {:summary-by-key {:month trader-summary}}}}
                      :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}}
                      :borrow-lend {:total-supplied-usd 0}}
          owner-model (vm/portfolio-vm base-state)
          spectate-model (vm/portfolio-vm
                          (assoc base-state
                                 :account-context {:spectate-mode {:active? true
                                                                    :address trader-address}}))
          owner-benchmark (->> (get-in owner-model [:chart :series])
                               (filter #(= :benchmark-0 (:id %)))
                               first)
          spectate-benchmark (->> (get-in spectate-model [:chart :series])
                                  (filter #(= :benchmark-0 (:id %)))
                                  first)]
      (is (= [0 100 300]
             (mapv :value (:points owner-benchmark))))
      (is (= (mapv :value (get-in spectate-model [:chart :points]))
             (mapv :value (:points spectate-benchmark)))))))
