(ns hyperopen.vol-range.domain.report-ledger-test
  "13.5 rows 3, 4 and 6 at the event level."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.domain.report-ledger :as ledger]
            [hyperopen.vol-range.report-fixtures :as fx]))

(def ctx {:base-symbol "VRT" :quote-symbol "USDC" :tracked? true :campaign-id "cmp-vrt"})

(defn- event [row] (ledger/fill-event (fx/fill row) ctx))

(deftest buy-and-sell-deltas-before-fees-test
  (let [b (event fx/buy-7-row)
        s (event fx/sell-30-row)]
    (is (= {:base-delta "7" :cash-delta "-534.8" :net-base-delta "7" :net-cash-delta "-534.8"
            :fee "0" :fee-kind :quote :fee-token "USDC"}
           (select-keys b [:base-delta :cash-delta :net-base-delta :net-cash-delta :fee :fee-kind :fee-token])))
    (is (= {:base-delta "-30" :cash-delta "2652" :net-base-delta "-30" :net-cash-delta "2652"}
           (select-keys s [:base-delta :cash-delta :net-base-delta :net-cash-delta])))
    (is (= (:fill-key (fx/fill fx/buy-7-row)) (:event-id b)))))

(deftest row-3-base-fee-reduces-net-base-test
  (let [e (event fx/base-fee-row)]
    (is (= :base (:fee-kind e)))
    (is (= "9.996" (:net-base-delta e)))
    (is (= "-800" (:net-cash-delta e)))
    (is (= {:q "159.996" :c "-800"}
           (ledger/state-before (fx/campaign-record) [e] (+ fx/t0 (* 2 fx/hour)))))))

(deftest row-4-builder-fee-is-already-in-the-fee-test
  (let [e (event fx/builder-fee-row)]
    (is (= "0.4" (:fee e)))
    (is (= "-800.4" (:net-cash-delta e)) "the debit is 0.4, not 0.5")
    (is (= "10" (:net-base-delta e)))))

(deftest negative-fee-credits-test
  (let [e (event (assoc fx/buy-7-row :fee "-0.0534" :tid 777))]
    (is (= "-534.7466" (:net-cash-delta e)))))

(deftest third-token-fee-is-unsupported-and-kept-test
  (let [e (event (assoc fx/buy-7-row :fee "0.01" :feeToken "VRS"))]
    (is (= :unsupported (:fee-kind e)))
    (is (= "VRS" (:fee-token e)))
    (is (= "7" (:net-base-delta e)))
    (is (= "-534.8" (:net-cash-delta e)) "never converted into USDC or base")))

(deftest events-sort-stably-and-de-duplicate-test
  (let [a (event (assoc fx/buy-7-row :tid 10))
        b (event (assoc fx/buy-7-row :tid 9))
        c (event (assoc fx/buy-7-row :tid 100 :time (dec (:time fx/buy-7-row))))]
    (is (= [(:event-id c) (:event-id b) (:event-id a)]
           (mapv :event-id (ledger/events [a b c a])))
        "time first, then tid numerically, duplicates once")))

(deftest row-6-state-before-is-half-open-test
  (let [e (event fx/buy-7-row)
        t (:time e)
        c (fx/campaign-record)]
    (is (= {:q "150" :c "0"} (ledger/state-before c [e] t)) "a fill at exactly t is excluded")
    (is (= {:q "157" :c "-534.8"} (ledger/state-before c [e] (inc t))))
    (is (= 1 (count (ledger/in-window [e] [t (inc t)]))) "and included in the interval starting at t")
    (is (= {:q "150" :c "0"} (ledger/state-before c [(assoc e :tracked? false)] (inc t))) "untracked never counts")
    (is (= {:q "150" :c "0"} (ledger/state-before c [(assoc e :time (dec fx/t0))] (inc t))) "before T0 is in q0")))

(deftest activity-summary-test
  (let [es (ledger/events [(event fx/buy-7-row) (event fx/sell-30-row) (event fx/base-fee-row)
                           (event (assoc fx/builder-fee-row :crossed true))])]
    (is (= {:fills 4 :bought-base "27" :bought-quote "2134.8" :buy-vwap "79.066666666667"
            :sold-base "30" :sold-quote "2652" :sell-vwap "88.4" :maker 3 :taker 1
            :fees-by-token {"USDC" "0.4" "VRT" "0.004"}}
           (ledger/activity es)))))
