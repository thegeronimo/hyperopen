(ns hyperopen.views.account-info.tabs.open-orders.sorting-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info.tabs.open-orders :as open-orders-tab]
            [hyperopen.views.account-info.tabs.open-orders.sorting :as open-orders-sorting]))

(defn- reset-open-orders-sort-cache-fixture
  [f]
  (open-orders-sorting/reset-open-orders-sort-cache!)
  (f)
  (open-orders-sorting/reset-open-orders-sort-cache!))

(use-fixtures :each reset-open-orders-sort-cache-fixture)

(deftest open-orders-tab-content-memoizes-sorting-by-input-signature-and-sort-state-test
  (let [rows [{:oid 1001
               :coin "ETH"
               :side "B"
               :sz "2.0"
               :orig-sz "2.0"
               :px "100.0"
               :type "Limit"
               :time 1700000000000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}]
        sort-state {:column "Time" :direction :desc}
        sort-calls (atom 0)]
    (open-orders-sorting/reset-open-orders-sort-cache!)
    (with-redefs [open-orders-sorting/sort-open-orders-by-column
                  (fn
                    ([orders _column _direction]
                     (swap! sort-calls inc)
                     orders)
                    ([orders _column _direction _market-by-key]
                     (swap! sort-calls inc)
                     orders))]
      (open-orders-tab/open-orders-tab-content rows sort-state)
      (open-orders-tab/open-orders-tab-content rows sort-state)
      (is (= 1 @sort-calls))

      (let [sort-state-asc (assoc sort-state :direction :asc)]
        (open-orders-tab/open-orders-tab-content rows sort-state-asc)
        (open-orders-tab/open-orders-tab-content rows sort-state-asc)
        (is (= 2 @sort-calls))

        (let [churned-rows (into [] rows)]
          (open-orders-tab/open-orders-tab-content churned-rows sort-state-asc)
          (open-orders-tab/open-orders-tab-content churned-rows sort-state-asc)
          (is (= 2 @sort-calls)))

        (let [changed-rows (assoc-in (into [] rows) [0 :px] "101.0")]
          (open-orders-tab/open-orders-tab-content changed-rows sort-state-asc)
          (is (= 3 @sort-calls)))))))

