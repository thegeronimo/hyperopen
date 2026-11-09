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

(deftest side-quote-never-shows-a-placeholder-as-a-chance-test
  (testing "never traded: 0.50 mark, no mid, no previous price"
    (is (= {:state :not-traded}
           (quotes/side-quote {:mark 0.5 :mid nil :prevDayRaw "0.0" :volume24h 0}))))
  (testing "a 1.2¢ / 98.8¢ book reports a 0.50 mid; the last trade wins"
    (is (= {:state :last :chance 0.051985 :price 0.051985}
           (quotes/side-quote {:mark 0.051985 :mid 0.5 :prevDayRaw "0.988" :volume24h 238.38}))))
  (testing "a book that lost a side keeps its last trade"
    (is (= :last (:state (quotes/side-quote {:mark 0.21 :mid nil :prevDayRaw "0.2" :volume24h 10})))))
  (testing "no context yet is unknown, not untraded"
    (is (= {:state :unknown} (quotes/side-quote {:mark 0 :mid 0 :prevDayRaw nil :volume24h 0})))
    (is (= "—" (quotes/quote-text {:state :unknown}))))
  (testing "a tight book quotes its mid"
    (is (= {:state :live :chance 0.81894 :price 0.81894}
           (quotes/side-quote {:mark 0.81908 :mid 0.81894 :prevDayRaw "0.8173" :volume24h 184799})))))

(deftest book-quote-uses-the-mid-only-inside-five-cents-test
  (let [tight (quotes/book-quote 0.81886 0.81902 0.81908)
        wide (quotes/book-quote 0.012 0.988 0.051985)
        one-sided (quotes/book-quote nil 0.988 0.5)]
    (is (= :live (:state tight)))
    (is (close-to? 0.81894 (:chance tight)))
    (is (= {:state :last :chance 0.051985 :price 0.051985 :bid 0.012 :ask 0.988}
           (dissoc wide :spread)))
    (is (= {:state :no-market :ask 0.988 :last 0.5} one-sided))))

(deftest mirror-quote-flips-prices-and-swaps-bid-ask-test
  (let [mirrored (quotes/mirror-quote {:state :live :chance 0.8 :price 0.8 :bid 0.79 :ask 0.81})]
    (is (close-to? 0.2 (:chance mirrored)))
    (is (close-to? 0.19 (:bid mirrored)))
    (is (close-to? 0.21 (:ask mirrored)))))

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
