(ns hyperopen.vol-range.domain.volatility-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.domain.volatility :as vol]
            [hyperopen.vol-range.fixtures :as fx]))

(def now fx/now-2026-10-19-14z)
(def oct-5 (js/Date.UTC 2026 9 5))
(def oct-4 (js/Date.UTC 2026 9 4))
(def oct-12 (js/Date.UTC 2026 9 12))
(def oct-18 (js/Date.UTC 2026 9 18))
(def oct-19 (js/Date.UTC 2026 9 19))

(defn- good-rows [] (fx/daily-rows "HYPE" oct-5 14 "103" "97"))
(defn- resolve-auto [rows]
  (vol/resolve-volatility {:mode :auto :rows rows :now-ms now :coin "HYPE"}))

(deftest completed-window-test
  (let [w (vol/completed-window now 14)]
    (is (= oct-5 (:first-day-ms w)))
    (is (= oct-18 (:last-day-ms w)))
    (is (= oct-19 (:current-day-ms w)))
    (is (= 14 (count (:days w))))
    (is (= oct-18 (last (:days w))))
    (is (not (some #{oct-19} (:days w))))))

(deftest parkinson-canonical-test
  (let [r (resolve-auto (good-rows))]
    (is (= :ok (:status r)))
    (is (= "0.036044488206" (:daily-fraction r)))
    (is (= "parkinson-v1" (:estimator-version r)))
    (is (= oct-5 (get-in r [:window :first-day-ms])))
    (is (= oct-18 (get-in r [:window :last-day-ms])))
    (let [sel (vol/select-window (good-rows) {:now-ms now :coin "HYPE" :lookback-days 14})
          est (vol/parkinson-daily (:days sel))]
      (is (< (js/Math.abs (- (:raw est) 0.03604448820648275)) 1e-15))
      (is (not (:zero? est))))))

(deftest current-day-excluded-test
  (let [rows (into (good-rows) (fx/daily-rows "HYPE" oct-19 1 "500" "1"))
        r (resolve-auto rows)]
    (is (= "0.036044488206" (:daily-fraction r)))))

(deftest map-shaped-rows-test
  (is (= "0.036044488206" (:daily-fraction (resolve-auto {:rows (good-rows)})))))

(deftest missing-day-never-substituted-test
  (let [rows (->> (good-rows)
                  (remove #(= oct-12 (:t %)))
                  (concat (fx/daily-rows "HYPE" oct-4 1 "103" "97"))
                  vec)
        r (resolve-auto rows)]
    (is (= :unavailable (:status r)))
    (is (= :missing-day (:reason r)))
    (is (= oct-12 (:day-ms r)))
    (is (= "14 completed daily candles required" (:message r)))))

(deftest invalid-day-test
  (doseq [bad [{:h "abc"} {:l "0"} {:h "90"} {:l nil} {:i "1h"} {:t (+ oct-12 1000)}]]
    (let [rows (mapv #(if (= oct-12 (:t %)) (merge % bad) %) (good-rows))
          r (resolve-auto rows)]
      (is (= :unavailable (:status r)) (pr-str bad))
      (is (contains? #{:invalid-day :missing-day} (:reason r)) (pr-str bad))))
  (let [rows (mapv #(if (= oct-12 (:t %)) (assoc % :h "abc") %) (good-rows))]
    (is (= :invalid-day (:reason (resolve-auto rows))))))

(deftest duplicate-days-test
  (let [dup (first (filter #(= oct-12 (:t %)) (good-rows)))]
    (is (= :ok (:status (resolve-auto (conj (good-rows) dup)))))
    (is (= :ok (:status (resolve-auto (conj (good-rows) (assoc dup :h "103.0"))))))
    (let [r (resolve-auto (conj (good-rows) (assoc dup :h "110")))]
      (is (= :unavailable (:status r)))
      (is (= :conflicting-duplicate (:reason r)))
      (is (= oct-12 (:day-ms r))))))

(deftest numeric-and-string-keys-test
  (let [num-rows (mapv #(assoc % :h 103 :l 97) (good-rows))
        str-rows (mapv (fn [r] (into {} (map (fn [[k v]] [(name k) v])) r)) (good-rows))]
    (is (= "0.036044488206" (:daily-fraction (resolve-auto num-rows))))
    (is (= "0.036044488206" (:daily-fraction (resolve-auto str-rows))))))

(deftest other-coin-rows-ignored-test
  (let [rows (into (good-rows) (fx/daily-rows "OTHER" oct-12 1 "900" "1"))]
    (is (= "0.036044488206" (:daily-fraction (resolve-auto rows))))))

(deftest zero-volatility-test
  (let [r (resolve-auto (fx/daily-rows "HYPE" oct-5 14 "100" "100"))]
    (is (= :ok (:status r)))
    (is (= "0.000000000000" (:daily-fraction r)))
    (is (true? (:zero? r)))))

(deftest manual-test
  (is (= "0.060000000000" (:daily-fraction (vol/manual-daily-fraction "6.00"))))
  (is (= "0.001000000000" (:daily-fraction (vol/manual-daily-fraction "0.10"))))
  (is (= "0.500000000000" (:daily-fraction (vol/manual-daily-fraction "50.00"))))
  (is (= :input/manual-vol-out-of-range (:error (vol/manual-daily-fraction "0.09"))))
  (is (= :input/manual-vol-out-of-range (:error (vol/manual-daily-fraction "50.01"))))
  (is (= :input/invalid-number (:error (vol/manual-daily-fraction "abc"))))
  (let [r (vol/resolve-volatility {:mode :manual :manual-daily-pct "6.00"})]
    (is (= {:status :ok :source :manual :daily-fraction "0.060000000000"} r)))
  (is (= :invalid (:status (vol/resolve-volatility {:mode :manual :manual-daily-pct "0.09"})))))

(deftest manual-does-not-touch-history-test
  (let [rows (good-rows)
        before (resolve-auto rows)]
    (vol/resolve-volatility {:mode :manual :manual-daily-pct "6.00" :rows rows :now-ms now :coin "HYPE"})
    (is (= before (resolve-auto rows)))))

(deftest utc-open-anchor-test
  (let [rows (into (good-rows) [{:t oct-19 :T (+ oct-19 86399999) :s "HYPE" :i "1d"
                                 :o "79.5" :c "80" :h "999" :l "1" :v "1" :n 1}])
        a (vol/utc-open-anchor rows now "HYPE")]
    (is (= {:price "79.5" :observed-at-ms oct-19} a)))
  (is (nil? (vol/utc-open-anchor (good-rows) now "HYPE"))))

(deftest volatility-independent-of-anchor-test
  (testing "resolve-volatility has no anchor input; identical rows give identical results"
    (let [rows (good-rows)
          a (resolve-auto rows)
          b (vol/resolve-volatility {:mode :auto :rows rows :now-ms now :coin "HYPE"
                                     :anchor "60" :custom-anchor "60"})]
      (is (= a b))
      (is (= a (resolve-auto rows))))))
