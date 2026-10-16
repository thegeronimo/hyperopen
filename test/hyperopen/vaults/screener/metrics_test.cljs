(ns hyperopen.vaults.screener.metrics-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vaults.detail.performance :as performance]
            [hyperopen.vaults.screener.metrics :as screener-metrics]))

(def day-ms
  (* 24 60 60 1000))

(def end-ms
  (.getTime (js/Date. "2026-10-01T00:00:00.000Z")))

(defn- history
  "`days` daily points ending at `end-ms`: account value compounding by a
  wobbling daily return, pnl = account value - starting capital."
  [days]
  (let [start 100000
        values (reductions (fn [v i] (* v (+ 1.002 (* 0.004 (js/Math.sin i)))))
                           start
                           (range 1 days))
        times (map #(- end-ms (* day-ms (- days 1 %))) (range days))]
    {:accountValueHistory (mapv (fn [t v] [t (str v)]) times values)
     :pnlHistory (mapv (fn [t v] [t (str (- v start))]) times values)}))

(defn- details
  [days]
  {:portfolio {:all-time (history days)
               :month (history (min days 30))}})

(deftest window-metrics-uses-the-detail-page-pipeline-test
  (let [details* (details 200)
        {:keys [values statuses point-count]} (screener-metrics/window-metrics details* :six-month)
        rows (screener-metrics/strategy-cumulative-rows details* :six-month)
        reference (:portfolio-values
                   (performance/compute-metrics-sync
                    (performance/build-metrics-request-data rows {} [])))]
    (is (pos? point-count))
    (is (number? (:sharpe values)))
    (is (neg? (:max-drawdown values)) "the wobble produces real drawdowns")
    (is (= (:sharpe reference) (:sharpe values)))
    (is (= (:max-drawdown reference) (:max-drawdown values)))
    (is (keyword? (:sharpe statuses)))))

(deftest window-changes-the-measured-span-test
  (let [details* (details 400)
        month (screener-metrics/window-metrics details* :month)
        year (screener-metrics/window-metrics details* :one-year)]
    (is (< (:point-count month) (:point-count year)))
    (is (not= (get-in month [:values :cumulative-return])
              (get-in year [:values :cumulative-return])))))

(deftest empty-history-yields-no-values-test
  (is (= {:point-count 0 :values {} :statuses {}}
         (screener-metrics/window-metrics {:portfolio {}} :six-month))))

(deftest cacheable-portfolio-keeps-only-history-windows-test
  (is (= {:all-time {:accountValueHistory [[1 "2"]] :pnlHistory [[1 "0"]]}}
         (screener-metrics/cacheable-portfolio
          {:all-time {:accountValueHistory [[1 "2"]] :pnlHistory [[1 "0"]] :vlm "9"}
           :perp-all-time {:accountValueHistory []}}))))

(deftest short-history-is-flagged-by-point-count-test
  (testing "five days of history leaves too few points for a 20-point minimum"
    (is (< (:point-count (screener-metrics/window-metrics (details 5) :six-month)) 20))))

(deftest a-vault-that-never-falls-has-zero-drawdown-test
  (let [t0 (- end-ms (* 100 day-ms))
        rising {:accountValueHistory (mapv (fn [i] [(+ t0 (* i day-ms)) (str (+ 100000 (* 50 i)))]) (range 100))
                :pnlHistory (mapv (fn [i] [(+ t0 (* i day-ms)) (str (* 50 i))]) (range 100))}
        {:keys [values statuses]} (screener-metrics/window-metrics {:portfolio {:all-time rising}} :three-month)]
    (is (= 0 (:max-drawdown values)))
    (is (= 0 (:longest-dd-days values)))
    (is (= :ok (:max-drawdown statuses)))))
