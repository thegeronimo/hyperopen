(ns hyperopen.portfolio.optimizer.application.vault-history-window-test
  "Which window of a vault's Hyperliquid history the optimizer uses, and the
  own-history recommendation for vaults too young for any lane-ready window.

  Hyperliquid spreads a fixed ~40-90 point budget over a vault's whole life, so
  the one-year clip suits only 1-2 year old vaults. Younger vaults used to fall
  through to the 31-day month series and older ones kept <30 samples - both
  then needed a proxy the user cannot name for a strategy."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.application.default-assumptions :as default-assumptions]
            [hyperopen.portfolio.optimizer.application.history-loader.normalization :as normalization]))

(def ^:private day-ms (* 24 60 60 1000))

(def ^:private now-ms (.getTime (js/Date. "2026-10-16T00:00:00.000Z")))

(defn- summary
  "`n` samples `gap-days` apart ending at now, account value wobbling upward."
  [n gap-days]
  (let [times (mapv #(- now-ms (* day-ms gap-days (- n 1 %))) (range n))
        values (mapv #(* 100000 (+ 1 (* 0.002 %) (* 0.01 (js/Math.sin %)))) (range n))]
    {:accountValueHistory (mapv (fn [t v] [t (str v)]) times values)
     :pnlHistory (mapv (fn [t v] [t (str (- v 100000))]) times values)}))

(defn- first-candidate
  [portfolio]
  (first (normalization/vault-history-candidates {:portfolio portfolio})))

(deftest young-vault-uses-its-full-history-test
  (testing "230 days old: no complete year, so all-time beats the 31-day month"
    (let [{:keys [source history]} (first-candidate {:all-time (summary 42 5.5)
                                                     :month (summary 47 0.66)})]
      (is (= :sparse-two-year source))
      (is (= 42 (count history))))))

(deftest old-vault-uses-a-two-year-window-test
  (testing "3.5 years of ~87 samples: a 1y clip keeps <30, two years keeps enough"
    (let [{:keys [source history]} (first-candidate {:all-time (summary 87 14.7)
                                                     :month (summary 47 0.66)})]
      (is (= :sparse-two-year source))
      (is (>= (count history) 31)))))

(deftest one-to-two-year-vault-keeps-the-one-year-clip-test
  (testing "a lane-ready one-year clip keeps priority (unchanged behaviour)"
    (let [{:keys [source]} (first-candidate {:all-time (summary 66 9)
                                             :month (summary 47 0.66)})]
      (is (= :derived-one-year source)))))

(deftest brand-new-vault-still-falls-back-to-the-month-test
  (testing "60 days old: nothing lane-ready, the short window remains"
    (let [{:keys [source]} (first-candidate {:all-time (summary 40 1.5)
                                             :month (summary 30 1)})]
      (is (not= :sparse-two-year source))
      (is (not= :sparse-all-time source)))))

(deftest annualized-volatility-of-irregular-rows-test
  (let [rows (mapv (fn [i] {:time-ms (* i day-ms)
                            :close (if (even? i) 100 101)})
                   (range 11))
        vol (default-assumptions/annualized-volatility rows)]
    (is (< 0.18 vol 0.20) "a 1% daily zig-zag is ~19% a year")
    (is (nil? (default-assumptions/annualized-volatility (take 3 rows)))
        "too few intervals to quote")))

(deftest own-history-recommendation-test
  (let [rec (default-assumptions/vault-own-history-recommendation
             {:instrument-id "vault:0xabc" :market-type :vault}
             {:portfolio {:all-time (summary 40 1.5) :month (summary 30 1)}})]
    (is (= :conservative (:approach rec)))
    (is (= :vault-own-history (:source rec)))
    (is (number? (:volatility rec)))
    (is (= default-assumptions/own-history-max-weight (:max-weight rec)))
    (is (re-find #"own" (:rationale rec))))
  (is (nil? (default-assumptions/vault-own-history-recommendation
             {:instrument-id "perp:BTC" :market-type :perp}
             {:portfolio {:all-time (summary 40 1.5)}}))
      "only vaults"))

(deftest plan-applies-a-frontend-recommendation-test
  (let [rec {:approach :conservative :members [] :volatility 0.31 :max-weight 0.1
             :source :vault-own-history :rationale "own"}
        plan (default-assumptions/recommendation-plan
              {:discovery {}
               :assumptions {}
               :universe-ids #{"vault:0xabc"}
               :resolve-member (constantly nil)
               :targets [{:instrument-id "vault:0xabc" :recommendation rec}]})
        entry (get-in plan [:assumptions "vault:0xabc"])]
    (is (= [{:instrument-id "vault:0xabc" :held []}] (:applied plan)))
    (is (= :conservative (:behavior entry)))
    (is (= 0.31 (:volatility entry)))
    (is (= 0.1 (:max-weight entry)))
    (is (= :vault-own-history (get-in entry [:metadata :source])))))