(deftest open-orders-tab-content-re-sorts-and-re-indexes-when-coin-market-labels-change-test
  (let [rows [{:oid 1001
               :coin "@107"
               :side "B"
               :sz "1.0"
               :orig-sz "1.0"
               :px "100.0"
               :type "Limit"
               :time 1700000000000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}
              {:oid 1002
               :coin "@108"
               :side "A"
               :sz "1.0"
               :orig-sz "1.0"
               :px "101.0"
               :type "Limit"
               :time 1699999999000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}]
        sort-state {:column "Coin" :direction :asc}
        market-by-key {"spot:@107" {:coin "@107"
                                    :symbol "ZZZ/USDC"
                                    :base "ZZZ"
                                    :market-type :spot}
                       "spot:@108" {:coin "@108"
                                    :symbol "AAA/USDC"
                                    :base "AAA"
                                    :market-type :spot}}
        equivalent-market (into {} market-by-key)
        changed-market {"spot:@107" {:coin "@107"
                                     :symbol "AAA/USDC"
                                     :base "AAA"
                                     :market-type :spot}
                        "spot:@108" {:coin "@108"
                                     :symbol "ZZZ/USDC"
                                     :base "ZZZ"
                                     :market-type :spot}}
        sort-calls (atom 0)
        index-calls (atom 0)
        original-sort open-orders-sorting/sort-open-orders-by-column
        original-index-builder @#'open-orders-sorting/*build-open-orders-coin-search-index*]
    (open-orders-sorting/reset-open-orders-sort-cache!)
    (with-redefs [open-orders-sorting/sort-open-orders-by-column
                  (fn
                    ([orders column direction]
                     (swap! sort-calls inc)
                     (original-sort orders column direction))
                    ([orders column direction market-by-key*]
                     (swap! sort-calls inc)
                     (original-sort orders column direction market-by-key*)))
                  open-orders-sorting/*build-open-orders-coin-search-index*
                  (fn [sorted-rows market-by-key*]
                    (swap! index-calls inc)
                    (original-index-builder sorted-rows market-by-key*))]
      (open-orders-tab/open-orders-tab-content rows sort-state {:market-by-key market-by-key})
      (open-orders-tab/open-orders-tab-content rows sort-state {:market-by-key market-by-key})
      (is (= 1 @sort-calls))
      (is (= 1 @index-calls))

      (open-orders-tab/open-orders-tab-content rows sort-state {:market-by-key equivalent-market})
      (is (= 1 @sort-calls))
      (is (= 1 @index-calls))

      (let [changed-content (open-orders-tab/open-orders-tab-content rows sort-state {:market-by-key changed-market})
            changed-row (hiccup/first-viewport-row changed-content)
            changed-coin-cell (nth (vec (hiccup/node-children changed-row)) 2)
            changed-strings (set (hiccup/collect-strings changed-coin-cell))]
        (is (= 2 @sort-calls))
        (is (= 2 @index-calls))
        (is (contains? changed-strings "AAA"))
        (is (not (contains? changed-strings "ZZZ")))))))

(deftest open-orders-tab-content-filters-rows-by-direction-filter-test
  (let [rows [{:oid 1001
               :coin "LONGCOIN"
               :side "B"
               :sz "1.0"
               :orig-sz "1.0"
               :px "100.0"
               :type "Limit"
               :time 1700000002000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}
              {:oid 1002
               :coin "SHORTA"
               :side "A"
               :sz "2.0"
               :orig-sz "2.0"
               :px "99.0"
               :type "Limit"
               :time 1700000001000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}
              {:oid 1003
               :coin "SHORTS"
               :side "S"
               :sz "3.0"
               :orig-sz "3.0"
               :px "98.0"
               :type "Limit"
               :time 1700000000000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}]
        sort-state {:column "Time" :direction :desc}
        all-content (open-orders-tab/open-orders-tab-content rows sort-state {:direction-filter :all})
        long-content (open-orders-tab/open-orders-tab-content rows sort-state {:direction-filter :long})
        short-content (open-orders-tab/open-orders-tab-content rows sort-state {:direction-filter :short})
        all-row-count (count (vec (hiccup/node-children (hiccup/tab-rows-viewport-node all-content))))
        long-row-count (count (vec (hiccup/node-children (hiccup/tab-rows-viewport-node long-content))))
        short-row-count (count (vec (hiccup/node-children (hiccup/tab-rows-viewport-node short-content))))
        long-text (set (hiccup/collect-strings long-content))
        short-text (set (hiccup/collect-strings short-content))]
    (is (= 3 all-row-count))
    (is (= 1 long-row-count))
    (is (= 2 short-row-count))
    (is (contains? long-text "LONGCOIN"))
    (is (not (contains? long-text "SHORTA")))
    (is (not (contains? long-text "SHORTS")))
    (is (contains? short-text "SHORTA"))
    (is (contains? short-text "SHORTS"))
    (is (not (contains? short-text "LONGCOIN")))))

(deftest open-orders-tab-content-filters-rows-by-coin-search-test
  (let [rows [{:oid 1001
               :coin "xyz:NVDA"
               :side "B"
               :sz "1.0"
               :orig-sz "1.0"
               :px "100.0"
               :type "Limit"
               :time 1700000002000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}
              {:oid 1002
               :coin "HYPE"
               :side "A"
               :sz "2.0"
               :orig-sz "2.0"
               :px "99.0"
               :type "Limit"
               :time 1700000001000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}]
        sort-state {:column "Time" :direction :desc}
        all-content (open-orders-tab/open-orders-tab-content rows sort-state {:coin-search ""})
        symbol-search-content (open-orders-tab/open-orders-tab-content rows sort-state {:coin-search "nv"})
        prefix-search-content (open-orders-tab/open-orders-tab-content rows sort-state {:coin-search "xyz"})
        all-row-count (count (vec (hiccup/node-children (hiccup/tab-rows-viewport-node all-content))))
        symbol-row-count (count (vec (hiccup/node-children (hiccup/tab-rows-viewport-node symbol-search-content))))
        prefix-row-count (count (vec (hiccup/node-children (hiccup/tab-rows-viewport-node prefix-search-content))))
        symbol-text (set (hiccup/collect-strings symbol-search-content))
        prefix-text (set (hiccup/collect-strings prefix-search-content))]
    (is (= 2 all-row-count))
    (is (= 1 symbol-row-count))
    (is (= 1 prefix-row-count))
    (is (contains? symbol-text "NVDA"))
    (is (not (contains? symbol-text "HYPE")))
    (is (contains? prefix-text "NVDA"))
    (is (contains? prefix-text "xyz"))))

(deftest open-orders-tab-content-re-sorts-when-direction-filter-changes-but-not-when-only-coin-search-changes-test
  (let [rows [{:oid 1001
               :coin "ETH"
               :side "B"
               :sz "2.0"
               :orig-sz "2.0"
               :px "100.0"
               :type "Limit"
               :time 1700000000000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}
              {:oid 1002
               :coin "BTC"
               :side "A"
               :sz "1.0"
               :orig-sz "1.0"
               :px "99.0"
               :type "Limit"
               :time 1699999999000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}]
        sort-state {:column "Time" :direction :desc}
        sort-calls (atom 0)]
    (open-orders-sorting/reset-open-orders-sort-cache!)
    (with-redefs [open-orders-sorting/sort-open-orders-by-column
                  (fn
                    ([orders _column _direction]
                     (swap! sort-calls inc)
                     orders)
                    ([orders _column _direction _market-by-key]
                     (swap! sort-calls inc)
                     orders))]
      (open-orders-tab/open-orders-tab-content rows sort-state {:direction-filter :all})
      (open-orders-tab/open-orders-tab-content rows sort-state {:direction-filter :all})
      (is (= 1 @sort-calls))
      (open-orders-tab/open-orders-tab-content rows sort-state {:direction-filter :short})
      (open-orders-tab/open-orders-tab-content rows sort-state {:direction-filter :short})
      (is (= 2 @sort-calls))
      (open-orders-tab/open-orders-tab-content rows sort-state {:direction-filter :short
                                                                :coin-search "eth"})
      (open-orders-tab/open-orders-tab-content rows sort-state {:direction-filter :short
                                                                :coin-search "eth"})
      (is (= 2 @sort-calls)))))
