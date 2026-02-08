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

(defn- find-all-nodes [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)
          self-match (when (pred node) [node])]
      (into (or self-match [])
            (mapcat #(find-all-nodes % pred) children)))

    (seq? node)
    (mapcat #(find-all-nodes % pred) node)

    :else []))

(defn- count-nodes [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)
          self-count (if (pred node) 1 0)]
      (+ self-count (reduce + 0 (map #(count-nodes % pred) children))))

    (seq? node)
    (reduce + 0 (map #(count-nodes % pred) node))

    :else 0))

(defn- tab-header-node [tab-content]
  (first (vec (node-children tab-content))))

(defn- tab-rows-viewport-node [tab-content]
  (second (vec (node-children tab-content))))

(defn- first-viewport-row [tab-content]
  (-> tab-content tab-rows-viewport-node node-children first))

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

(def sample-account-info-state
  {:account-info {:selected-tab :balances
                  :loading false
                  :error nil
                  :hide-small-balances? false
                  :balances-sort default-sort-state
                  :positions-sort default-sort-state
                  :open-orders-sort {:column "Time" :direction :desc}}
   :webdata2 {}
   :orders {:open-orders []
            :open-orders-snapshot []
            :open-orders-snapshot-by-dex {}}
   :spot {:meta nil
          :clearinghouse-state nil}
   :perp-dex-clearinghouse {}})

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

(deftest funding-history-sortable-header-uses-secondary-text-hover-and-action-test
  (let [header-node (view/sortable-funding-history-header "Time" {:column "Time" :direction :asc})
        sort-icon-node (second (vec (node-children header-node)))]
    (is (contains? (node-class-set header-node) "text-trading-text-secondary"))
    (is (contains? (node-class-set header-node) "hover:text-trading-text"))
    (is (= [[:actions/sort-funding-history "Time"]]
           (get-in header-node [1 :on :click])))
    (is (= "↑" (last sort-icon-node)))))

(deftest sort-funding-history-by-column-respects-direction-and-deterministic-fallback-test
  (let [rows [{:id "2"
               :time-ms 2000
               :coin "ETH"
               :position-size-raw 2
               :payment-usdc-raw 0.5
               :funding-rate-raw 0.0002}
              {:id "1"
               :time-ms 1000
               :coin "BTC"
               :position-size-raw -1
               :payment-usdc-raw 1.2
               :funding-rate-raw -0.0001}
              {:id "3"
               :time-ms 1500
               :coin "SOL"
               :position-size-raw 0.5
               :payment-usdc-raw -0.3
               :funding-rate-raw 0.0004}]
        coin-asc (view/sort-funding-history-by-column rows "Coin" :asc)
        payment-desc (view/sort-funding-history-by-column rows "Payment" :desc)
        missing-values [{:id "b" :coin "ETH"}
                        {:id "a" :coin "BTC"}]
        missing-asc (view/sort-funding-history-by-column missing-values "Payment" :asc)]
    (is (= ["BTC" "ETH" "SOL"] (mapv :coin coin-asc)))
    (is (= [1.2 0.5 -0.3] (mapv :payment-usdc-raw payment-desc)))
    (is (= ["a" "b"] (mapv :id missing-asc)))))

(deftest tab-navigation-renders-hide-small-toggle-only-on-balances-tab-test
  (let [counts {:balances 1 :positions 1}
        balances-nav (view/tab-navigation :balances counts true {})
        positions-nav (view/tab-navigation :positions counts true {})
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

(deftest tab-navigation-renders-funding-history-actions-in-right-controls-test
  (let [counts {:balances 2 :positions 4 :open-orders 3}
        nav (view/tab-navigation :funding-history counts false {:filter-open? false})
        filter-button (find-first-node nav #(contains? (direct-texts %) "Filter"))
        view-all-button (find-first-node nav #(contains? (direct-texts %) "View All"))
        export-button (find-first-node nav #(contains? (direct-texts %) "Export as CSV"))
        export-button-classes (node-class-set export-button)]
    (is (some? filter-button))
    (is (some? view-all-button))
    (is (some? export-button))
    (is (= [[:actions/toggle-funding-history-filter-open]]
           (get-in filter-button [1 :on :click])))
    (is (= [[:actions/view-all-funding-history]]
           (get-in view-all-button [1 :on :click])))
    (is (= [[:actions/export-funding-history-csv]]
           (get-in export-button [1 :on :click])))
    (is (contains? export-button-classes "text-trading-green"))
    (is (contains? export-button-classes "font-normal"))))

(deftest tab-navigation-renders-positions-count-when-positive-test
  (let [counts {:balances 2 :positions 4 :open-orders 3}
        nav (view/tab-navigation :positions counts false {})
        positions-tab-node (find-first-node nav #(contains? (direct-texts %) "Positions (4)"))]
    (is (some? positions-tab-node))))

(deftest tab-navigation-hides-positions-count-when-zero-test
  (let [counts {:balances 2 :positions 0 :open-orders 3}
        nav (view/tab-navigation :positions counts false {})
        positions-tab-base-node (find-first-node nav #(contains? (direct-texts %) "Positions"))
        positions-tab-count-node (find-first-node nav #(contains? (direct-texts %) "Positions (0)"))]
    (is (some? positions-tab-base-node))
    (is (nil? positions-tab-count-node))))

(deftest balances-tab-content-does-not-render-legacy-subheader-row-test
  (let [content (view/balances-tab-content [sample-balance-row] false default-sort-state)
        title-node (find-first-node content #(contains? (direct-texts %) "Balances ("))
        filter-label-node (find-first-node content #(contains? (direct-texts %) "Hide Small Balances"))]
    (is (nil? title-node))
    (is (nil? filter-label-node))))

(deftest positions-tab-content-does-not-render-legacy-subheader-row-test
  (let [webdata2 {:clearinghouseState {:assetPositions [sample-position-data]}}
        content (view/positions-tab-content webdata2 default-sort-state {})
        title-node (find-first-node content #(contains? (direct-texts %) "Positions ("))
        active-positions-node (find-first-node content #(contains? (direct-texts %) "Active positions"))]
    (is (nil? title-node))
    (is (nil? active-positions-node))))

(deftest account-info-panel-uses-fixed-height-and-bounded-content-test
  (let [panel (view/account-info-panel sample-account-info-state)
        panel-classes (node-class-set panel)
        content-node (second (vec (node-children panel)))
        content-classes (node-class-set content-node)]
    (is (contains? panel-classes "h-96"))
    (is (contains? panel-classes "flex"))
    (is (contains? panel-classes "flex-col"))
    (is (contains? panel-classes "min-h-0"))
    (is (contains? panel-classes "overflow-hidden"))
    (is (contains? content-classes "flex-1"))
    (is (contains? content-classes "min-h-0"))
    (is (contains? content-classes "overflow-hidden"))))

(deftest tab-content-uses-scrollable-row-viewport-test
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
        fills [{:tid 1 :coin "HYPE" :side "B" :sz "1.2" :px "100.0" :fee "0.1" :time 1700000000000}]
        fundings [{:coin "HYPE" :fundingRate "0.001" :payment "1.23" :positionSize "100.0" :time 1700000000000}]
        ledger [{:type "deposit" :coin "USDC" :delta "5.0" :time 1700000000000}]
        contents [(view/balances-tab-content [sample-balance-row] false default-sort-state)
                  (view/positions-tab-content {:clearinghouseState {:assetPositions [sample-position-data]}}
                                              default-sort-state
                                              {})
                  (view/open-orders-tab-content open-orders {:column "Time" :direction :desc})
                  (view/trade-history-tab-content fills)
                  (view/funding-history-tab-content fundings)
                  (view/order-history-tab-content ledger)]]
    (doseq [content contents
            :let [rows-viewport-classes (node-class-set (tab-rows-viewport-node content))]]
      (is (contains? rows-viewport-classes "flex-1"))
      (is (contains? rows-viewport-classes "min-h-0"))
      (is (contains? rows-viewport-classes "overflow-y-auto"))
      (is (contains? rows-viewport-classes "scrollbar-hide")))))

(deftest account-info-table-rows-use-hover-highlight-without-divider-lines-test
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
        fills [{:tid 1 :coin "HYPE" :side "B" :sz "1.2" :px "100.0" :fee "0.1" :time 1700000000000}]
        fundings [{:coin "HYPE" :fundingRate "0.001" :payment "1.23" :positionSize "100.0" :time 1700000000000}]
        ledger [{:type "deposit" :coin "USDC" :delta "5.0" :time 1700000000000}]
        contents [(view/balances-tab-content [sample-balance-row] false default-sort-state)
                  (view/positions-tab-content {:clearinghouseState {:assetPositions [sample-position-data]}}
                                              default-sort-state
                                              {})
                  (view/open-orders-tab-content open-orders {:column "Time" :direction :desc})
                  (view/trade-history-tab-content fills)
                  (view/funding-history-tab-content fundings)
                  (view/order-history-tab-content ledger)]]
    (doseq [content contents
            :let [row-classes (node-class-set (first-viewport-row content))]]
      (is (contains? row-classes "hover:bg-base-300"))
      (is (not (contains? row-classes "border-b")))
      (is (not (contains? row-classes "border-base-300"))))))

(deftest account-info-table-headers-remove-divider-lines-test
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
        fills [{:tid 1 :coin "HYPE" :side "B" :sz "1.2" :px "100.0" :fee "0.1" :time 1700000000000}]
        fundings [{:coin "HYPE" :fundingRate "0.001" :payment "1.23" :positionSize "100.0" :time 1700000000000}]
        ledger [{:type "deposit" :coin "USDC" :delta "5.0" :time 1700000000000}]
        contents [(view/balances-tab-content [sample-balance-row] false default-sort-state)
                  (view/positions-tab-content {:clearinghouseState {:assetPositions [sample-position-data]}}
                                              default-sort-state
                                              {})
                  (view/open-orders-tab-content open-orders {:column "Time" :direction :desc})
                  (view/trade-history-tab-content fills)
                  (view/funding-history-tab-content fundings)
                  (view/order-history-tab-content ledger)]]
    (doseq [content contents
            :let [header-classes (node-class-set (tab-header-node content))]]
      (is (not (contains? header-classes "border-b")))
      (is (not (contains? header-classes "border-base-300"))))))

(deftest account-info-table-rows-use-compact-density-classes-test
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
        fills [{:tid 1 :coin "HYPE" :side "B" :sz "1.2" :px "100.0" :fee "0.1" :time 1700000000000}]
        fundings [{:coin "HYPE" :fundingRate "0.001" :payment "1.23" :positionSize "100.0" :time 1700000000000}]
        ledger [{:type "deposit" :coin "USDC" :delta "5.0" :time 1700000000000}]
        contents [[:balances (view/balances-tab-content [sample-balance-row] false default-sort-state)]
                  [:positions (view/positions-tab-content {:clearinghouseState {:assetPositions [sample-position-data]}}
                                                          default-sort-state
                                                          {})]
                  [:open-orders (view/open-orders-tab-content open-orders {:column "Time" :direction :desc})]
                  [:trade-history (view/trade-history-tab-content fills)]
                  [:funding-history (view/funding-history-tab-content fundings)]
                  [:order-history (view/order-history-tab-content ledger)]]]
    (doseq [[_ content] contents
            :let [row-classes (node-class-set (first-viewport-row content))]]
      (is (contains? row-classes "py-px"))
      (is (contains? row-classes "px-3"))
      (is (contains? row-classes "gap-2")))))

(deftest account-info-table-headers-use-compact-density-classes-test
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
        fills [{:tid 1 :coin "HYPE" :side "B" :sz "1.2" :px "100.0" :fee "0.1" :time 1700000000000}]
        fundings [{:coin "HYPE" :fundingRate "0.001" :payment "1.23" :positionSize "100.0" :time 1700000000000}]
        ledger [{:type "deposit" :coin "USDC" :delta "5.0" :time 1700000000000}]
        contents [[:balances (view/balances-tab-content [sample-balance-row] false default-sort-state)]
                  [:positions (view/positions-tab-content {:clearinghouseState {:assetPositions [sample-position-data]}}
                                                          default-sort-state
                                                          {})]
                  [:open-orders (view/open-orders-tab-content open-orders {:column "Time" :direction :desc})]
                  [:trade-history (view/trade-history-tab-content fills)]
                  [:funding-history (view/funding-history-tab-content fundings)]
                  [:order-history (view/order-history-tab-content ledger)]]]
    (doseq [[_ content] contents
            :let [header-classes (node-class-set (tab-header-node content))]]
      (is (contains? header-classes "py-1"))
      (is (contains? header-classes "px-3"))
      (is (contains? header-classes "gap-2")))))

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
        header-node (tab-header-node content)
        header-cells (vec (node-children header-node))
        row-node (first-viewport-row content)
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
        trade-header-cells (vec (node-children (tab-header-node trade-node)))
        trade-row-cells (vec (node-children (first-viewport-row trade-node)))
        funding-node (view/funding-history-tab-content fundings)
        funding-header-cells (vec (node-children (tab-header-node funding-node)))
        funding-row-cells (vec (node-children (first-viewport-row funding-node)))
        order-node (view/order-history-tab-content ledger)
        order-header-cells (vec (node-children (tab-header-node order-node)))
        order-row-cells (vec (node-children (first-viewport-row order-node)))]
    (doseq [idx (range 1 6)]
      (is (contains? (node-class-set (nth trade-header-cells idx)) "text-left"))
      (is (contains? (node-class-set (nth trade-row-cells idx)) "text-left")))
    (doseq [idx (range 1 6)]
      (is (contains? (node-class-set (nth funding-header-cells idx)) "text-left"))
      (is (contains? (node-class-set (nth funding-row-cells idx)) "text-left")))
    (doseq [idx (range 1 4)]
      (is (contains? (node-class-set (nth order-header-cells idx)) "text-left"))
      (is (contains? (node-class-set (nth order-row-cells idx)) "text-left")))))

(deftest funding-history-headers-use-secondary-text-and-sort-actions-test
  (let [fundings [{:id "1700000000000|HYPE|120.0|-0.42|0.0006"
                   :time-ms 1700000000000
                   :coin "HYPE"
                   :position-size-raw 120.0
                   :payment-usdc-raw -0.42
                   :funding-rate-raw 0.0006}]
        content (@#'view/funding-history-table fundings {:sort {:column "Time" :direction :desc}})
        header-cells (vec (node-children (tab-header-node content)))
        columns ["Time" "Coin" "Size" "Position Side" "Payment" "Rate"]]
    (doseq [[idx column-name] (map-indexed vector columns)]
      (let [button-node (first (vec (node-children (nth header-cells idx))))]
        (is (= :button (first button-node)))
        (is (contains? (node-class-set button-node) "text-trading-text-secondary"))
        (is (contains? (node-class-set button-node) "hover:text-trading-text"))
        (is (= [[:actions/sort-funding-history column-name]]
               (get-in button-node [1 :on :click])))))))

(deftest funding-history-content-sorts-by-sort-state-and-default-fallback-test
  (let [rows [{:id "2"
               :time-ms 2000
               :coin "BTC"
               :position-size-raw 2
               :payment-usdc-raw 0.5
               :funding-rate-raw 0.0002}
              {:id "1"
               :time-ms 3000
               :coin "ETH"
               :position-size-raw -1
               :payment-usdc-raw 1.2
               :funding-rate-raw -0.0001}
              {:id "3"
               :time-ms 1000
               :coin "SOL"
               :position-size-raw 0.5
               :payment-usdc-raw -0.3
               :funding-rate-raw 0.0004}]
        coin-sorted (@#'view/funding-history-table rows {:sort {:column "Coin" :direction :asc}})
        coin-row (first-viewport-row coin-sorted)
        coin-value (nth (vec (node-children coin-row)) 1)
        default-sorted (@#'view/funding-history-table rows {})
        default-row (first-viewport-row default-sorted)
        default-coin (nth (vec (node-children default-row)) 1)]
    (is (contains? (direct-texts coin-value) "BTC"))
    (is (contains? (direct-texts default-coin) "ETH"))))

(deftest funding-history-panel-renders-controls-and-parity-columns-test
  (let [funding-row {:id "1700000000000|HYPE|120.0|-0.42|0.0006"
                     :time-ms 1700000000000
                     :coin "HYPE"
                     :size-raw 120.0
                     :position-size-raw 120.0
                     :position-side :long
                     :payment-usdc-raw -0.42
                     :funding-rate-raw 0.0006}
        state (-> sample-account-info-state
                  (assoc-in [:account-info :selected-tab] :funding-history)
                  (assoc-in [:account-info :funding-history]
                            {:filters {:coin-set #{}
                                       :start-time-ms 0
                                       :end-time-ms 2000000000000}
                             :draft-filters {:coin-set #{}
                                             :start-time-ms 0
                                             :end-time-ms 2000000000000}
                             :filter-open? false
                             :loading? false
                             :error nil})
                  (assoc-in [:orders :fundings-raw] [funding-row])
                  (assoc-in [:orders :fundings] [funding-row]))
        panel (view/account-info-panel state)]
    (is (some? (find-first-node panel #(contains? (direct-texts %) "Filter"))))
    (is (some? (find-first-node panel #(contains? (direct-texts %) "View All"))))
    (is (some? (find-first-node panel #(contains? (direct-texts %) "Export as CSV"))))
    (is (= 1 (count-nodes panel #(contains? (direct-texts %) "Export as CSV"))))
    (is (some? (find-first-node panel #(contains? (direct-texts %) "Position Side"))))
    (is (some? (find-first-node panel #(contains? (direct-texts %) "Long"))))))

(deftest funding-history-controls-renders-status-without-header-actions-test
  (let [controls (@#'view/funding-history-controls {:loading? true
                                                    :error "Boom"
                                                    :filters {:coin-set #{}}
                                                    :draft-filters {:coin-set #{}}}
                                                   [])
        status-row (first (vec (node-children controls)))]
    (is (some? (find-first-node status-row #(contains? (direct-texts %) "Loading..."))))
    (is (some? (find-first-node status-row #(contains? (direct-texts %) "Boom"))))
    (is (nil? (find-first-node controls #(contains? (direct-texts %) "Filter"))))
    (is (nil? (find-first-node controls #(contains? (direct-texts %) "View All"))))
    (is (nil? (find-first-node controls #(contains? (direct-texts %) "Export as CSV"))))))

(deftest funding-history-filter-panel-renders-apply-and-cancel-controls-test
  (let [funding-row {:id "1700000000000|HYPE|120.0|-0.42|0.0006"
                     :time-ms 1700000000000
                     :coin "HYPE"
                     :size-raw 120.0
                     :position-size-raw 120.0
                     :position-side :long
                     :payment-usdc-raw -0.42
                     :funding-rate-raw 0.0006}
        state (-> sample-account-info-state
                  (assoc-in [:account-info :selected-tab] :funding-history)
                  (assoc-in [:account-info :funding-history]
                            {:filters {:coin-set #{}
                                       :start-time-ms 0
                                       :end-time-ms 2000000000000}
                             :draft-filters {:coin-set #{"HYPE"}
                                             :start-time-ms 1700000000000
                                             :end-time-ms 1700100000000}
                             :filter-open? true
                             :loading? false
                             :error nil})
                  (assoc-in [:orders :fundings-raw] [funding-row])
                  (assoc-in [:orders :fundings] [funding-row]))
        panel (view/account-info-panel state)
        datetime-input (find-first-node panel #(= "datetime-local" (get-in % [1 :type])))]
    (is (some? datetime-input))
    (is (some? (find-first-node panel #(contains? (direct-texts %) "Apply"))))
    (is (some? (find-first-node panel #(contains? (direct-texts %) "Cancel"))))))

(deftest funding-history-coin-filter-uses-standard-green-checkboxes-test
  (let [funding-row-hype {:id "1700000000000|HYPE|120.0|-0.42|0.0006"
                          :time-ms 1700000000000
                          :coin "HYPE"
                          :size-raw 120.0
                          :position-size-raw 120.0
                          :position-side :long
                          :payment-usdc-raw -0.42
                          :funding-rate-raw 0.0006}
        funding-row-sol {:id "1700000100000|SOL|80.0|0.18|0.0002"
                         :time-ms 1700000100000
                         :coin "SOL"
                         :size-raw 80.0
                         :position-size-raw 80.0
                         :position-side :long
                         :payment-usdc-raw 0.18
                         :funding-rate-raw 0.0002}
        state (-> sample-account-info-state
                  (assoc-in [:account-info :selected-tab] :funding-history)
                  (assoc-in [:account-info :funding-history]
                            {:filters {:coin-set #{}}
                             :draft-filters {:coin-set #{"HYPE"}}
                             :filter-open? true
                             :loading? false
                             :error nil})
                  (assoc-in [:orders :fundings-raw] [funding-row-hype funding-row-sol])
                  (assoc-in [:orders :fundings] [funding-row-hype funding-row-sol]))
        panel (view/account-info-panel state)
        coin-checkboxes (find-all-nodes panel
                                        (fn [node]
                                          (and (= :input (first node))
                                               (= "checkbox" (get-in node [1 :type]))
                                               (= :actions/toggle-funding-history-filter-coin
                                                  (first (first (get-in node [1 :on :change])))))))
        class-sets (map node-class-set coin-checkboxes)]
    (is (= 2 (count coin-checkboxes)))
    (is (every? #(contains? % "trade-toggle-checkbox") class-sets))
    (is (every? #(contains? % "h-4") class-sets))
    (is (every? #(contains? % "w-4") class-sets))
    (is (every? #(not (contains? % "checkbox-xs")) class-sets))
    (is (some true? (map #(get-in % [1 :checked]) coin-checkboxes)))
    (is (every? #(= :actions/toggle-funding-history-filter-coin
                    (first (first (get-in % [1 :on :change]))))
                coin-checkboxes))))
