(ns hyperopen.portfolio.vault-attribution.test-fixtures
  (:require [cljs.test :refer-macros [is]]
            [hyperopen.portfolio.metrics :as portfolio-metrics]))

(def now 1794500000000)

(def day 86400000)

(def vault-a "0xdfc24b077bc1425ad1dea75bcb6f8158e10df303")

(def vault-b "0x1e37a337ed460039d1b15bd3bc489de789768d5e")

(defn near?
  ([expected actual] (near? expected actual 1e-6))
  ([expected actual tolerance]
   (and (number? actual)
        (js/isFinite actual)
        (< (js/Math.abs (- expected actual)) tolerance))))

(defn deposit
  [time-ms vault usdc]
  {:time time-ms
   :hash (str "0xdep" time-ms)
   :delta {:type "vaultDeposit" :vault vault :usdc usdc}})

(defn withdraw
  "A `vaultWithdraw` row; `:user` only when given (the account's own rows
   name the account, which the selector checks)."
  [time-ms vault {:keys [requested commission basis net user]}]
  {:time time-ms
   :hash (str "0xwd" time-ms)
   :delta {:type "vaultWithdraw"
           :vault vault
           :user user
           :requestedUsd requested
           :commission commission
           :closingCost "0.0"
           :basis basis
           :netWithdrawnUsd net}})

(def noise
  "Rows the model must skip without throwing."
  [nil
   "garbage"
   {:delta nil}
   {:time "not-a-time" :delta {:type "vaultDeposit" :vault vault-a :usdc "5"}}
   {:time 1 :delta {:type "vaultDeposit" :vault vault-a :usdc "NaN"}}
   {:time 2 :delta {:type "deposit" :usdc "100"}}
   {:time 3 :delta {:type "vaultLeaderCommission" :usdc "1"}}])

(defn index-rows
  "Cumulative-percent rows `[[time-ms pct] ...]` sampled every `step-ms` from
   `start-ms` through `end-ms` (inclusive), rebased to 0% at `start-ms` like
   every Hyperliquid window. `index-fn` maps time to the true share price."
  [start-ms end-ms step-ms index-fn]
  (let [base (index-fn start-ms)
        times (concat (range start-ms end-ms step-ms) [end-ms])]
    (mapv (fn [t] [t (* 100 (- (/ (index-fn t) base) 1))]) (distinct times))))

(defn all-time-returns
  "Cumulative returns the real portfolio metrics code computes from an entry."
  [entry]
  (portfolio-metrics/returns-history-rows-from-summary entry))

(defn assert-series-invariants
  [result]
  (let [series (:series result)
        totals (:totals result)]
    (is (seq series))
    (is (apply < (map :time-ms series)) "times strictly ascending")
    (doseq [{:keys [value net-deposited pnl] :as point} series]
      (is (every? #(and (number? %) (js/isFinite %)) [value net-deposited pnl]) (pr-str point))
      (is (near? value (+ pnl net-deposited)) "value = pnl + net deposited"))
    (doseq [[a b] (partition 2 1 series)
            :when (not (near? (:net-deposited a) (:net-deposited b) 1e-9))]
      (is (= 1 (- (:time-ms b) (:time-ms a))) "a flow spans exactly 1 ms")
      (is (near? (:pnl a) (:pnl b) 1e-9) "no P&L shares an interval with a flow"))
    (is (near? (:pnl totals) (:pnl (peek series))) "series ends at totals pnl")
    (is (near? (:pnl totals)
               (reduce + 0 (keep :pnl (:vaults result))))
        "vault pnl sums to totals pnl")
    (when-let [entry (get-in result [:summary-entries :vault-all-time])]
      (let [rows (all-time-returns entry)]
        (is (seq rows))
        (is (every? (fn [[t v]] (and (js/isFinite t) (js/isFinite v))) rows))))))
