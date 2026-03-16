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
             (get-in book [:render :best-ask]))))))

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
