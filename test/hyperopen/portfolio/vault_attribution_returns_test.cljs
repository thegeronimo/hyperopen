(ns hyperopen.portfolio.vault-attribution-returns-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.vault-attribution :as attribution]
            [hyperopen.portfolio.vault-attribution.model :as model]
            [hyperopen.portfolio.vault-attribution.test-fixtures :as fx]))

(def ^:private now fx/now)
(def ^:private day fx/day)
(def ^:private near? fx/near?)

(defn- final-return-pct
  [entry]
  (second (peek (fx/all-time-returns entry))))

(deftest large-mid-way-deposit-keeps-real-return-test
  (let [start (- now (* 100 day))
        mid (+ start (* 50 day))
        index (fn [t]
                (if (<= t mid)
                  (+ 1 (* 0.1 (/ (- t start) (* 50 day))))
                  (* 1.1 (+ 1 (* 0.05 (/ (- t mid) (* 50 day)))))))
        result (attribution/attribution
                {:ledger-rows [(fx/deposit start fx/vault-a "1000")
                               (fx/deposit mid fx/vault-a "100000")]
                 :equity-by-vault {fx/vault-a 106155}
                 :vaults {fx/vault-a {:returns-candidates [(fx/index-rows start now day index)]}}
                 :now-ms now})
        entry (get-in result [:summary-entries :vault-all-time])]
    (is (near? 5155 (get-in result [:totals :pnl])))
    (is (near? 15.5 (final-return-pct entry) 1e-6))
    (testing "the month window carries only the second leg"
      (let [month (get-in result [:summary-entries :vault-month])
            expected (* 100 (- (/ 1.155 (index (- now (* 30 day)))) 1))]
        (is (near? expected (final-return-pct month) 1e-6))))
    (fx/assert-series-invariants result)))

