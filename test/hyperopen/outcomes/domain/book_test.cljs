(ns hyperopen.outcomes.domain.book-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.outcomes.domain.book :as book]))

(def ^:private yes {:side-index 0 :coin "#10" :prevDayRaw "0.5"})
(def ^:private no {:side-index 1 :coin "#11" :prevDayRaw "0.5"})

(def ^:private wide-book
  {:bids [{:px "0.20" :sz "10"}] :asks [{:px "0.60" :sz "10"}]})

(deftest the-fed-top-of-book-wins-over-the-active-l2-book-test
  (testing "list, header, ticket rows and side buttons read one source"
    (let [fed (assoc yes :bid 0.64 :ask 0.66)
          quote (book/quote-for-side fed no {"#10" wide-book})]
      (is (= :live (:state quote)))
      (is (< (js/Math.abs (- 0.65 (:chance quote))) 1e-9))))
  (testing "a sibling's fed top of book is mirrored"
    (let [quote (book/quote-for-side yes (assoc no :bid 0.34 :ask 0.36) nil)]
      (is (= :live (:state quote)))
      (is (< (js/Math.abs (- 0.65 (:chance quote))) 1e-9))))
  (testing "with nothing fed the active side's l2 book decides"
    (is (= :no-market (:state (book/quote-for-side yes no {"#10" wide-book}))))))

(deftest market-side-quotes-is-the-shared-helper-test
  (let [market {:coin "#11" :outcome-side-index 1
                :outcome-sides [(assoc yes :bid 0.64 :ask 0.66) no]}
        [q0 q1] (book/market-side-quotes market nil)]
    (is (= [:live :live] [(:state q0) (:state q1)]))
    (is (< (js/Math.abs (- 0.35 (:chance q1))) 1e-9))))

(deftest market-orders-off-test
  (let [market {:coin "#10" :outcome-sides [yes no]}]
    (is (false? (book/market-orders-off? market nil)) "a book still loading does not block")
    (is (true? (book/market-orders-off? market {:bids [] :asks [{:px "0.5" :sz "1"}]})))
    (is (true? (book/market-orders-off? market wide-book)))
    (is (false? (book/market-orders-off? market {:bids [{:px "0.64" :sz "1"}]
                                                 :asks [{:px "0.66" :sz "1"}]})))))
