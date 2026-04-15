(ns hyperopen.views.portfolio.vm.history-helpers-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio.vm.history :as vm-history]))

(defn- utc-month-offset
  [time-ms months]
  (let [date (js/Date. time-ms)]
    (.setUTCMonth date (+ (.getUTCMonth date) months))
    (.getTime date)))

(defn- utc-year-offset
  [time-ms years]
  (let [date (js/Date. time-ms)]
    (.setUTCFullYear date (+ (.getUTCFullYear date) years))
    (.getTime date)))

(defn- approx=
  [a b]
  (< (js/Math.abs (- a b)) 1e-9))

(deftest summary-window-cutoff-and-scope-selection-cover-all-ranges-test
  (let [last-ms (.getTime (js/Date. "2026-02-01T00:00:00.000Z"))]
    (is (= (- last-ms (* 24 60 60 1000))
           (vm-history/summary-window-cutoff-ms :day last-ms)))
    (is (= (- last-ms (* 7 24 60 60 1000))
           (vm-history/summary-window-cutoff-ms :week last-ms)))
    (is (= (- last-ms (* 30 24 60 60 1000))
           (vm-history/summary-window-cutoff-ms :month last-ms)))
    (is (= (utc-month-offset last-ms -3)
           (vm-history/summary-window-cutoff-ms :three-month last-ms)))
    (is (= (utc-month-offset last-ms -6)
           (vm-history/summary-window-cutoff-ms :six-month last-ms)))
    (is (= (utc-year-offset last-ms -1)
           (vm-history/summary-window-cutoff-ms :one-year last-ms)))
    (is (= (utc-year-offset last-ms -2)
           (vm-history/summary-window-cutoff-ms :two-year last-ms)))
    (is (nil? (vm-history/summary-window-cutoff-ms :all-time last-ms)))
    (is (nil? (vm-history/summary-window-cutoff-ms :unknown last-ms)))
    (is (nil? (vm-history/summary-window-cutoff-ms :day "bad")))
    (is (= :all (vm-history/range-all-time-key :all-time)))
    (is (= :month (vm-history/range-all-time-key :month))))
  (is (= [{:time-ms 1 :value 10}]
         (vm-history/account-value-history-rows {:accountValueHistory [{:time-ms 1 :value 10}]})))
  (is (= [] (vm-history/account-value-history-rows {})))
  (is (= [[1 2]] (vm-history/pnl-history-rows {:pnlHistory [[1 2]]})))
  (is (= [] (vm-history/pnl-history-rows {}))))

(deftest normalized-history-and-windowing-handle-invalid-and-rebase-branches-test
  (let [rows [{:time-ms 3 :value "15"}
              [1 "5"]
              {:t 2 :pnl "10"}
              {:time-ms js/NaN :value 4}
              ["bad" 1]]
        normalized (vm-history/normalized-history-rows rows)]
    (is (= [{:time-ms 1 :value 5}
            {:time-ms 2 :value 10}
            {:time-ms 3 :value 15}]
           normalized))
    (is (= [{:time-ms 2 :value 10}
            {:time-ms 3 :value 15}]
           (vm-history/history-window-rows normalized 2)))
    (is (= normalized
           (vm-history/history-window-rows normalized nil)))
    (is (= [{:time-ms 1 :value 0}
            {:time-ms 2 :value 5}
            {:time-ms 3 :value 10}]
           (vm-history/rebase-history-rows normalized 5)))
    (is (= normalized
           (vm-history/rebase-history-rows normalized js/NaN)))))

(deftest pnl-candle-and-benchmark-point-paths-cover-value-shapes-test
  (is (= 20
         (vm-history/pnl-delta {:pnlHistory [[1 10] [2 30]]})))
  (is (= 0
         (vm-history/pnl-delta {:pnlHistory []})))
  (is (nil? (vm-history/pnl-delta {:pnlHistory [[1 "bad"] [2 3]]})))
  (is (= 12 (vm-history/candle-point-close {:c "12"})))
  (is (= 14 (vm-history/candle-point-close {:close "14"})))
  (is (= 16 (vm-history/candle-point-close [0 0 0 0 "16"])))
  (is (nil? (vm-history/candle-point-close [0 0 0 0 "bad"])))
  (is (nil? (vm-history/candle-point-close :bad)))
  (is (= [{:time-ms 1 :value 10}
          {:time-ms 3 :value 12}]
         (vm-history/benchmark-candle-points [{:t 1 :c "10"}
                                              {:t 2 :c "0"}
                                              [3 0 0 0 "12"]
                                              [4 0 0 0 "bad"]]))))

