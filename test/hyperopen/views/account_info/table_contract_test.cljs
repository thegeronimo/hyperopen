(ns hyperopen.views.account-info.table-contract-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info.tabs.open-orders :as open-orders-tab]
            [hyperopen.views.account-info-view :as view]))

(defn- sample-open-orders
  []
  [{:oid 101
    :coin "HYPE"
    :side "B"
    :sz "2.0"
    :orig-sz "2.0"
    :px "100.0"
    :type "Limit"
    :time 1700000000000
    :reduce-only true
    :is-trigger false
    :trigger-condition nil
    :is-position-tpsl false}])

(defn- sample-fills
  []
  [{:tid 1 :coin "HYPE" :side "B" :sz "1.2" :px "100.0" :fee "0.1" :time 1700000000000}])

(defn- sample-fundings
  []
  [{:coin "HYPE" :fundingRate "0.001" :payment "1.23" :positionSize "100.0" :time 1700000000000}])

(defn- sample-order-history
  []
  [fixtures/sample-order-history-row])

(defn- tab-contents
  []
  [(view/balances-tab-content [fixtures/sample-balance-row] false fixtures/default-sort-state)
   (view/positions-tab-content {:webdata2 {:clearinghouseState {:assetPositions [fixtures/sample-position-data]}}
                                :sort-state fixtures/default-sort-state
                                :perp-dex-states {}})
   (open-orders-tab/open-orders-tab-content (sample-open-orders) {:column "Time" :direction :desc})
   (view/trade-history-tab-content (sample-fills))
   (view/funding-history-tab-content (sample-fundings))
   (view/order-history-tab-content (sample-order-history))])

(defn- keyed-tab-contents
  []
  [[:balances (view/balances-tab-content [fixtures/sample-balance-row] false fixtures/default-sort-state)]
   [:positions (view/positions-tab-content {:webdata2 {:clearinghouseState {:assetPositions [fixtures/sample-position-data]}}
                                            :sort-state fixtures/default-sort-state
                                            :perp-dex-states {}})]
   [:open-orders (open-orders-tab/open-orders-tab-content (sample-open-orders) {:column "Time" :direction :desc})]
   [:trade-history (view/trade-history-tab-content (sample-fills))]
   [:funding-history (view/funding-history-tab-content (sample-fundings))]
   [:order-history (view/order-history-tab-content (sample-order-history))]])

(deftest tab-content-uses-scrollable-row-viewport-test
  (doseq [content (tab-contents)
          :let [rows-viewport-classes (hiccup/node-class-set (hiccup/tab-rows-viewport-node content))]]
    (is (contains? rows-viewport-classes "flex-1"))
    (is (contains? rows-viewport-classes "min-h-0"))
    (is (contains? rows-viewport-classes "min-w-0"))
    (is (contains? rows-viewport-classes "overflow-auto"))
    (is (contains? rows-viewport-classes "scrollbar-hide"))))

(deftest account-info-table-rows-use-hover-highlight-without-divider-lines-test
  (doseq [content (tab-contents)
          :let [row-classes (hiccup/node-class-set (hiccup/first-viewport-row content))]]
    (is (contains? row-classes "hover:bg-base-300"))
    (is (not (contains? row-classes "border-b")))
    (is (not (contains? row-classes "border-base-300")))))

(deftest account-info-table-headers-remove-divider-lines-test
  (doseq [content (tab-contents)
          :let [header-classes (hiccup/node-class-set (hiccup/tab-header-node content))]]
    (is (not (contains? header-classes "border-b")))
    (is (not (contains? header-classes "border-base-300")))))

(deftest account-info-table-rows-use-compact-density-classes-test
  (doseq [[tab-key content] (keyed-tab-contents)
          :let [row-classes (hiccup/node-class-set (hiccup/first-viewport-row content))]]
    (if (= tab-key :positions)
      (do
        (is (contains? row-classes "py-0"))
        (is (contains? row-classes "pr-3"))
        (is (not (contains? row-classes "px-3"))))
      (do
        (is (contains? row-classes "py-px"))
        (is (contains? row-classes "px-3"))))
    (if (= tab-key :balances)
      (is (contains? row-classes "gap-x-4"))
      (is (contains? row-classes "gap-2")))))

