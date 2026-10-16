(ns hyperopen.portfolio.optimizer.application.tear-sheet-backtest-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.application.tear-sheet.backtest :as backtest]))

(def ^:private day backtest/day-ms)

(def ^:private t0 (* 20000 day))

(defn- growth-rows
  "Daily rows from day `from` to day `to` growing `rate` per day."
  [from to rate]
  (mapv (fn [d] {:time-ms (+ t0 (* d day)) :close (* 100 (js/Math.pow (+ 1 rate) d))})
        (range from (inc to))))

(defn- final-pct
  [result]
  (second (peek (:rows result))))

(defn- approx=
  [a b]
  (< (js/Math.abs (- a b)) 1e-6))

(deftest daily-grid-test
  (is (= [t0 (+ t0 day) (+ t0 (* 2 day))] (backtest/daily-grid t0 (+ t0 (* 2 day)))))
  (is (= [t0 (+ t0 day) (+ t0 day 3600000)] (backtest/daily-grid (+ t0 5) (+ t0 day 3600000)))
      "a partial last day ends on the end time")
  (is (nil? (backtest/daily-grid t0 t0))))

(deftest value-at-interpolates-log-linearly-test
  (let [rows [{:time-ms 0 :close 100} {:time-ms 10 :close 400}]]
    (is (nil? (backtest/value-at rows -1)) "not live before its first sample")
    (is (approx= 200 (backtest/value-at rows 5)) "geometric midpoint")
    (is (= 400 (backtest/value-at rows 99)) "holds its last value")))

(deftest two-legs-daily-rebalance-test
  (let [a (growth-rows 0 10 0.01)
        b (growth-rows 0 10 0)
        result (backtest/run {:legs [{:id "a" :weight 0.5 :rows a} {:id "b" :weight 0.5 :rows b}]
                              :start-ms t0 :end-ms (+ t0 (* 10 day))
                              :rebalance :daily :missing :spread})]
    (is (= 11 (count (:rows result))))
    (is (approx= (* 100 (- (js/Math.pow 1.005 10) 1)) (final-pct result))
        "half in a 1%/day leg, rebalanced daily, compounds at 0.5%/day")
    (is (= [] (:joins result)))
    (is (pos? (get-in result [:contribution "a"])))
    (is (approx= 0 (get-in result [:contribution "b"])))))

(deftest hold-drifts-test
  (let [a (growth-rows 0 10 0.01)
        b (growth-rows 0 10 0)
        result (backtest/run {:legs [{:id "a" :weight 0.5 :rows a} {:id "b" :weight 0.5 :rows b}]
                              :start-ms t0 :end-ms (+ t0 (* 10 day))
                              :rebalance :hold :missing :spread})]
    (is (approx= (* 100 0.5 (- (js/Math.pow 1.01 10) 1)) (final-pct result))
        "buy and hold: half the leg's own growth")))

(deftest missing-leg-rules-test
  (let [a (growth-rows 0 10 0.01)
        late (growth-rows 5 10 0)
        legs [{:id "a" :weight 0.5 :rows a} {:id "late" :weight 0.5 :rows late}]
        base {:legs legs :start-ms t0 :end-ms (+ t0 (* 10 day)) :rebalance :daily}
        spread (backtest/run (assoc base :missing :spread))
        cash (backtest/run (assoc base :missing :cash))
        common (backtest/run (assoc base :missing :common))]
    (testing "spread: the live leg holds all the weight until the late one starts"
      (is (approx= (* 100 (- (* (js/Math.pow 1.01 5) (js/Math.pow 1.005 5)) 1)) (final-pct spread)))
      (is (= [{:id "late" :time-ms (+ t0 (* 5 day))}] (:joins spread)))
      (is (= [1 1 1 1 1 2 2 2 2 2 2] (mapv second (:live-counts spread)))))
    (testing "cash: the missing weight earns nothing"
      (is (approx= (* 100 (- (js/Math.pow 1.005 10) 1)) (final-pct cash))))
    (testing "common: the window starts when every leg exists"
      (is (= (+ t0 (* 5 day)) (:start-ms common)))
      (is (= 6 (count (:rows common))))
      (is (approx= (* 100 (- (js/Math.pow 1.005 5) 1)) (final-pct common))))))

(deftest profit-share-on-gains-above-high-water-test
  (let [a (growth-rows 0 10 0.01)
        base {:legs [{:id "a" :weight 1 :rows a :fee-rate 0.1}]
              :start-ms t0 :end-ms (+ t0 (* 10 day)) :rebalance :hold :missing :spread}
        gross (backtest/run base)
        net (backtest/run (assoc base :profit-share? true))]
    (is (approx= (* 100 (- (js/Math.pow 1.01 10) 1)) (final-pct gross)))
    (is (approx= (* 0.9 (final-pct gross)) (final-pct net))
        "a leg that only rises keeps 90% of its gain")))

(deftest nothing-to-backtest-test
  (is (nil? (backtest/run {:legs [] :start-ms t0 :end-ms (+ t0 day)})))
  (is (nil? (backtest/run {:legs [{:id "a" :weight 1 :rows (growth-rows 20 30 0.01)}]
                           :start-ms t0 :end-ms (+ t0 (* 10 day))}))
      "no leg has history in the window"))

(deftest drawdown-rows-test
  (is (= [[1 0] [2 0] [3 -50] [4 0]]
         (mapv (fn [[t v]] [t (js/Math.round v)])
               (backtest/drawdown-rows [[1 0] [2 100] [3 0] [4 200]])))))

(deftest median-gap-days-test
  (is (= 1 (backtest/median-gap-days (growth-rows 0 10 0) t0 (+ t0 (* 10 day)))))
  (is (= 5 (backtest/median-gap-days (take-nth 5 (growth-rows 0 20 0)) t0 (+ t0 (* 20 day))))
      "a sample every 5 days")
  (is (nil? (backtest/median-gap-days (growth-rows 0 10 0) (+ t0 (* 50 day)) (+ t0 (* 60 day))))
      "no samples in the window"))