(deftest benchmark-candle-points-unwrap-wrapped-containers-and-dedupe-last-write-wins-test
  (is (= [{:time-ms 1 :value 12}
          {:time-ms 2 :value 20}]
         (vm-history/benchmark-candle-points {:rows [{:t 1 :c "10"}
                                                     {:t 1 :c "12"}
                                                     {:t 2 :c "20"}]})))
  (is (= [{:time-ms 3 :value 32}
          {:time-ms 4 :value 40}]
         (vm-history/benchmark-candle-points {:data [[4 0 0 0 "40"]
                                                     [3 0 0 0 "30"]
                                                     [3 0 0 0 "32"]]})))
  (is (= [{:time-ms 5 :value 55}]
         (vm-history/benchmark-candle-points {:candles [{:t 5 :close "50"}
                                                        {:t 5 :close "55"}]}))))

(deftest aligned-benchmark-return-rows-cover-anchor-and-fallback-branches-test
  (is (= []
         (vm-history/aligned-benchmark-return-rows [] [{:time-ms 1}])))
  (is (= []
         (vm-history/aligned-benchmark-return-rows [{:time-ms 1 :value 100}] [])))
  (let [aligned (vm-history/aligned-benchmark-return-rows [{:time-ms 1000 :value 100}
                                                           {:time-ms 2000 :value 110}
                                                           {:time-ms 3000 :value 121}]
                                                          [{:time-ms 1500}
                                                           {:time-ms 2500}
                                                           {:time-ms 3500}])]
    (is (= [1500 2500 3500] (mapv :time-ms aligned)))
    (is (approx= 0 (get-in aligned [0 :value])))
    (is (approx= 10 (get-in aligned [1 :value])))
    (is (approx= 21 (get-in aligned [2 :value]))))
  (let [aligned (vm-history/aligned-benchmark-return-rows [{:time-ms 10 :value 50}
                                                           {:time-ms 11 :value 55}]
                                                          [{:time-ms 9}
                                                           {:time-ms 10}
                                                           {:time-ms 11}])]
    (is (= [10 11] (mapv :time-ms aligned)))
    (is (approx= 0 (get-in aligned [0 :value])))
    (is (approx= 10 (get-in aligned [1 :value]))))
  (is (= []
         (vm-history/aligned-benchmark-return-rows [{:time-ms 1 :value 0}
                                                    {:time-ms 2 :value 5}]
                                                   [{:time-ms 1}
                                                    {:time-ms 2}])))
  (is (= [{:time-ms 1 :value 0} {:time-ms 2 :value 12}]
         (vm-history/cumulative-return-time-points [[1 0] [2 12]]))))

(deftest aligned-benchmark-return-rows-honors-explicit-anchor-time-before-late-strategy-start-test
  (let [t2 (.getTime (js/Date. "2026-04-14T00:00:00.000Z"))
        t0 (vm-history/summary-window-cutoff-ms :one-year t2)
        t1 (.getTime (js/Date. "2025-10-14T00:00:00.000Z"))
        aligned (vm-history/aligned-benchmark-return-rows [{:time-ms t0 :value 100}
                                                           {:time-ms t1 :value 86}
                                                           {:time-ms t2 :value 88}]
                                                          [{:time-ms t1}
                                                           {:time-ms t2}]
                                                          t0)]
    (is (= [t1 t2] (mapv :time-ms aligned)))
    (is (approx= -14 (get-in aligned [0 :value])))
    (is (approx= -12 (get-in aligned [1 :value])))))

(deftest benchmark-market-return-rows-preserve-dense-candle-path-with-explicit-window-test
  (let [t0 (.getTime (js/Date. "2025-04-14T00:00:00.000Z"))
        t1 (.getTime (js/Date. "2025-07-14T00:00:00.000Z"))
        t2 (.getTime (js/Date. "2025-10-14T00:00:00.000Z"))
        t3 (.getTime (js/Date. "2026-01-14T00:00:00.000Z"))
        t4 (.getTime (js/Date. "2026-04-14T00:00:00.000Z"))
        rows (vm-history/benchmark-market-return-rows [{:time-ms t0 :value 100}
                                                       {:time-ms t1 :value 94}
                                                       {:time-ms t2 :value 90}
                                                       {:time-ms t3 :value 86}
                                                       {:time-ms t4 :value 88}]
                                                      {:anchor-time-ms t0
                                                       :end-time-ms t4})]
    (is (= [t0 t1 t2 t3 t4]
           (mapv :time-ms rows)))
    (is (every? true?
                (map approx=
                     [0 -6 -10 -14 -12]
                     (mapv :value rows))))))
