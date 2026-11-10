(ns hyperopen.outcomes.domain.quotes-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.outcomes.domain.quotes :as quotes]))

(defn- close-to?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 1e-9))

;; Order book for #270370 (DET to beat WAS), 2026-11-09 16:25 UTC.
(def ^:private det-asks
  [[0.81902 758] [0.81903 97] [0.8194 700] [0.81987 832]])

(def ^:private det-bids
  [[0.81886 69] [0.81885 135] [0.81859 67]])

;; A never-traded contract as the live feed shows it: a placeholder mid near
;; 0.50, a previous-day price of 0 and no volume.
(def ^:private placeholder-side
  {:mark 0.5 :markRaw "0.5" :mid 0.5 :midRaw "0.5" :prevDayRaw "0.0" :volume24h 0 :baseVolume24h 0})

(deftest the-one-rule-reads-top-to-bottom-test
  (testing "1 · bid and ask 5¢ apart or less → the midpoint"
    (let [q (quotes/chance-quote {:bid 0.81886 :ask 0.81902 :book-loaded? true})]
      (is (= :live (:state q)))
      (is (close-to? 0.81894 (:chance q)))
      (is (true? (:market-order? q)))
      (is (true? (:rankable? q)))
      (is (false? (:muted? q)))
      (is (= "82%" (quotes/quote-text q))))
    (testing "exactly 5¢ is still tight"
      (is (= :live (:state (quotes/chance-quote {:bid 0.30 :ask 0.35 :book-loaded? true}))))))
  (testing "a tight book wins even for a never-traded contract"
    (is (= :live (:state (quotes/chance-quote {:bid 0.49 :ask 0.51 :book-loaded? true :never-traded? true})))))
  (testing "2 · never traded and not tight → Not traded, limit only, with the standing quotes"
    (let [q (quotes/chance-quote {:bid 0.013 :ask 0.987 :book-loaded? true :never-traded? true})]
      (is (= :not-traded (:state q)))
      (is (nil? (:chance q)))
      (is (false? (:market-order? q)))
      (is (= "Not traded" (quotes/quote-text q)))
      (is (= "bid 1.3¢ · ask 98.7¢" (quotes/standing-quotes-text q)))))
  (testing "3 · both sides, more than 5¢ and up to 20¢ apart → the muted range, never one number"
    (let [q (quotes/chance-quote {:bid 0.30 :ask 0.40 :book-loaded? true})]
      (is (= :range (:state q)))
      (is (nil? (:chance q)))
      (is (= [0.30 0.40] [(:low q) (:high q)]))
      (is (true? (:market-order? q)))
      (is (false? (:rankable? q)))
      (is (true? (:muted? q)))
      (is (= "30–40%" (quotes/quote-text q)))))
  (testing "4 · more than 20¢ apart, one-sided or empty → No market, limit only"
    (doseq [input [{:bid 0.012 :ask 0.988 :book-loaded? true}
                   {:bid nil :ask 0.988 :book-loaded? true}
                   {:bid nil :ask nil :book-loaded? true}]]
      (let [q (quotes/chance-quote input)]
        (is (= :no-market (:state q)))
        (is (false? (:market-order? q)))
        (is (= "No market" (quotes/quote-text q))))))
  (testing "no quotes yet → a dash, never the mark"
    (let [q (quotes/chance-quote {})]
      (is (= :no-quotes (:state q)))
      (is (= "—" (quotes/quote-text q))))))

(deftest side-quote-never-shows-a-placeholder-or-the-mark-test
  (testing "the placeholder fixture (mid 0.50, prevDayPx 0, no volume) is Not traded"
    (let [q (quotes/side-quote placeholder-side)]
      (is (= :not-traded (:state q)))
      (is (true? (:never-traded? q)))
      (is (not= "50%" (quotes/quote-text q)))))
  (testing "a traded side with only a context midpoint is a dash: the width is unknown"
    (is (= :no-quotes (:state (quotes/side-quote {:mark 0.81908 :mid 0.81894 :midRaw "0.81894"
                                                  :prevDayRaw "0.8173" :volume24h 184799})))))
  (testing "a null midPx on an arrived context means the book lost a side"
    (is (= :no-market (:state (quotes/side-quote {:mark 0.21 :midRaw nil :prevDayRaw "0.2" :volume24h 10})))))
  (testing "no context yet is unknown, not untraded"
    (is (= :no-quotes (:state (quotes/side-quote {:mark 0 :prevDayRaw nil :volume24h 0})))))
  (testing "a side carrying its best bid/ask reads them"
    (is (= :live (:state (quotes/side-quote {:bid 0.81886 :ask 0.81902 :prevDayRaw "0.8173"}))))
    (is (= :range (:state (quotes/side-quote {:bid 0.30 :ask 0.40 :prevDayRaw "0.3"}))))))

(deftest never-traded-needs-positive-evidence-test
  (is (true? (quotes/never-traded? {:prevDayRaw "0.0" :volume24h 0})))
  (is (false? (quotes/never-traded? {:prevDayRaw "0.0" :volume24h 12})))
  (is (false? (quotes/never-traded? {:prevDayRaw "0.0" :baseVolume24h 3})))
  (is (false? (quotes/never-traded? {:prevDayRaw nil :volume24h 0})))
  (is (false? (quotes/never-traded? {:prevDayRaw "0.5" :volume24h 0}))))