(deftest account-info-table-headers-use-compact-density-classes-test
  (doseq [[tab-key content] (keyed-tab-contents)
          :let [header-classes (hiccup/node-class-set (hiccup/tab-header-node content))]]
    (is (contains? header-classes "py-1"))
    (if (= tab-key :positions)
      (do
        (is (contains? header-classes "pr-3"))
        (is (not (contains? header-classes "px-3"))))
      (is (contains? header-classes "px-3")))
    (if (= tab-key :balances)
      (is (contains? header-classes "gap-x-4"))
      (is (contains? header-classes "gap-2")))))

(deftest account-info-numeric-cells-use-num-utility-test
  (let [balance-node (view/balance-row fixtures/sample-balance-row)
        balance-cells (vec (hiccup/node-children balance-node))
        position-node (view/position-row fixtures/sample-position-data)
        position-cells (vec (hiccup/node-children position-node))]
    (doseq [idx [1 2 3 4]]
      (is (contains? (hiccup/node-class-set (nth balance-cells idx)) "num")))
    (doseq [idx [1 2 3 4 5 6 7 8]]
      (is (contains? (hiccup/node-class-set (nth position-cells idx)) "num")))))

(deftest history-tables-trade-history-left-aligns-value-columns-test
  (let [trade-node (view/trade-history-tab-content (sample-fills))
        trade-header-cells (vec (hiccup/node-children (hiccup/tab-header-node trade-node)))
        trade-row-cells (vec (hiccup/node-children (hiccup/first-viewport-row trade-node)))
        funding-node (view/funding-history-tab-content (sample-fundings))
        funding-header-cells (vec (hiccup/node-children (hiccup/tab-header-node funding-node)))
        funding-row-cells (vec (hiccup/node-children (hiccup/first-viewport-row funding-node)))
        order-node (view/order-history-tab-content (sample-order-history))
        order-header-cells (vec (hiccup/node-children (hiccup/tab-header-node order-node)))
        order-row-cells (vec (hiccup/node-children (hiccup/first-viewport-row order-node)))]
    (doseq [idx [1 2 3 4 5 6 7]]
      (is (contains? (hiccup/node-class-set (nth trade-header-cells idx)) "text-left"))
      (is (contains? (hiccup/node-class-set (nth trade-row-cells idx)) "text-left")))
    (doseq [idx [3 4 5 6 7]]
      (is (not (contains? (hiccup/node-class-set (nth trade-row-cells idx)) "num-right"))))
    (doseq [idx [1 2 3 4 5]]
      (is (contains? (hiccup/node-class-set (nth funding-header-cells idx)) "text-left"))
      (is (contains? (hiccup/node-class-set (nth funding-row-cells idx)) "text-left")))
    (doseq [idx [1 2 3 4 5 6 7 8 9 10 11 12]]
      (is (contains? (hiccup/node-class-set (nth order-header-cells idx)) "text-left"))
      (is (contains? (hiccup/node-class-set (nth order-row-cells idx)) "text-left")))
    (doseq [idx [4 5 6 7]]
      (is (not (contains? (hiccup/node-class-set (nth order-header-cells idx)) "text-right")))
      (is (not (contains? (hiccup/node-class-set (nth order-row-cells idx)) "text-right")))
      (is (not (contains? (hiccup/node-class-set (nth order-row-cells idx)) "num-right"))))))

(deftest balances-table-left-aligns-desktop-value-columns-test
  (let [balances-node (view/balances-tab-content [fixtures/sample-balance-row] false fixtures/default-sort-state)
        total-header (view/sortable-balances-header "Total Balance" fixtures/default-sort-state :left)
        available-header (view/sortable-balances-header "Available Balance" fixtures/default-sort-state :left)
        usdc-header (view/sortable-balances-header "USDC Value" fixtures/default-sort-state :left)
        pnl-header (view/sortable-balances-header "PNL (ROE %)" fixtures/default-sort-state :left)
        row-cells (vec (hiccup/node-children (hiccup/first-viewport-row balances-node)))]
    (doseq [header-node [total-header available-header usdc-header pnl-header]]
      (is (contains? (hiccup/node-class-set header-node) "justify-start")))
    (doseq [idx [1 2 3 4]]
      (is (contains? (hiccup/node-class-set (nth row-cells idx)) "text-left"))
      (is (not (contains? (hiccup/node-class-set (nth row-cells idx)) "text-right")))
      (is (not (contains? (hiccup/node-class-set (nth row-cells idx)) "num-right"))))))
