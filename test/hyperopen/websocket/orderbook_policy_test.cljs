(ns hyperopen.websocket.orderbook-policy-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.websocket.orderbook-policy :as policy]))

(deftest parse-number-test
  (is (= 42 (policy/parse-number 42)))
  (is (= 12.5 (policy/parse-number "12.5")))
  (is (nil? (policy/parse-number "abc")))
  (is (nil? (policy/parse-number nil))))

(deftest sort-levels-test
  (let [levels [{:px "100"} {:px 101} {:px "99.5"}]]
    (testing "bids sorted descending by parsed price"
      (is (= [101 "100" "99.5"]
             (mapv :px (policy/sort-bids levels)))))
    (testing "asks keep legacy descending sort for compatibility"
      (is (= [101 "100" "99.5"]
             (mapv :px (policy/sort-asks levels)))))))

(deftest sort-levels-treats-missing-or-invalid-prices-as-zero-test
  (let [sorted-bids (policy/sort-bids [{:px "101"}
                                       {:price "invalid"}
                                       {:size "3"}])]
    (is (= [101 0 0]
           (mapv #(or (policy/level-price %) 0) sorted-bids)))))

(deftest sort-levels-puts-invalid-prices-below-small-positive-prices-test
  (let [sorted-bids (policy/sort-bids [{:id :half :px "0.5"}
                                       {:id :missing}
                                       {:id :invalid :price "invalid"}])]
    (is (= [:half :missing :invalid]
           (mapv :id sorted-bids)))))

(deftest normalize-levels-and-cumulative-depth-test
  (let [levels (policy/normalize-levels [{:px "101.5" :sz "2"}
                                         {:px "100.5" :sz "3"}])
        with-totals (policy/calculate-cumulative-totals levels)]
    (is (= [{:px "101.5" :sz "2" :px-num 101.5 :sz-num 2}
            {:px "100.5" :sz "3" :px-num 100.5 :sz-num 3}]
           levels))
    (is (= [{:px "101.5" :sz "2" :px-num 101.5 :sz-num 2 :cum-size 2 :cum-value 203}
            {:px "100.5" :sz "3" :px-num 100.5 :sz-num 3 :cum-size 5 :cum-value 504.5}]
           with-totals))))

(deftest calculate-cumulative-totals-uses-zero-defaults-for-missing-price-and-size-test
  (let [with-totals (policy/calculate-cumulative-totals [{:px "101"}
                                                         {:sz "2"}])]
    (is (= []
           (policy/calculate-cumulative-totals [])))
    (is (= [{:px "101" :cum-size 0 :cum-value 0}
            {:sz "2" :cum-size 2 :cum-value 0}]
           with-totals))))

(deftest formatting-defaults-and-order-fallbacks-test
  (testing "format-total defaults to whole-number rounding when decimals are omitted"
    (is (= "5"
           (policy/format-total 5.4))))
  (testing "quote sizing falls back missing price or size to zero"
    (is (= 0
           (policy/order-size-for-unit {:sz "3"} :quote)))
    (is (= 0
           (policy/order-size-for-unit {:px "5"} :quote))))
  (testing "quote formatting rounds to whole numbers while base formatting keeps raw strings"
    (is (= "5"
           (policy/format-order-size {:px "2.55" :sz "2"} :quote)))
    (is (= "2.50000000"
           (policy/format-order-size {:sz "2.50000000" :sz-num 2.5} :base)))
    (is (= "2.5"
           (policy/format-order-size {:sz 2.5} :base)))
    (is (= "5"
           (policy/format-order-total {:cum-value 5.1} :quote)))))

(deftest cumulative-defaults-stay-zero-based-test
  (testing "missing cumulative totals are treated as zero when taking maxima"
    (is (= 0.5
           (policy/get-max-cumulative-total [{:cum-size nil}
                                             {:cum-size 0.5}]
                                            :base))))
  (testing "bar widths only render for positive maxima and keep zero fallback styling"
    (is (= 50
           (policy/cumulative-bar-width 1 2)))
    (is (= 100
           (policy/cumulative-bar-width 1 1)))
    (is (nil? (policy/cumulative-bar-width 1 0)))
    (is (= "0%"
           (policy/cumulative-bar-width-style 1 0)))))

(deftest build-book-one-sided-max-totals-do-not-inflate-missing-side-test
  (testing "missing ask totals do not override small bid maxima"
    (let [book (policy/build-book [{:px "0.2" :sz "0.5"}] [] 1)]
      (is (= {:base 0.5 :quote 0.1}
             (get-in book [:render :max-total-by-unit])))))
  (testing "missing bid totals do not override small ask maxima"
    (let [book (policy/build-book [] [{:px "0.2" :sz "0.25"}] 1)]
      (is (= {:base 0.25 :quote 0.05}
             (get-in book [:render :max-total-by-unit]))))))

(deftest build-book-test
  (let [book (policy/build-book [{:px "100" :sz "2"}
                                 {:px "99" :sz "1"}]
                                [{:px "101" :sz "4"}
                                 {:px "102" :sz "5"}]
                                1)]
    (testing "legacy keys remain sorted in compatibility order"
      (is (= [{:px "100" :sz "2"} {:px "99" :sz "1"}]
             (:bids book)))
      (is (= [{:px "102" :sz "5"} {:px "101" :sz "4"}]
             (:asks book))))
    (testing "render snapshot stores limited display and cumulative slices"
      (is (= [{:px "100" :sz "2" :px-num 100 :sz-num 2}]
             (get-in book [:render :display-bids])))
      (is (= [{:px "101" :sz "4" :px-num 101 :sz-num 4}]
             (get-in book [:render :display-asks])))
      (is (= [{:px "100" :sz "2" :px-num 100 :sz-num 2 :cum-size 2 :cum-value 200}]
             (get-in book [:render :bids-with-totals])))
      (is (= [{:px "101" :sz "4" :px-num 101 :sz-num 4 :cum-size 4 :cum-value 404}]
             (get-in book [:render :asks-with-totals])))
      (is (= {:px "100" :sz "2" :px-num 100 :sz-num 2}
             (get-in book [:render :best-bid])))
      (is (= {:px "101" :sz "4" :px-num 101 :sz-num 4}
             (get-in book [:render :best-ask]))))
    (testing "render snapshot includes branch-independent precomputed metadata"
      (is (= 1
             (get-in book [:render :spread :absolute])))
      (is (= "1"
             (get-in book [:render :spread :absolute-label])))
      (is (= "0.990%"
             (get-in book [:render :spread :percentage-label])))
      (is (= {:base 4 :quote 404}
             (get-in book [:render :max-total-by-unit])))
      (is (not (contains? (:render book) :desktop-bids)))
      (is (not (contains? (:render book) :desktop-asks)))
      (is (not (contains? (:render book) :mobile-pairs))))))

(deftest build-render-snapshot-can-trim-inactive-responsive-branch-test
  (let [bids [{:px "100" :sz "2"} {:px "99" :sz "1"}]
        asks [{:px "101" :sz "4"} {:px "102" :sz "5"}]
        mobile-snapshot (policy/build-render-snapshot bids
                                                      asks
                                                      2
                                                      {:visible-branch :mobile})
        desktop-snapshot (policy/build-render-snapshot bids
                                                       asks
                                                       2
                                                       {:visible-branch :desktop})]
    (testing "mobile-only snapshots keep mobile pairs and skip desktop rows"
      (is (contains? mobile-snapshot :mobile-pairs))
      (is (not (contains? mobile-snapshot :desktop-bids)))
      (is (not (contains? mobile-snapshot :desktop-asks)))
      (is (= ["100" "99"]
             (mapv (fn [{:keys [bid]}]
                     (:px bid))
                   (:mobile-pairs mobile-snapshot)))))
    (testing "desktop-only snapshots keep ladder rows and skip mobile pairs"
      (is (contains? desktop-snapshot :desktop-bids))
      (is (contains? desktop-snapshot :desktop-asks))
      (is (not (contains? desktop-snapshot :mobile-pairs)))
      (is (= ["100" "99"]
             (mapv :px (:desktop-bids desktop-snapshot))))
      (is (= ["102" "101"]
             (mapv :px (:desktop-asks desktop-snapshot))))
      (let [desktop-bid (get-in desktop-snapshot [:desktop-bids 0])
            desktop-ask (get-in desktop-snapshot [:desktop-asks 0])]
        (is (= :bid (:side desktop-bid)))
        (is (= "bid-100" (:row-key desktop-bid)))
        (is (= "100" (get-in desktop-bid [:display :price])))
        (is (= "2" (get-in desktop-bid [:display :size :base])))
        (is (= "200" (get-in desktop-bid [:display :size :quote])))
        (is (= "2" (get-in desktop-bid [:display :total :base])))
        (is (= "200" (get-in desktop-bid [:display :total :quote])))
        (is (= "22.22222222222222%" (get-in desktop-bid [:display :bar-width :base])))
        (is (= :ask (:side desktop-ask)))
        (is (= "ask-102" (:row-key desktop-ask)))
        (is (= "102" (get-in desktop-ask [:display :price])))
        (is (= "5" (get-in desktop-ask [:display :size :base])))
        (is (= "510" (get-in desktop-ask [:display :size :quote])))
        (is (= "9" (get-in desktop-ask [:display :total :base])))
        (is (= "914" (get-in desktop-ask [:display :total :quote])))
        (is (= "100%" (get-in desktop-ask [:display :bar-width :quote])))))))

(deftest build-book-ask-derivation-compatibility-test
  (let [book (policy/build-book [{:px "100" :sz "1"}]
                                [{:px "103" :sz "1"}
                                 {:px "101" :sz "1"}
                                 {:px "102" :sz "1"}]
                                2)]
    (is (= [{:px "103" :sz "1"}
            {:px "102" :sz "1"}
            {:px "101" :sz "1"}]
           (:asks book)))
    (is (= [{:px "101" :sz "1" :px-num 101 :sz-num 1}
            {:px "102" :sz "1" :px-num 102 :sz-num 1}]
           (get-in book [:render :display-asks])))
    (is (= {:px "101" :sz "1" :px-num 101 :sz-num 1}
           (get-in book [:render :best-ask])))))

(deftest build-book-invalid-max-levels-falls-back-to-default-depth-limit-test
  (let [bids (mapv (fn [n]
                     {:px (str n)
                     :sz "1"})
                   (range 100 0 -1))
        asks (mapv (fn [n]
                     {:px (str n)
                      :sz "1"})
                   (range 201 301))
        book (policy/build-book bids asks 0)]
    (is (= 100 (count (:bids book))))
    (is (= 100 (count (:asks book))))
    (is (= policy/default-max-render-levels-per-side
           (count (get-in book [:render :display-bids]))))
    (is (= policy/default-max-render-levels-per-side
           (count (get-in book [:render :display-asks]))))
    (is (= 100
           (get-in book [:render :best-bid :px-num])))
    (is (= 201
           (get-in book [:render :best-ask :px-num])))
    (is (= 100
           (get-in book [:render :display-bids 0 :px-num])))
    (is (= 21
           (get-in book [:render :display-bids 79 :px-num])))
    (is (= 201
           (get-in book [:render :display-asks 0 :px-num])))
    (is (= 280
           (get-in book [:render :display-asks 79 :px-num])))))

(deftest same-render-book-ignores-timestamp-only-differences-test
  (let [book-a {:bids [{:px "100" :sz "2"}]
                :asks [{:px "101" :sz "3"}]
                :render {:best-bid {:px "100" :sz "2"}
                         :best-ask {:px "101" :sz "3"}}
                :timestamp 1}
        book-b (assoc book-a :timestamp 2)
        book-c (assoc-in book-a [:bids 0 :sz] "4")]
    (is (true? (policy/same-render-book? book-a book-b)))
    (is (false? (policy/same-render-book? book-a book-c)))))

(deftest normalize-aggregation-config-test
  (is (= {:nSigFigs 4}
         (policy/normalize-aggregation-config {:nSigFigs 4})))
  (is (= {}
         (policy/normalize-aggregation-config {:nSigFigs 6})))
  (is (= {}
         (policy/normalize-aggregation-config {}))))

(deftest build-subscription-test
  (is (= {:type "l2Book" :coin "BTC" :nSigFigs 3}
         (policy/build-subscription "BTC" {:nSigFigs 3})))
  (is (= {:type "l2Book" :coin "ETH"}
         (policy/build-subscription "ETH" {:nSigFigs 8}))))
