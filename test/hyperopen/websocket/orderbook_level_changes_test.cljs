(ns hyperopen.websocket.orderbook-level-changes-test
  "Per-level change detection that drives the order book's change flash, the shared
   ladder price precision, and the depth-bar scale fraction."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.platform :as platform]
            [hyperopen.websocket.market-projection-runtime :as market-runtime]
            [hyperopen.websocket.orderbook :as orderbook]
            [hyperopen.websocket.orderbook-policy :as policy]))

(defn- book [bids asks]
  (policy/build-book bids asks))

(deftest book-price-decimals-uses-the-most-precise-visible-level-test
  (testing "a whole-dollar tick carries no fraction digits"
    (is (= 0 (policy/book-price-decimals [{:px "29899"} {:px "29900"}]))))
  (testing "one level with a fraction sets the precision for the whole ladder"
    (is (= 1 (policy/book-price-decimals [{:px "3877"} {:px "3876.5"}]))))
  (testing "trailing zeros in the raw price are not precision"
    (is (= 5 (policy/book-price-decimals [{:px "0.012340"}]))))
  (testing "precision is capped"
    (is (= 8 (policy/book-price-decimals [{:px "0.0000000001234"}]))))
  (testing "an empty ladder has no fraction digits"
    (is (= 0 (policy/book-price-decimals [])))))

(deftest ladder-prices-and-spread-share-one-precision-test
  (let [snapshot (policy/build-render-snapshot [{:px "3876.5" :sz "1"}]
                                               [{:px "3877" :sz "2"}]
                                               5
                                               {:visible-branch :desktop})]
    (is (= "3,876.5" (get-in snapshot [:desktop-bids 0 :display :price])))
    (is (= "3,877.0" (get-in snapshot [:desktop-asks 0 :display :price])))
    (is (= "0.5" (get-in snapshot [:spread :absolute-label])))))

(deftest whole-dollar-ladder-drops-the-cents-test
  (let [snapshot (policy/build-render-snapshot [{:px "29899" :sz "1"}]
                                               [{:px "29900" :sz "2"}]
                                               5
                                               {:visible-branch :desktop})]
    (is (= "29,899" (get-in snapshot [:desktop-bids 0 :display :price])))
    (is (= "29,900" (get-in snapshot [:desktop-asks 0 :display :price])))
    (is (= "1" (get-in snapshot [:spread :absolute-label])))))

(deftest depth-bar-scale-is-a-clamped-fraction-of-the-deepest-side-test
  (let [snapshot (policy/build-render-snapshot [{:px "100" :sz "1"} {:px "99" :sz "3"}]
                                               [{:px "101" :sz "2"}]
                                               5
                                               {:visible-branch :desktop})]
    (is (= 0.25 (get-in snapshot [:desktop-bids 0 :display :bar-scale :base])))
    (is (= 1 (get-in snapshot [:desktop-bids 1 :display :bar-scale :base])))
    (is (= 0.5 (get-in snapshot [:desktop-asks 0 :display :bar-scale :base]))))
  (is (= 0 (policy/cumulative-bar-scale nil 10)))
  (is (= 0 (policy/cumulative-bar-scale 5 0)))
  (is (= 1 (policy/cumulative-bar-scale 15 10))))

(deftest level-changes-reports-new-and-resized-levels-by-price-test
  (let [previous (book [{:px "100" :sz "2"} {:px "99" :sz "1"}]
                       [{:px "101" :sz "3"} {:px "102" :sz "4"}])
        next-book (book [{:px "100" :sz "5"} {:px "99" :sz "1"} {:px "98" :sz "7"}]
                        [{:px "102" :sz "4"}])]
    (is (= {:bids #{"100" "98"}
            :asks #{}}
           (policy/level-changes previous next-book)))))

(deftest level-changes-ignores-a-ladder-shift-when-levels-are-unchanged-test
  ;; The market ticks down: 101 is lifted, every other level keeps its size. Rows
  ;; shift position but only the price that actually changed is reported.
  (let [previous (book [{:px "100" :sz "2"} {:px "99" :sz "1"}]
                       [{:px "101" :sz "3"} {:px "102" :sz "4"}])
        next-book (book [{:px "100" :sz "2"} {:px "99" :sz "1"}]
                        [{:px "100.5" :sz "6"} {:px "102" :sz "4"}])]
    (is (= {:bids #{} :asks #{"100.5"}}
           (policy/level-changes previous next-book)))))

(deftest level-changes-reports-nothing-without-a-predecessor-test
  (testing "first book"
    (is (= {:bids #{} :asks #{}}
           (policy/level-changes nil (book [{:px "100" :sz "2"}] [{:px "101" :sz "3"}])))))
  (testing "a book that shares no visible price (coin switch or precision change)"
    (is (= {:bids #{} :asks #{}}
           (policy/level-changes (book [{:px "100" :sz "2"}] [{:px "101" :sz "3"}])
                                 (book [{:px "50" :sz "2"}] [{:px "60" :sz "3"}]))))))

(deftest annotate-level-changes-advances-the-version-only-on-change-test
  (let [first-book (policy/annotate-level-changes
                    nil
                    (book [{:px "100" :sz "2"}] [{:px "101" :sz "3"}]))
        changed (policy/annotate-level-changes
                 first-book
                 (book [{:px "100" :sz "4"}] [{:px "101" :sz "3"}]))
        unchanged (policy/annotate-level-changes
                   changed
                   (book [{:px "100" :sz "4"}] [{:px "101" :sz "3"}]))]
    (is (= 0 (:flash-version first-book)))
    (is (= 1 (:flash-version changed)))
    (is (= #{"100"} (get-in changed [:changed-levels :bids])))
    (is (= 1 (:flash-version unchanged)))
    (is (= {:bids #{} :asks #{}} (:changed-levels unchanged)))
    (testing "annotations alone do not make a book render differently"
      (is (true? (policy/same-render-book? changed unchanged))))))

(deftest published-book-carries-level-changes-into-the-store-test
  (market-runtime/reset-market-projection-runtime!)
  (reset! orderbook/orderbook-state {:subscriptions {}
                                     :l2-subscriptions {}
                                     :l2-sides {}
                                     :books {}})
  (try
    (let [store (atom {:orderbooks {}})
          callbacks (atom [])
          flush! (fn []
                   (let [pending @callbacks]
                     (reset! callbacks [])
                     (doseq [f pending] (f 16))))
          payload (fn [time bid-size]
                    {:channel "l2Book"
                     :data {:coin "BTC"
                            :levels [[{:px "100" :sz bid-size}]
                                     [{:px "101" :sz "3"}]]
                            :time time}})]
      (with-redefs [platform/request-animation-frame! (fn [f]
                                                        (swap! callbacks conj f)
                                                        :raf)]
        (let [handler (orderbook/create-orderbook-data-handler store)]
          (handler (payload 1 "2"))
          (flush!)
          (is (= 0 (get-in @store [:orderbooks "BTC" :flash-version])))
          (handler (payload 2 "5"))
          (flush!)
          (is (= 1 (get-in @store [:orderbooks "BTC" :flash-version])))
          (is (= {:bids #{"100"} :asks #{}}
                 (get-in @store [:orderbooks "BTC" :changed-levels]))))))
    (finally
      (reset! orderbook/orderbook-state {:subscriptions {}
                                         :l2-subscriptions {}
                                         :l2-sides {}
                                         :books {}})
      (market-runtime/reset-market-projection-runtime!))))
