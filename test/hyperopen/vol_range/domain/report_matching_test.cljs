(ns hyperopen.vol-range.domain.report-matching-test
  "Gross spread on matched fills (HO-SPEC-014 v2.1 section 12.5, 13.5 row
  5): FIFO over gross quantities in either order, partial splits counted as
  pairs, unmatched buys and sold inventory marked at the end price, fee and
  rebate impact, and the reconciliation that equals the headline
  contribution for any sequence of USDC- or base-fee fills. Synthetic
  fixtures only."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop :include-macros true]
            [hyperopen.vol-range.domain.report-equations :as eq]
            [hyperopen.vol-range.domain.report-ledger :as ledger]
            [hyperopen.vol-range.domain.report-matching :as matching]
            [hyperopen.vol-range.report-fixtures :as fx]))

(def campaign (fx/campaign-record))
(def ctx {:base-symbol "VRT" :quote-symbol "USDC" :tracked? true :campaign-id "cmp-vrt"})

(defn- event [row] (ledger/fill-event (fx/fill row) ctx))

(defn- row [{:keys [side px sz at tid fee fee-token] :or {fee "0" fee-token "USDC"}}]
  (fx/fill-row {:side side :px px :sz sz :time (+ fx/t0 at) :tid tid :oid (if (= "B" side) 7001 7002)
                :fee fee :fee-token fee-token}))

(def a fx/t0)
(def b (+ fx/t0 (* 3 fx/hour)))

(defn- match [events p-end] (matching/match-window {:campaign campaign :events events :a-ms a :b-ms b :p-end p-end}))

(defn- contribution [events p-end]
  (let [sa (ledger/state-before campaign events a)
        sb (ledger/state-before campaign events b)]
    (:contribution (eq/window-figures {:q-a (:q sa) :c-a (:c sa) :p-a "80" :q-b (:q sb) :c-b (:c sb) :p-b p-end}))))

(deftest thirteen-five-row-5-test
  (let [events (ledger/events [(event fx/matched-buy-row) (event fx/matched-sell-row)])
        m (match events "100")]
    (is (= {:fills 2 :pairs 1 :matched-qty "9.9" :gross "99" :unmatched-buy-qty "0.1" :unmatched-buys "2"
            :unmatched-sell-qty "0" :unmatched-sells "0" :usdc-fees "0" :base-fees "0.1" :fee-impact "-10"
            :total "91" :p-end "100" :fees-by-token {"VRT" "0.1" "USDC" "0"} :reconciles? true}
           m))
    (testing "equal to the contribution: 891 − 800 with zero net base change"
      (is (= "91" (contribution events "100")))
      (is (matching/reconciles-with? m "91"))
      (is (not (matching/reconciles-with? m "91.01"))))))

(deftest sell-then-buy-partials-and-pair-counts-test
  (let [events (ledger/events [(event (row {:side "A" :px "90" :sz "5" :at 1000 :tid 1}))
                               (event (row {:side "A" :px "92" :sz "3" :at 2000 :tid 2}))
                               (event (row {:side "B" :px "85" :sz "6" :at 3000 :tid 3}))
                               (event (row {:side "B" :px "84" :sz "4" :at 4000 :tid 4}))])
        m (match events "88")]
    (testing "a buy of 6 splits across the oldest sell (5) and the next (1); the next buy takes the last 2"
      (is (= 3 (:pairs m)))
      (is (= "8" (:matched-qty m)))
      (is (= (str (+ (* 5 5) (* 1 7) (* 2 8))) (:gross m))))
    (testing "the remaining 2 bought are marked at the end price"
      (is (= "2" (:unmatched-buy-qty m)))
      (is (= "8" (:unmatched-buys m))))
    (is (= (:total m) (contribution events "88")))))

(deftest sold-inventory-not-reaccumulated-test
  (let [events (ledger/events [(event (row {:side "A" :px "90" :sz "4" :at 1000 :tid 1}))])
        m (match events "100")]
    (is (= 0 (:pairs m)))
    (is (= ["4" "-40"] [(:unmatched-sell-qty m) (:unmatched-sells m)]))
    (is (= "-40" (:total m) (contribution events "100")))))

(deftest a-negative-fee-is-a-rebate-test
  (let [events (ledger/events [(event (row {:side "B" :px "80" :sz "1" :at 1000 :tid 1 :fee "-0.02"}))
                               (event (row {:side "A" :px "81" :sz "1" :at 2000 :tid 2 :fee "-0.001" :fee-token "VRT"}))])
        m (match events "100")]
    (is (= ["-0.02" "-0.001"] [(:usdc-fees m) (:base-fees m)]))
    (is (= "0.12" (:fee-impact m)) "a rebate adds")
    (is (= "1.12" (:total m) (contribution events "100")))))

(deftest window-membership-and-untracked-fills-test
  (let [at-b (event (row {:side "B" :px "80" :sz "1" :at (* 3 fx/hour) :tid 9}))
        before (event (row {:side "A" :px "90" :sz "1" :at -1 :tid 8}))
        untracked (assoc (event (row {:side "A" :px "90" :sz "1" :at 1000 :tid 7})) :tracked? false)
        m (match (ledger/events [at-b before untracked]) "100")]
    (is (= 0 (:fills m)) "a fill at b, before a or untracked is outside the window")
    (is (= "0" (:total m)))))

(deftest a-third-token-fee-is-unreconciled-test
  (let [e (ledger/fill-event (fx/fill (row {:side "B" :px "80" :sz "1" :at 1000 :tid 1 :fee "0.5" :fee-token "VRS"})) ctx)
        m (match [e] "100")]
    (is (false? (:reconciles? m)))
    (is (nil? (:total m)))
    (is (not (matching/reconciles-with? m "20")))
    (is (nil? (match [e] nil)) "no end mark, no diagnostic")))

(def ^:private fill-gen
  (gen/let [side (gen/elements ["B" "A"])
            px (gen/choose 50 120)
            cents (gen/choose 0 99)
            sz (gen/choose 1 40)
            frac (gen/choose 0 9)
            fee (gen/choose -20 60)
            fee-token (gen/elements ["USDC" "VRT"])]
    {:side side :px (str px "." cents) :sz (str sz "." frac) :fee (str (/ fee 1000)) :fee-token fee-token}))

(deftest reconciliation-equals-the-headline-contribution-property-test
  (let [result (tc/quick-check
                150
                (prop/for-all [fills (gen/vector fill-gen 0 12)
                               p-end (gen/choose 40 140)]
                  (let [events (ledger/events
                                (map-indexed (fn [i f] (event (row (assoc f :at (* 1000 (inc i)) :tid (inc i)))))
                                             fills))
                        m (match events (str p-end))]
                    (and (:reconciles? m)
                         (matching/reconciles-with? m (contribution events (str p-end)))))))]
    (is (true? (:pass? result)) (pr-str (:shrunk result)))))
