(ns hyperopen.views.account-info-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.account-info-view :as view]))

(defn- class-values [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) (remove str/blank? (str/split class-attr #"\s+"))
    (sequential? class-attr) (mapcat class-values class-attr)
    :else []))

(defn- classes-from-tag [tag]
  (if (keyword? tag)
    (let [parts (str/split (name tag) #"\.")]
      (if (> (count parts) 1)
        (rest parts)
        []))
    []))

(defn- node-class-set [node]
  (let [attrs (when (and (vector? node) (map? (second node)))
                (second node))
        classes (concat (classes-from-tag (first node))
                        (class-values (:class attrs)))]
    (set classes)))

(defn- node-children [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- direct-texts [node]
  (->> (node-children node)
       (filter string?)
       set))

(defn- find-first-node [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- first-data-row [table-node]
  (->> (node-children table-node)
       rest
       (mapcat #(if (seq? %) % [%]))
       first))

(def sample-balance-row
  {:key "spot-0"
   :coin "USDC (Spot)"
   :total-balance 150.12
   :available-balance 120.45
   :usdc-value 150.12
   :pnl-value -1.2
   :pnl-pct -0.8
   :amount-decimals 2})

(def default-sort-state {:column nil :direction :asc})

(def sample-position-data
  {:position {:coin "HYPE"
              :leverage {:value 5}
              :szi "12.34"
              :positionValue "85081.58"
              :entryPx "34.51"
              :unrealizedPnl "-8206.13"
              :returnOnEquity "-0.088"
              :liquidationPx "12.10"
              :marginUsed "2400"
              :cumFunding {:allTime "10.0"}}})

(deftest balances-header-contrast-test
  (let [header-node (view/balance-table-header default-sort-state)
        sortable-node (view/sortable-balances-header "Coin" default-sort-state)
        sortable-left-node (view/sortable-balances-header "Total Balance" default-sort-state :left)
        non-sortable-node (view/non-sortable-header "Send")
        non-sortable-center-node (view/non-sortable-header "Send" :center)]
    (is (contains? (node-class-set header-node) "text-trading-text"))
    (is (contains? (node-class-set sortable-node) "text-trading-text-secondary"))
    (is (contains? (node-class-set sortable-node) "justify-start"))
    (is (contains? (node-class-set sortable-left-node) "justify-start"))
    (is (contains? (node-class-set sortable-node) "hover:text-trading-text"))
    (is (contains? (node-class-set non-sortable-node) "text-trading-text-secondary"))
    (is (contains? (node-class-set non-sortable-center-node) "justify-center"))))

(deftest position-headers-use-secondary-text-and-hover-affordance-test
  (let [position-header-node (view/position-table-header default-sort-state)
        position-coin-header (find-first-node position-header-node
                                              #(contains? (direct-texts %) "Coin"))
        sortable-node (view/sortable-header "Coin" default-sort-state)]
    (is (some? position-coin-header))
    (is (contains? (node-class-set sortable-node) "text-trading-text-secondary"))
    (is (contains? (node-class-set sortable-node) "hover:text-trading-text"))))

(deftest open-orders-sortable-header-uses-secondary-text-and-hover-affordance-test
  (let [header-node (view/sortable-open-orders-header "Time" {:column "Time" :direction :asc})]
    (is (contains? (node-class-set header-node) "text-trading-text-secondary"))
    (is (contains? (node-class-set header-node) "hover:text-trading-text"))))

(deftest tab-navigation-renders-hide-small-toggle-only-on-balances-tab-test
  (let [counts {:balances 1 :positions 1}
        balances-nav (view/tab-navigation :balances counts true)
        positions-nav (view/tab-navigation :positions counts true)
        balances-toggle-input (find-first-node balances-nav
                                               #(= "hide-small-balances"
                                                   (get-in % [1 :id])))
        balances-toggle-classes (node-class-set balances-toggle-input)
        balances-toggle-label (find-first-node balances-nav
                                               #(contains? (direct-texts %) "Hide Small Balances"))
        positions-toggle-input (find-first-node positions-nav
                                                #(= "hide-small-balances"
                                                    (get-in % [1 :id])))]
    (is (contains? (node-class-set balances-nav) "justify-between"))
    (is (some? balances-toggle-input))
    (is (true? (get-in balances-toggle-input [1 :checked])))
    (is (contains? balances-toggle-classes "trade-toggle-checkbox"))
    (is (contains? balances-toggle-classes "h-4"))
    (is (contains? balances-toggle-classes "w-4"))
    (is (contains? (node-class-set balances-toggle-label) "text-trading-text"))
    (is (nil? positions-toggle-input))))

(deftest balances-tab-content-does-not-render-legacy-subheader-row-test
  (let [content (view/balances-tab-content [sample-balance-row] false default-sort-state)
        title-node (find-first-node content #(contains? (direct-texts %) "Balances ("))
        filter-label-node (find-first-node content #(contains? (direct-texts %) "Hide Small Balances"))]
    (is (nil? title-node))
    (is (nil? filter-label-node))))

(deftest balance-row-primary-value-and-action-contrast-test
  (let [row-node (view/balance-row sample-balance-row)
        coin-node (find-first-node row-node #(contains? (direct-texts %) "USDC (Spot)"))
        send-button-node (find-first-node row-node #(contains? (direct-texts %) "Send"))
        transfer-button-node (find-first-node row-node #(contains? (direct-texts %) "Transfer"))]
    (is (contains? (node-class-set row-node) "text-trading-text"))
    (is (contains? (node-class-set coin-node) "font-semibold"))
    (is (contains? (node-class-set send-button-node) "text-trading-text"))
    (is (contains? (node-class-set transfer-button-node) "text-trading-text"))))

(deftest balance-pnl-color-and-placeholder-contrast-test
  (testing "pnl uses success/error colors and white placeholders"
    (let [positive (view/format-pnl 2.0 1.5)
          negative (view/format-pnl -3.0 -2.0)
          zero (view/format-pnl 0 0)
          missing (view/format-pnl nil nil)]
      (is (contains? (node-class-set positive) "text-success"))
      (is (contains? (node-class-set negative) "text-error"))
      (is (contains? (node-class-set zero) "text-trading-text"))
      (is (contains? (node-class-set missing) "text-trading-text")))))

(deftest balances-and-positions-values-use-semibold-weight-test
  (let [balance-row-node (view/balance-row sample-balance-row)
        position-row-node (view/position-row sample-position-data)
        balance-value-node (find-first-node balance-row-node
                                            #(let [classes (node-class-set %)]
                                               (and (contains? classes "text-left")
                                                    (contains? classes "font-semibold"))))
        position-value-node (find-first-node position-row-node
                                             #(let [classes (node-class-set %)]
                                                (and (contains? classes "text-left")
                                                     (contains? classes "font-semibold"))))]
    (is (some? balance-value-node))
    (is (some? position-value-node))))

(deftest position-table-columns-are-left-aligned-test
  (let [header-node (view/position-table-header default-sort-state)
        header-cells (vec (node-children header-node))
        row-node (view/position-row sample-position-data)
        row-cells (vec (node-children row-node))]
    (doseq [idx (range 1 11)]
      (is (contains? (node-class-set (nth header-cells idx)) "text-left")))
    (doseq [idx (range 1 11)]
      (is (contains? (node-class-set (nth row-cells idx)) "text-left")))))

(deftest open-orders-columns-are-left-aligned-test
  (let [open-orders [{:oid 101
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
                      :is-position-tpsl false}]
        content (view/open-orders-tab-content open-orders {:column "Time" :direction :desc})
        header-node (first (node-children content))
        header-cells (vec (node-children header-node))
        row-node (first-data-row content)
        row-cells (vec (node-children row-node))]
    (doseq [idx [4 5 6 7 8 9 10 11]]
      (is (contains? (node-class-set (nth header-cells idx)) "text-left")))
    (doseq [idx [4 5 6 7 8 9 10 11]]
      (is (contains? (node-class-set (nth row-cells idx)) "text-left")))))

(deftest history-tables-columns-are-left-aligned-test
  (let [fills [{:tid 1 :coin "HYPE" :side "B" :sz "1.2" :px "100.0" :fee "0.1" :time 1700000000000}]
        fundings [{:coin "HYPE" :fundingRate "0.001" :payment "1.23" :positionSize "100.0" :time 1700000000000}]
        ledger [{:type "deposit" :coin "USDC" :delta "5.0" :time 1700000000000}]
        trade-node (view/trade-history-tab-content fills)
        trade-header-cells (vec (node-children (first (node-children trade-node))))
        trade-row-cells (vec (node-children (first-data-row trade-node)))
        funding-node (view/funding-history-tab-content fundings)
        funding-header-cells (vec (node-children (first (node-children funding-node))))
        funding-row-cells (vec (node-children (first-data-row funding-node)))
        order-node (view/order-history-tab-content ledger)
        order-header-cells (vec (node-children (first (node-children order-node))))
        order-row-cells (vec (node-children (first-data-row order-node)))]
    (doseq [idx (range 1 6)]
      (is (contains? (node-class-set (nth trade-header-cells idx)) "text-left"))
      (is (contains? (node-class-set (nth trade-row-cells idx)) "text-left")))
    (doseq [idx (range 1 5)]
      (is (contains? (node-class-set (nth funding-header-cells idx)) "text-left"))
      (is (contains? (node-class-set (nth funding-row-cells idx)) "text-left")))
    (doseq [idx (range 1 4)]
      (is (contains? (node-class-set (nth order-header-cells idx)) "text-left"))
      (is (contains? (node-class-set (nth order-row-cells idx)) "text-left")))))
