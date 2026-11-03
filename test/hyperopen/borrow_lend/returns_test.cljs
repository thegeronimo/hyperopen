(ns hyperopen.borrow-lend.returns-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.borrow-lend.returns :as returns]))

(def ^:private hour-ms (* 60 60 1000))
(def ^:private day-ms (* 24 hour-ms))

(defn- approx=
  ([a b] (approx= a b 1e-9))
  ([a b eps] (and (number? a) (number? b) (< (js/Math.abs (- a b)) eps))))

(defn- simulate
  "Hourly accrual at a constant hourly `rate` for `hours` hours, starting with
  a supply of `start` at time 0, plus `deposits` as {hour amount}. Returns the
  rows the API would send and the final balance."
  [{:keys [rate hours start deposits]}]
  (loop [h 1
         balance start
         interest []
         ledger [{:time-ms 0 :token "USDC" :operation :supply :amount start}]]
    (if (> h hours)
      {:interest interest :ledger ledger :value balance :now-ms (+ (* hours hour-ms) 20000)}
      (let [earned (* balance rate)
            balance* (+ balance earned)
            row {:time-ms (+ (* h hour-ms) 10000) :token "USDC" :supply earned :borrow 0 :hours 1 :daily? false}
            deposit (get deposits h)]
        (recur (inc h)
               (cond-> balance* deposit (+ deposit))
               (conj interest row)
               (cond-> ledger
                 deposit (conj {:time-ms (+ (* h hour-ms) 30000) :token "USDC"
                                :operation (if (pos? deposit) :supply :withdraw)
                                :amount (js/Math.abs deposit)})))))))

(defn- run [{:keys [interest ledger value now-ms]}]
  (returns/realized-returns {:now-ms now-ms :current-value value
                             :interest-rows interest :ledger-rows ledger}))

(deftest constant-rate-recovers-the-rate-test
  (let [rate 0.000005
        sim (simulate {:rate rate :hours (* 40 24) :start 1000 :deposits {(* 12 24) 500 (* 30 24) -300}})
        {:keys [status periods since-ms]} (run sim)]
    (is (= :ok status))
    (testing "the first supplied hour starts one hour before the first hourly row"
      (is (= 10000 since-ms)))
    (testing "periods shorter than the history, plus since first supply"
      (is (= [:7d :1m :all] (mapv :id periods))))
    (testing "every period realizes the rate that was paid, whatever the deposits"
      (doseq [{:keys [realized-apy id]} periods]
        (is (approx= (* rate 8760) realized-apy 1e-9) (str id))))
    (testing "hours and average balance"
      (let [{:keys [hours avg-balance interest]} (last periods)]
        (is (= (* 40 24) hours))
        (is (< 1000 avg-balance 1500))
        (is (approx= interest (reduce + (map :supply (:interest sim))) 1e-9))))
    (testing "the 7-day window starts seconds into an hourly row, which is prorated"
      (is (approx= (* 7 24) (:hours (first periods)) 0.01)))))

(deftest reconstructed-balances-test
  (let [sim (simulate {:rate 0.0001 :hours 3 :start 100 :deposits {2 50}})
        rows (returns/exposure-rows (:value sim) (:interest sim) (:ledger sim))]
    (is (approx= 100 (:balance (first rows))))
    (is (approx= (* 100 1.0001) (:balance (second rows))))
    (is (approx= (+ 50 (* 100 1.0001 1.0001)) (:balance (nth rows 2))))))

(deftest daily-row-counts-a-same-day-supply-test
  (testing "a daily aggregate stamped before the day's first supply still accrues on it"
    (let [day0 (* 100 day-ms)
          interest [{:time-ms day0 :token "USDC" :supply 0.1 :borrow 0 :hours 4 :daily? true}]
          ledger [{:time-ms (+ day0 (* 20 hour-ms)) :token "USDC" :operation :supply :amount 1000}]
          {:keys [status periods since-ms]} (returns/realized-returns
                                              {:now-ms (+ day0 day-ms 1)
                                               :current-value 1000.1
                                               :interest-rows interest
                                               :ledger-rows ledger})]
      (is (= :ok status))
      (is (= (+ day0 (* 20 hour-ms)) since-ms))
      (is (= [:all] (mapv :id periods)))
      (is (= 4 (:hours (first periods))))
      (is (approx= (/ (* 0.1 8760) (* 1000 4)) (:realized-apy (first periods)) 1e-9)))))

(deftest inconsistent-history-test
  (testing "today's balance smaller than the interest it received"
    (is (= {:status :inconsistent}
           (returns/realized-returns {:now-ms 10 :current-value 1
                                      :interest-rows [{:time-ms 5 :supply 5 :hours 1 :daily? false}]
                                      :ledger-rows []}))))
  (testing "rounding within a cent is not an inconsistency"
    (is (= :ok (:status (returns/realized-returns
                         {:now-ms (* 2 hour-ms) :current-value 100.004
                          :interest-rows [{:time-ms hour-ms :supply 0.01 :hours 1 :daily? false}]
                          :ledger-rows [{:time-ms 0 :operation :supply :amount 100}]}))))))

(deftest empty-and-trailing-history-test
  (is (= {:status :empty}
         (returns/realized-returns {:now-ms 10 :current-value 50 :interest-rows [] :ledger-rows []})))
  (testing "interest posted after a full withdrawal counts, with no balance-hours"
    (let [rows (returns/exposure-rows 0
                                      [{:time-ms (* 2 hour-ms) :supply 0.001 :hours 1 :daily? false}]
                                      [{:time-ms (+ hour-ms 1) :operation :withdraw :amount 10.001}])]
      (is (= [{:end-ms (* 2 hour-ms) :span-ms hour-ms :interest 0.001 :hours 0 :balance 0}] rows)))))

(deftest daily-row-straddling-a-period-start-is-prorated-test
  (let [now-ms (* 100 day-ms)
        ;; Ten full daily rows, the newest ending at `now-ms`. Twelve hours
        ;; later the 7-day window starts halfway through the seventh-newest
        ;; row: six full rows plus half of that one.
        interest (mapv (fn [d] {:time-ms (- now-ms (* d day-ms)) :token "USDC"
                                :supply 1 :borrow 0 :hours 24 :daily? true})
                       (range 10 0 -1))
        {:keys [periods]} (returns/realized-returns
                           {:now-ms (+ now-ms (* 12 hour-ms))
                            :current-value 1010
                            :interest-rows interest
                            :ledger-rows [{:time-ms (- now-ms (* 10 day-ms)) :operation :supply :amount 1000}]})
        week (first periods)]
    (is (= :7d (:id week)))
    (is (approx= (* 6.5 24) (:hours week) 1e-9))
    (is (approx= 6.5 (:interest week) 1e-9))))