(deftest book-quote-is-the-same-rule-test
  (let [tight (quotes/book-quote 0.81886 0.81902 0.81908)
        wide (quotes/book-quote 0.012 0.988 0.051985)
        one-sided (quotes/book-quote nil 0.988 0.5)
        untraded (quotes/book-quote 0.013 0.987 0.5 placeholder-side)]
    (is (= :live (:state tight)))
    (is (close-to? 0.81894 (:chance tight)))
    (is (= :no-market (:state wide)))
    (is (nil? (:chance wide)) "the mark is never the chance")
    (is (= {:bid 0.012 :ask 0.988} (select-keys wide [:bid :ask])))
    (is (= :no-market (:state one-sided)))
    (is (= 0.988 (:ask one-sided)))
    (is (= :not-traded (:state untraded)))))

(deftest mirror-quote-flips-prices-and-swaps-bid-ask-test
  (let [mirrored (quotes/mirror-quote {:state :live :chance 0.8 :price 0.8 :bid 0.79 :ask 0.81})]
    (is (close-to? 0.2 (:chance mirrored)))
    (is (close-to? 0.19 (:bid mirrored)))
    (is (close-to? 0.21 (:ask mirrored))))
  (let [mirrored (quotes/mirror-quote (quotes/chance-quote {:bid 0.30 :ask 0.40 :book-loaded? true}))]
    (is (= :range (:state mirrored)))
    (is (= "60–70%" (quotes/quote-text mirrored)))))

(deftest ends-and-ranges-print-clamped-test
  (is (= "<1%" (quotes/quote-text (quotes/chance-quote {:bid 0.001 :ask 0.004 :book-loaded? true}))))
  (is (= ">99%" (quotes/quote-text (quotes/chance-quote {:bid 0.995 :ask 0.999 :book-loaded? true}))))
  (is (= "<1–12%" (quotes/range-text 0.002 0.12))))

(deftest exclusive-sum-flags-answers-past-105-percent-test
  (let [live (fn [c] {:state :live :chance c})]
    (is (= {:sum 1.09 :counted 3 :over? true :text "Adds up to 109%"}
           (update (quotes/exclusive-sum [(live 0.75) (live 0.07) (live 0.27)]) :sum #(/ (js/Math.round (* 100 %)) 100))))
    (is (false? (:over? (quotes/exclusive-sum [(live 0.60) (live 0.45)]))) "exactly 105% is not flagged")
    (testing "only midpoint chances count"
      (is (false? (:over? (quotes/exclusive-sum [(live 0.9) {:state :range :low 0.3 :high 0.4}
                                                 {:state :not-traded}])))))))

(deftest compact-shares-text-test
  (is (= "943K shares" (quotes/compact-shares-text 943120)))
  (is (= "1.2M shares" (quotes/compact-shares-text 1200000)))
  (is (= "950 shares" (quotes/compact-shares-text 950)))
  (is (nil? (quotes/compact-shares-text nil))))

(deftest text-helpers-test
  (is (= "82%" (quotes/chance-text 0.81894)))
  (is (= "<1%" (quotes/chance-text 0.004)))
  (is (= ">99%" (quotes/chance-text 0.996)))
  (is (= "—" (quotes/chance-text nil)))
  (is (= "Not traded" (quotes/quote-text {:state :not-traded})))
  (is (= "No market" (quotes/quote-text {:state :no-market})))
  (is (= "81.9¢" (quotes/cents-text 0.81902)))
  (is (= "81.902¢" (quotes/cents-text 0.81902 {:precise? true})))
  (is (= "+0.2 pts" (quotes/points-text 0.0018)))
  (is (= "−1.0 pts" (quotes/points-text -0.0099)))
  (is (= "0.0 pts" (quotes/points-text 0.0001)))
  (is (= "$1,234.50" (quotes/usd-text 1234.5)))
  (is (= "+$22.08" (quotes/usd-text 22.08 {:signed? true})))
  (is (= "−$0.02" (quotes/usd-text -0.02 {:signed? true})))
  (is (= "$227K" (quotes/compact-usd-text 227004)))
  (is (= "$1.2M" (quotes/compact-usd-text 1200000))))

(deftest change-24h-needs-a-reference-price-test
  (is (close-to? 0.00178 (quotes/change-24h {:mark 0.81908 :prevDayRaw "0.8173"})))
  (is (nil? (quotes/change-24h {:mark 0.5 :prevDayRaw "0.0"}))))

(deftest walk-asks-buys-whole-shares-test
  (testing "$100 of DET fills 122 shares at the best ask"
    (let [{:keys [shares cost avg-price exhausted?]} (quotes/walk-asks det-asks 100)]
      (is (= 122 shares))
      (is (close-to? 99.92044 cost))
      (is (close-to? 0.81902 avg-price))
      (is (false? exhausted?))))
  (testing "a bigger order walks into deeper levels"
    (let [{:keys [shares cost]} (quotes/walk-asks det-asks 1000)]
      (is (= 1220 shares))
      (is (< 999 cost 1000)))))

(deftest sell-into-bids-and-payout-test
  (let [{:keys [shares proceeds]} (quotes/sell-into-bids det-bids 122)]
    (is (= 122 shares))
    (is (close-to? (+ (* 69 0.81886) (* 53 0.81885)) proceeds)))
  (let [{:keys [pays profit profit-pct]} (quotes/payout 122 99.92)]
    (is (= 122 pays))
    (is (close-to? 22.08 profit))
    (is (< 22.09 profit-pct 22.11))))