(deftest drift-spreads-by-capital-time-test
  (let [start (- now (* 100 day))
        mid (+ start (* 50 day))
        result (attribution/attribution
                {:ledger-rows [(fx/deposit start fx/vault-a "10")
                               (fx/deposit mid fx/vault-a "1000000")]
                 :equity-by-vault {fx/vault-a 995010}
                 :vaults {fx/vault-a {:returns-candidates [(fx/index-rows start now day (constantly 1))]}}
                 :now-ms now})
        series (:series result)
        final-pct (final-return-pct (get-in result [:summary-entries :vault-all-time]))]
    (is (every? #(>= (:value %) 0) series))
    (is (near? 9.95 (:value (some #(when (= mid (:time-ms %)) %) series)) 1e-3))
    (is (< -1.01 final-pct -0.98) (str final-pct))
    (fx/assert-series-invariants result)))

(deftest irr-known-cases-test
  (is (near? 0.1 (model/irr [[0 -1000] [model/ms-per-year 1100]]) 1e-9))
  (let [x (/ (+ -1 (js/Math.sqrt 9.8)) 2)]
    (is (near? (- (* x x) 1)
               (model/irr [[0 -1000] [(/ model/ms-per-year 2) -1000] [model/ms-per-year 2200]])
               1e-9)))
  (is (near? -0.2 (model/irr [[0 -1000] [model/ms-per-year 800]]) 1e-9))
  (is (= -1 (model/irr [[0 -1000] [10 -5]])) "money in, nothing back: total loss")
  (is (nil? (model/irr [])))
  (is (nil? (model/irr [[nil -1] ["x" 2] [js/NaN 3]])))
  (testing "through attribution"
    (let [start (- now model/ms-per-year)
          result (attribution/attribution
                  {:ledger-rows [(fx/deposit start fx/vault-a "1000")]
                   :equity-by-vault {fx/vault-a 1100}
                   :now-ms now})]
      (is (near? 0.1 (get-in result [:vaults 0 :money-weighted-return]) 1e-9))
      (is (near? 0.1 (get-in result [:totals :money-weighted-return]) 1e-9))))
  (testing "nil when held less than a day"
    (let [result (attribution/attribution
                  {:ledger-rows [(fx/deposit (- now 3600000) fx/vault-a "1000")]
                   :equity-by-vault {fx/vault-a 1001}
                   :now-ms now})]
      (is (nil? (get-in result [:vaults 0 :money-weighted-return])))
      (is (nil? (get-in result [:totals :money-weighted-return]))))))

(deftest window-entries-test
  (let [start (- now (* 100 day))
        index #(+ 1 (* 0.001 (/ (- % start) day)))
        result (attribution/attribution
                {:ledger-rows [(fx/deposit start fx/vault-a "1000")]
                 :equity-by-vault {fx/vault-a 1100}
                 :vaults {fx/vault-a {:returns-candidates
                                      [(fx/index-rows (- now day) now 3600000 index)
                                       (fx/index-rows start now (* 12 day) index)]}}
                 :now-ms now})
        entries (:summary-entries result)]
    (is (= #{:vault-day :vault-week :vault-month :vault-all-time} (set (keys entries))))
    (doseq [[k days] {:vault-day 1 :vault-week 7 :vault-month 30}]
      (let [{:keys [accountValueHistory pnlHistory]} (get entries k)
            window-start (- now (* days day))]
        (testing (name k)
          (is (= (mapv first accountValueHistory) (mapv first pnlHistory)))
          (is (= window-start (ffirst accountValueHistory)))
          (is (= now (first (peek accountValueHistory))))
          (is (= 0 (second (first pnlHistory))) "P&L rebased to 0 at window start")
          (is (near? 1100 (second (peek accountValueHistory))))
          (is (near? (* 1000 (- (index now) (index window-start)))
                     (second (peek pnlHistory))
                     1e-6)))))
    (is (= start (ffirst (get-in entries [:vault-all-time :accountValueHistory]))))
    (testing "a position younger than the window starts at its first deposit"
      (let [young (- now (* 3 day))
            result (attribution/attribution
                    {:ledger-rows [(fx/deposit young fx/vault-a "500")]
                     :equity-by-vault {fx/vault-a 510}
                     :now-ms now})
            week (get-in result [:summary-entries :vault-week])]
        (is (= young (ffirst (:accountValueHistory week))))
        (is (near? 10 (second (peek (:pnlHistory week)))))))))

(deftest stitched-candidates-follow-the-true-index-test
  (let [origin (- now (* 200 day))
        start (- now (* 100 day))
        index #(+ 1 (* 0.001 (/ (- % origin) day)))
        equity (* 1000 (/ (index now) (index start)))
        result (attribution/attribution
                {:ledger-rows [(fx/deposit start fx/vault-a "1000")]
                 :equity-by-vault {fx/vault-a equity}
                 :vaults {fx/vault-a {:returns-candidates
                                      [(fx/index-rows (- now day) now 3600000 index)
                                       (fx/index-rows (- now (* 7 day)) now 14400000 index)
                                       (fx/index-rows (- now (* 30 day)) now day index)
                                       (fx/index-rows origin now (* 12 day) index)]}}
                 :now-ms now})
        row (get-in result [:vaults 0])
        series (:series result)
        value-at (fn [t] (:value (some #(when (= t (:time-ms %)) %) series)))]
    (is (near? (* 100 (- (/ (index now) (index start)) 1)) (:vault-return-while-held-pct row)))
    (doseq [t [(- now (* 15 day)) (- now (* 3 day)) (- now 7200000)]]
      (is (near? (* 1000 (/ (index t) (index start))) (value-at t) 1e-6) (str t)))
    (is (= (- now (* 30 day)) (:daily-from-ms result)))
    (fx/assert-series-invariants result)))

(deftest one-vault-while-held-equals-the-sleeve-time-weighted-return-test
  (let [start (- now (* 200 day))
        index #(+ 1 (* 0.002 (/ (- % start) day)))
        result (attribution/attribution
                {:ledger-rows [(fx/deposit start fx/vault-a "1000")
                               (fx/deposit (+ start (* 40 day)) fx/vault-a "4000")
                               (fx/withdraw (+ start (* 80 day)) fx/vault-a
                                            {:requested "3000" :commission "30"
                                             :basis "2500" :net "2970"})]
                 :equity-by-vault {fx/vault-a 2600}
                 :vaults {fx/vault-a {:returns-candidates
                                      [(fx/index-rows (- now (* 30 day)) now day index)
                                       (fx/index-rows start now (* 12 day) index)]}}
                 :now-ms now})
        row (first (:vaults result))
        total (final-return-pct (get-in result [:summary-entries :vault-all-time]))
        index-based (* 100 (- (/ (index now) (index start)) 1))]
    (is (number? total))
    (is (near? total (:vault-return-while-held-pct row)) "row == All vaults for one vault")
    (is (> (js/Math.abs (- index-based total)) 1) "the raw index reads differently")))
