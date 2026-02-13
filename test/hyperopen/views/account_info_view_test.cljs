(ns hyperopen.views.account-info-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.utils.formatting :as fmt]
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

(defn- collect-strings [node]
  (cond
    (string? node) [node]

    (vector? node)
    (mapcat collect-strings (node-children node))

    (seq? node)
    (mapcat collect-strings node)

    :else []))

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

(defn- balance-row-coin [row-node]
  (let [coin-cell (first (vec (node-children row-node)))]
    (first (direct-texts coin-cell))))

(defn- balance-tab-coins [tab-content]
  (->> (node-children (tab-rows-viewport-node tab-content))
       (map balance-row-coin)
       vec))

(defn- balance-row-contract-cell [row-node]
  (nth (vec (node-children row-node)) 7))

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

(def sample-order-history-row
  {:order {:coin "xyz:NVDA"
           :oid 307891000622
           :side "B"
           :origSz "0.500"
           :remainingSz "0.000"
           :limitPx "0.000"
           :orderType "Market"
           :reduceOnly false
           :isTrigger false
           :isPositionTpsl false
           :timestamp 1700000000000}
   :status "filled"
   :statusTimestamp 1700000005000})

(defn- order-history-row
  [idx]
  {:order {:coin "PUMP"
           :oid idx
           :side (if (odd? idx) "B" "A")
           :origSz "1.0"
           :remainingSz "0.0"
           :limitPx "0.001"
           :orderType "Limit"
           :reduceOnly false
           :isTrigger false
           :isPositionTpsl false
           :timestamp (+ 1700000000000 idx)}
   :status (if (odd? idx) "filled" "canceled")
   :statusTimestamp (+ 1700000000000 idx)})

(defn- funding-history-row
  [idx]
  {:id (str idx)
   :time-ms (+ 1700000000000 idx)
   :coin (if (odd? idx) "BTC" "ETH")
   :position-size-raw (if (odd? idx) 1.0 -1.0)
   :payment-usdc-raw (/ idx 1000)
   :funding-rate-raw (/ idx 1000000)})

(defn- trade-history-row
  [idx]
  {:tid idx
   :coin (if (odd? idx) "BTC" "ETH")
   :side (if (odd? idx) "B" "A")
   :sz "1.25"
   :px "100.5"
   :fee "0.05"
   :time (+ 1700000000000 idx)})

(def sample-account-info-state
  {:account-info {:selected-tab :balances
                  :loading false
                  :error nil
                  :hide-small-balances? false
                  :balances-sort default-sort-state
                  :positions-sort default-sort-state
                  :open-orders-sort {:column "Time" :direction :desc}
                  :order-history {:sort {:column "Time" :direction :desc}
                                  :status-filter :all
                                  :filter-open? false
                                  :loading? false
                                  :error nil
                                  :request-id 0}}
   :webdata2 {}
   :orders {:open-orders []
            :open-orders-snapshot []
            :open-orders-snapshot-by-dex {}
            :order-history []}
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

(deftest balances-header-includes-contract-column-as-final-cell-test
  (let [header-node (view/balance-table-header default-sort-state)
        header-cells (vec (node-children header-node))
        header-labels (mapv #(first (collect-strings %)) header-cells)]
    (is (= 8 (count header-cells)))
    (is (= ["Coin"
            "Total Balance"
            "Available Balance"
            "USDC Value"
            "PNL (ROE %)"
            "Send"
            "Transfer"
            "Contract"]
           header-labels))))

(deftest position-headers-use-secondary-text-and-hover-affordance-test
  (let [position-header-node (view/position-table-header default-sort-state)
        position-coin-header (find-first-node position-header-node
                                              #(contains? (direct-texts %) "Coin"))
        sortable-node (view/sortable-header "Coin" default-sort-state)]
    (is (some? position-coin-header))
    (is (contains? (node-class-set sortable-node) "text-trading-text-secondary"))
    (is (contains? (node-class-set sortable-node) "hover:text-trading-text"))))

(deftest position-header-coin-cell-includes-left-padding-class-test
  (let [position-header-node (view/position-table-header default-sort-state)
        header-cells (vec (node-children position-header-node))
        coin-header-cell (nth header-cells 0)]
    (is (contains? (node-class-set coin-header-cell) "pl-3"))))

(deftest open-orders-sortable-header-uses-secondary-text-and-hover-affordance-test
  (let [header-node (view/sortable-open-orders-header "Time" {:column "Time" :direction :asc})
        sort-icon-node (second (vec (node-children header-node)))]
    (is (contains? (node-class-set header-node) "text-trading-text-secondary"))
    (is (contains? (node-class-set header-node) "hover:text-trading-text"))
    (is (= [[:actions/sort-open-orders "Time"]]
           (get-in header-node [1 :on :click])))
    (is (= "↑" (last sort-icon-node)))))

(deftest funding-history-sortable-header-uses-secondary-text-hover-and-action-test
  (let [header-node (view/sortable-funding-history-header "Time" {:column "Time" :direction :asc})
        sort-icon-node (second (vec (node-children header-node)))]
    (is (contains? (node-class-set header-node) "text-trading-text-secondary"))
    (is (contains? (node-class-set header-node) "hover:text-trading-text"))
    (is (= [[:actions/sort-funding-history "Time"]]
           (get-in header-node [1 :on :click])))
    (is (= "↑" (last sort-icon-node)))))

(deftest trade-history-sortable-header-uses-secondary-text-hover-and-action-test
  (let [header-node (view/sortable-trade-history-header "Time" {:column "Time" :direction :asc})
        sort-icon-node (second (vec (node-children header-node)))]
    (is (contains? (node-class-set header-node) "text-trading-text-secondary"))
    (is (contains? (node-class-set header-node) "hover:text-trading-text"))
    (is (= [[:actions/sort-trade-history "Time"]]
           (get-in header-node [1 :on :click])))
    (is (= "↑" (last sort-icon-node)))))

(deftest order-history-sortable-header-uses-secondary-text-hover-and-action-test
  (let [header-node (view/sortable-order-history-header "Time" {:column "Time" :direction :asc})
        sort-icon-node (second (vec (node-children header-node)))]
    (is (contains? (node-class-set header-node) "text-trading-text-secondary"))
    (is (contains? (node-class-set header-node) "hover:text-trading-text"))
    (is (= [[:actions/sort-order-history "Time"]]
           (get-in header-node [1 :on :click])))
    (is (= "↑" (last sort-icon-node)))))

(deftest sort-trade-history-by-column-is-deterministic-on-ties-and-formats-derived-values-test
  (let [rows [{:tid 2
               :coin "xyz:NVDA"
               :side "B"
               :sz "2"
               :px "10"
               :fee "0.1"
               :time 1700000000000}
              {:tid 1
               :coin "BTC"
               :side "A"
               :sz "1"
               :px "15"
               :fee "0.2"
               :time 1700000000000}
              {:tid 3
               :coin "ETH"
               :dir "Open Long (Price Improved)"
               :sz "3"
               :px "8"
               :tradeValue "24"
               :closedPnl "-0.3"
               :fee "0.05"
               :time 1700000001000}]
        time-asc (view/sort-trade-history-by-column rows "Time" :asc {})
        value-desc (view/sort-trade-history-by-column rows "Trade Value" :desc {})
        direction-asc (view/sort-trade-history-by-column rows "Direction" :asc {})]
    (is (= [1 2 3] (mapv :tid time-asc)))
    (is (= [3 2 1] (mapv :tid value-desc)))
    (is (= [2 3 1] (mapv :tid direction-asc)))))

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

(deftest sort-balances-by-column-keeps-usdc-partition-on-top-for-coin-sort-test
  (let [rows [{:key "spot-usdc" :coin "USDC (Spot)"}
              {:key "hype" :coin "HYPE"}
              {:key "perps-usdc" :coin "USDC (Perps)"}
              {:key "btc" :coin "BTC"}]
        coin-asc (view/sort-balances-by-column rows "Coin" :asc)
        coin-desc (view/sort-balances-by-column rows "Coin" :desc)]
    (is (= ["USDC (Perps)" "USDC (Spot)" "BTC" "HYPE"]
           (mapv :coin coin-asc)))
    (is (= ["USDC (Spot)" "USDC (Perps)" "HYPE" "BTC"]
           (mapv :coin coin-desc)))))

(deftest sort-balances-by-column-keeps-usdc-partition-on-top-for-numeric-columns-test
  (let [rows [{:key "usdc-low" :coin "USDC (Spot)" :usdc-value 5}
              {:key "coin-high" :coin "AAA" :usdc-value 1000}
              {:key "usdc-high" :coin "USDC (Perps)" :usdc-value 100}
              {:key "coin-low" :coin "BBB" :usdc-value 1}]
        value-asc (view/sort-balances-by-column rows "USDC Value" :asc)
        value-desc (view/sort-balances-by-column rows "USDC Value" :desc)]
    (is (= ["USDC (Spot)" "USDC (Perps)" "BBB" "AAA"]
           (mapv :coin value-asc)))
    (is (= ["USDC (Perps)" "USDC (Spot)" "AAA" "BBB"]
           (mapv :coin value-desc)))))

(deftest sort-balances-by-column-is-deterministic-on-ties-with-coin-then-key-test
  (let [rows [{:key "u-b" :coin "USDC Beta" :usdc-value 10}
              {:key "u-a2" :coin "USDC Alpha" :usdc-value 10}
              {:key "u-a1" :coin "USDC Alpha" :usdc-value 10}
              {:key "n-z" :coin "ZZZ" :usdc-value 20}
              {:key "n-a2" :coin "AAA" :usdc-value 20}
              {:key "n-a1" :coin "AAA" :usdc-value 20}]
        asc-result (view/sort-balances-by-column rows "USDC Value" :asc)
        desc-result (view/sort-balances-by-column rows "USDC Value" :desc)
        expected [["USDC Alpha" "u-a1"]
                  ["USDC Alpha" "u-a2"]
                  ["USDC Beta" "u-b"]
                  ["AAA" "n-a1"]
                  ["AAA" "n-a2"]
                  ["ZZZ" "n-z"]]]
    (is (= expected
           (mapv (juxt :coin :key) asc-result)))
    (is (= expected
           (mapv (juxt :coin :key) desc-result)))))

(deftest build-balance-rows-attaches-contract-id-for-non-usdc-spot-and-leaves-usdc-rows-empty-test
  (let [rows (view/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "12.5"
                                                    :totalMarginUsed "2.5"}}
               :spotAssetCtxs []}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 8
                                :tokenId "0x11111111111111111111111111111111"}
                               {:index 1
                                :name "HYPE"
                                :weiDecimals 5
                                :tokenId "0x22222222222222222222222222222222"}]
                      :universe []}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "5.0"
                                                 :entryNtl "0"}
                                                {:coin "HYPE"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "7.0"
                                                 :entryNtl "0"}]}})
        by-coin (into {} (map (juxt :coin identity)) rows)]
    (is (nil? (:contract-id (get by-coin "USDC (Perps)"))))
    (is (nil? (:contract-id (get by-coin "USDC (Spot)"))))
    (is (= 5.0
           (view/parse-num (:usdc-value (get by-coin "USDC (Spot)")))))
    (is (= "0x22222222222222222222222222222222"
           (:contract-id (get by-coin "HYPE"))))))

(deftest build-balance-rows-unified-mode-prefers-spot-usdc-without-perps-double-count-test
  (let [rows (view/build-balance-rows-for-account
              {:clearinghouseState {:marginSummary {:accountValue "3.03"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs [{:markPx "1"}]}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 6
                                :tokenId "0x11111111111111111111111111111111"}
                               {:index 1
                                :name "MEOW"
                                :weiDecimals 6
                                :tokenId "0x22222222222222222222222222222222"}]
                      :universe [{:tokens [0 0]
                                  :index 0}]}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "201.42"
                                                 :entryNtl "0"}
                                                {:coin "MEOW"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "1.0"
                                                 :entryNtl "0"}]}}
              {:mode :unified
               :abstraction-raw "unifiedAccount"})
        by-coin (into {} (map (juxt :coin identity)) rows)
        usdc-row (get by-coin "USDC")]
    (is (= 2 (count rows)))
    (is (some? usdc-row))
    (is (= 201.42 (view/parse-num (:total-balance usdc-row))))
    (is (= 201.42 (view/parse-num (:usdc-value usdc-row))))
    (is (true? (:transfer-disabled? usdc-row)))
    (is (nil? (get by-coin "USDC (Spot)")))
    (is (nil? (get by-coin "USDC (Perps)")))))

(deftest build-balance-rows-unified-mode-falls-back-to-perps-usdc-when-spot-usdc-missing-test
  (let [rows (view/build-balance-rows-for-account
              {:clearinghouseState {:marginSummary {:accountValue "3.03"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs []}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 6}
                               {:index 1
                                :name "MEOW"
                                :weiDecimals 6}]
                      :universe []}
               :clearinghouse-state {:balances [{:coin "MEOW"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "1.0"
                                                 :entryNtl "0"}]}}
              {:mode :unified
               :abstraction-raw "unifiedAccount"})
        by-coin (into {} (map (juxt :coin identity)) rows)
        usdc-row (get by-coin "USDC")]
    (is (= 2 (count rows)))
    (is (some? usdc-row))
    (is (= 3.03 (view/parse-num (:total-balance usdc-row))))
    (is (= 3.03 (view/parse-num (:usdc-value usdc-row))))
    (is (true? (:transfer-disabled? usdc-row)))))

(deftest build-balance-rows-usdc-value-falls-back-to-coin-identity-when-meta-missing-test
  (let [rows (view/build-balance-rows-for-account
              {:clearinghouseState {:marginSummary {:accountValue "0.0"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs []}
              {:meta {:tokens []
                      :universe []}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "3.03000000"
                                                 :total "204.41936500"
                                                 :entryNtl "0"}]}}
              {:mode :unified
               :abstraction-raw "unifiedAccount"})
        usdc-row (first rows)]
    (is (= 1 (count rows)))
    (is (= "USDC" (:coin usdc-row)))
    (is (= 204.419365 (view/parse-num (:usdc-value usdc-row))))
    (is (= 201.389365 (view/parse-num (:available-balance usdc-row))))))

(deftest build-balance-rows-usdc-identity-invariant-holds-without-pricing-context-test
  (let [rows (view/build-balance-rows
              {:spotAssetCtxs nil}
              {:meta nil
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "204.41"
                                                 :entryNtl "0"}
                                                {:coin "USDC.e"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "0.04"
                                                 :entryNtl "0"}]}})
        usdc-like-rows (filter #(str/starts-with? (:coin %) "USDC") rows)]
    (is (= 2 (count usdc-like-rows)))
    (doseq [row usdc-like-rows]
      (is (= (view/parse-num (:total-balance row))
             (view/parse-num (:usdc-value row)))))))

(deftest build-balance-rows-filters-zero-balance-rows-by-default-test
  (let [rows (view/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "0.0"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs [{:markPx "1"}]}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 6}
                               {:index 1
                                :name "USDE"
                                :weiDecimals 6}
                               {:index 2
                                :name "HYPE"
                                :weiDecimals 6}]
                      :universe [{:tokens [0 0]
                                  :index 0}]}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "10.0"
                                                 :entryNtl "0"}
                                                {:coin "USDE"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "0.0"
                                                 :entryNtl "0"}
                                                {:coin "HYPE"
                                                 :token 2
                                                 :hold "0.0"
                                                 :total "1.0"
                                                 :entryNtl "0"}]}})
        coins (set (map :coin rows))]
    (is (contains? coins "USDC (Spot)"))
    (is (contains? coins "HYPE"))
    (is (not (contains? coins "USDE")))
    (is (not (contains? coins "USDC (Perps)")))))

(deftest balances-tab-content-without-active-sort-preserves-input-order-test
  (let [rows [{:key "row-1" :coin "HYPE" :total-balance 1 :available-balance 1 :usdc-value 1}
              {:key "row-2" :coin "USDC (Spot)" :total-balance 2 :available-balance 2 :usdc-value 2}
              {:key "row-3" :coin "BTC" :total-balance 3 :available-balance 3 :usdc-value 3}]
        tab-content (view/balances-tab-content rows false {:column nil :direction :asc})]
    (is (= ["HYPE" "USDC (Spot)" "BTC"]
           (balance-tab-coins tab-content)))))

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

(deftest tab-navigation-renders-order-history-filter-actions-test
  (let [counts {:balances 2 :positions 4 :open-orders 3}
        nav (view/tab-navigation :order-history
                                 counts
                                 false
                                 {}
                                 {:status-filter :filled
                                  :filter-open? true})
        filter-button (find-first-node nav #(contains? (direct-texts %) "Filter"))
        filled-option (find-first-node nav #(contains? (direct-texts %) "Filled"))]
    (is (some? filter-button))
    (is (some? filled-option))
    (is (= [[:actions/toggle-order-history-filter-open]]
           (get-in filter-button [1 :on :click])))
    (is (= [[:actions/set-order-history-status-filter :filled]]
           (get-in filled-option [1 :on :click])))))

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

(deftest tab-navigation-renders-neutral-positions-freshness-cue-test
  (let [counts {:balances 2 :positions 4 :open-orders 3}
        nav (view/tab-navigation :positions
                                 counts
                                 false
                                 {}
                                 {}
                                 {:positions {:text "Last update 2m 0s ago"
                                              :tone :neutral}})
        cue-node (find-first-node nav #(= "account-tab-freshness-cue" (get-in % [1 :data-role])))
        cue-text (str/join " " (collect-strings cue-node))
        cue-text-node (find-first-node cue-node #(contains? (direct-texts %) "Last update 2m 0s ago"))]
    (is (some? cue-node))
    (is (str/includes? cue-text "Last update 2m 0s ago"))
    (is (contains? (node-class-set cue-text-node) "text-base-content/70"))))

(deftest tab-navigation-renders-open-orders-delayed-freshness-cue-test
  (let [counts {:balances 2 :positions 4 :open-orders 3}
        nav (view/tab-navigation :open-orders
                                 counts
                                 false
                                 {}
                                 {}
                                 {:open-orders {:text "Stale 12s"
                                                :tone :warning}})
        cue-node (find-first-node nav #(= "account-tab-freshness-cue" (get-in % [1 :data-role])))
        cue-text (str/join " " (collect-strings cue-node))
        cue-text-node (find-first-node cue-node #(contains? (direct-texts %) "Stale 12s"))]
    (is (some? cue-node))
    (is (str/includes? cue-text "Stale 12s"))
    (is (contains? (node-class-set cue-text-node) "text-warning"))))

(deftest account-info-panel-derives-positions-freshness-cue-from-websocket-health-test
  (let [state (-> sample-account-info-state
                  (assoc-in [:account-info :selected-tab] :positions)
                  (assoc :wallet {:address "0xabc"})
                  (assoc :websocket-health
                         {:generated-at-ms 5000
                          :streams {["webData2" nil "0xabc" nil nil]
                                    {:topic "webData2"
                                     :status :n-a
                                     :subscribed? true
                                     :last-payload-at-ms 3000}}}))
        panel (view/account-info-panel state)
        cue-node (find-first-node panel #(= "account-tab-freshness-cue" (get-in % [1 :data-role])))
        cue-text (str/join " " (collect-strings cue-node))]
    (is (some? cue-node))
    (is (str/includes? cue-text "Last update 2s ago"))))

(deftest account-info-panel-derives-open-orders-stale-cue-from-websocket-health-test
  (let [state (-> sample-account-info-state
                  (assoc-in [:account-info :selected-tab] :open-orders)
                  (assoc :wallet {:address "0xabc"})
                  (assoc :websocket-health
                         {:generated-at-ms 20000
                          :streams {["openOrders" nil "0xabc" nil nil]
                                    {:topic "openOrders"
                                     :status :delayed
                                     :subscribed? true
                                     :last-payload-at-ms 8000
                                     :stale-threshold-ms 5000}}}))
        panel (view/account-info-panel state)
        cue-node (find-first-node panel #(= "account-tab-freshness-cue" (get-in % [1 :data-role])))
        cue-text (str/join " " (collect-strings cue-node))]
    (is (some? cue-node))
    (is (str/includes? cue-text "Stale 12s"))))

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
        order-history [sample-order-history-row]
        contents [(view/balances-tab-content [sample-balance-row] false default-sort-state)
                  (view/positions-tab-content {:clearinghouseState {:assetPositions [sample-position-data]}}
                                              default-sort-state
                                              {})
                  (view/open-orders-tab-content open-orders {:column "Time" :direction :desc})
                  (view/trade-history-tab-content fills)
                  (view/funding-history-tab-content fundings)
                  (view/order-history-tab-content order-history)]]
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
        order-history [sample-order-history-row]
        contents [(view/balances-tab-content [sample-balance-row] false default-sort-state)
                  (view/positions-tab-content {:clearinghouseState {:assetPositions [sample-position-data]}}
                                              default-sort-state
                                              {})
                  (view/open-orders-tab-content open-orders {:column "Time" :direction :desc})
                  (view/trade-history-tab-content fills)
                  (view/funding-history-tab-content fundings)
                  (view/order-history-tab-content order-history)]]
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
        order-history [sample-order-history-row]
        contents [(view/balances-tab-content [sample-balance-row] false default-sort-state)
                  (view/positions-tab-content {:clearinghouseState {:assetPositions [sample-position-data]}}
                                              default-sort-state
                                              {})
                  (view/open-orders-tab-content open-orders {:column "Time" :direction :desc})
                  (view/trade-history-tab-content fills)
                  (view/funding-history-tab-content fundings)
                  (view/order-history-tab-content order-history)]]
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
        order-history [sample-order-history-row]
        contents [[:balances (view/balances-tab-content [sample-balance-row] false default-sort-state)]
                  [:positions (view/positions-tab-content {:clearinghouseState {:assetPositions [sample-position-data]}}
                                                          default-sort-state
                                                          {})]
                  [:open-orders (view/open-orders-tab-content open-orders {:column "Time" :direction :desc})]
                  [:trade-history (view/trade-history-tab-content fills)]
                  [:funding-history (view/funding-history-tab-content fundings)]
                  [:order-history (view/order-history-tab-content order-history)]]]
    (doseq [[tab-key content] contents
            :let [row-classes (node-class-set (first-viewport-row content))]]
      (if (= tab-key :positions)
        (do
          (is (contains? row-classes "py-0"))
          (is (contains? row-classes "pr-3"))
          (is (not (contains? row-classes "px-3"))))
        (do
          (is (contains? row-classes "py-px"))
          (is (contains? row-classes "px-3"))))
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
        order-history [sample-order-history-row]
        contents [[:balances (view/balances-tab-content [sample-balance-row] false default-sort-state)]
                  [:positions (view/positions-tab-content {:clearinghouseState {:assetPositions [sample-position-data]}}
                                                          default-sort-state
                                                          {})]
                  [:open-orders (view/open-orders-tab-content open-orders {:column "Time" :direction :desc})]
                  [:trade-history (view/trade-history-tab-content fills)]
                  [:funding-history (view/funding-history-tab-content fundings)]
                  [:order-history (view/order-history-tab-content order-history)]]]
    (doseq [[tab-key content] contents
            :let [header-classes (node-class-set (tab-header-node content))]]
      (is (contains? header-classes "py-1"))
      (if (= tab-key :positions)
        (do
          (is (contains? header-classes "pr-3"))
          (is (not (contains? header-classes "px-3"))))
        (is (contains? header-classes "px-3")))
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

(deftest balance-row-renders-unified-transfer-disabled-label-test
  (let [row-node (view/balance-row (assoc sample-balance-row
                                          :coin "USDC"
                                          :transfer-disabled? true))
        transfer-label-node (find-first-node row-node #(contains? (direct-texts %) "Unified"))
        transfer-button-node (find-first-node row-node #(contains? (direct-texts %) "Transfer"))]
    (is (some? transfer-label-node))
    (is (contains? (node-class-set transfer-label-node) "text-trading-text-secondary"))
    (is (nil? transfer-button-node))))

(deftest balance-row-unified-available-balance-renders-dashed-tooltip-test
  (let [row-node (view/balance-row {:key "unified-usdc"
                                    :coin "USDC"
                                    :total-balance 204.419365
                                    :available-balance 201.389365
                                    :usdc-value 204.41
                                    :pnl-value nil
                                    :pnl-pct nil
                                    :amount-decimals 8
                                    :transfer-disabled? true})
        tooltip-trigger-node (find-first-node row-node #(and (contains? (node-class-set %) "decoration-dashed")
                                                              (contains? (direct-texts %) "201.38936500")))
        tooltip-panel-node (find-first-node row-node #(and (= :div (first %))
                                                           (contains? (node-class-set %) "group-hover:opacity-100")))
        tooltip-text (str/join " " (collect-strings tooltip-panel-node))]
    (is (some? tooltip-trigger-node))
    (is (contains? (node-class-set tooltip-trigger-node) "underline"))
    (is (contains? (node-class-set tooltip-trigger-node) "underline-offset-2"))
    (is (some? tooltip-panel-node))
    (is (str/includes? tooltip-text "201.38936500 USDC is available to withdraw or transfer."))))

(deftest balance-row-contract-cell-renders-explorer-link-with-abbreviated-id-test
  (let [contract-id "0x1234567890abcdef"
        row-node (view/balance-row (assoc sample-balance-row :contract-id contract-id))
        contract-cell (balance-row-contract-cell row-node)
        link-node (find-first-node contract-cell #(= :a (first %)))
        icon-node (find-first-node contract-cell #(= :svg (first %)))
        strings (set (collect-strings contract-cell))]
    (is (some? link-node))
    (is (some? icon-node))
    (is (= (str "https://app.hyperliquid.xyz/explorer/token/" contract-id)
           (get-in link-node [1 :href])))
    (is (= "_blank" (get-in link-node [1 :target])))
    (is (= "noopener noreferrer" (get-in link-node [1 :rel])))
    (is (contains? strings "0x1234...cdef"))))

(deftest balance-row-contract-cell-stays-blank-when-contract-id-missing-or-invalid-test
  (let [missing-row (view/balance-row sample-balance-row)
        invalid-row (view/balance-row (assoc sample-balance-row :contract-id "bad id"))
        missing-cell (balance-row-contract-cell missing-row)
        invalid-cell (balance-row-contract-cell invalid-row)
        missing-link (find-first-node missing-cell #(= :a (first %)))
        invalid-link (find-first-node invalid-cell #(= :a (first %)))]
    (is (some? missing-cell))
    (is (some? invalid-cell))
    (is (nil? missing-link))
    (is (nil? invalid-link))
    (is (empty? (collect-strings missing-cell)))
    (is (empty? (collect-strings invalid-cell)))))

(deftest balance-row-contract-abbreviation-rules-test
  (let [short-id "1234567890"
        no-prefix-id "abcdefghijklmnop"
        short-row (view/balance-row (assoc sample-balance-row :contract-id short-id))
        no-prefix-row (view/balance-row (assoc sample-balance-row :contract-id no-prefix-id))
        short-strings (set (collect-strings (balance-row-contract-cell short-row)))
        no-prefix-strings (set (collect-strings (balance-row-contract-cell no-prefix-row)))]
    (is (contains? short-strings short-id))
    (is (contains? no-prefix-strings "abcd...mnop"))))

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
                                               (and (contains? classes "font-semibold")
                                                    (contains? classes "num"))))
        position-value-node (find-first-node position-row-node
                                             #(let [classes (node-class-set %)]
                                                (and (contains? classes "font-semibold")
                                                     (contains? classes "num"))))]
    (is (some? balance-value-node))
    (is (some? position-value-node))))

(deftest position-table-uses-left-alignment-for-value-columns-test
  (let [header-node (view/position-table-header default-sort-state)
        header-cells (vec (node-children header-node))
        row-node (view/position-row sample-position-data)
        row-cells (vec (node-children row-node))]
    (doseq [idx (range 1 9)]
      (is (contains? (node-class-set (nth header-cells idx)) "text-left")))
    (doseq [idx (range 1 9)]
      (is (contains? (node-class-set (nth row-cells idx)) "text-left")))
    (doseq [idx [9]]
      (is (contains? (node-class-set (nth header-cells idx)) "text-left")))
    (doseq [idx [9]]
      (is (contains? (node-class-set (nth row-cells idx)) "text-left")))))

(defn- sample-position-row
  ([coin leverage size]
   (sample-position-row coin leverage size nil))
  ([coin leverage size dex]
   {:position {:coin coin
               :leverage {:value leverage}
               :szi size
               :positionValue "100.00"
               :entryPx "10.00"
               :unrealizedPnl "1.25"
               :returnOnEquity "0.10"
               :liquidationPx "4.20"
               :marginUsed "12.00"
               :cumFunding {:allTime "0.5"}}
    :dex dex}))

(deftest position-size-format-removes-leverage-and-uses-base-symbol-test
  (is (= "0.500 NVDA"
         (view/format-position-size {:coin "xyz:NVDA"
                                     :szi "0.500"
                                     :leverage {:value 10}})))
  (is (= "0.500 NVDA"
         (view/format-position-size {:coin "NVDA"
                                     :szi "0.500"
                                     :leverage {:value 10}}))))

(deftest position-row-renders-green-leverage-and-dex-chips-test
  (let [row-node (view/position-row (sample-position-row "xyz:NVDA" 10 "0.500"))
        row-cells (vec (node-children row-node))
        coin-cell (nth row-cells 0)
        size-cell (nth row-cells 1)
        coin-strings (set (collect-strings coin-cell))
        leverage-chip (find-first-node coin-cell #(contains? (direct-texts %) "10x"))
        dex-chip (find-first-node coin-cell #(contains? (direct-texts %) "xyz"))
        expected-chip-classes #{"bg-emerald-500/20" "text-emerald-300" "border-emerald-500/30"}]
    (is (contains? coin-strings "NVDA"))
    (is (contains? coin-strings "10x"))
    (is (contains? coin-strings "xyz"))
    (is (not (contains? coin-strings "xyz:NVDA")))
    (is (= #{"0.500 NVDA"} (set (collect-strings size-cell))))
    (is (every? #(contains? (node-class-set leverage-chip) %) expected-chip-classes))
    (is (every? #(contains? (node-class-set dex-chip) %) expected-chip-classes))))

(deftest position-row-coin-cell-uses-hyperliquid-gradient-background-test
  (let [row-node (view/position-row (sample-position-row "xyz:NVDA" 10 "0.500"))
        coin-cell (first (vec (node-children row-node)))
        expected-background "linear-gradient(90deg, rgb(31, 166, 125) 0px, rgb(31, 166, 125) 4px, rgb(11, 50, 38) 4px, transparent 100%) transparent"]
    (is (= expected-background
           (get-in coin-cell [1 :style :background])))
    (is (= "12px" (get-in coin-cell [1 :style :padding-left])))
    (is (nil? (get-in coin-cell [1 :style :margin-left])))
    (is (nil? (get-in coin-cell [1 :style :padding-right])))
    (is (contains? (node-class-set coin-cell) "self-stretch"))))

(deftest position-row-dedupes-explicit-and-prefixed-dex-label-test
  (let [row-node (view/position-row (sample-position-row "xyz:NVDA" 10 "0.500" "xyz"))
        coin-cell (first (vec (node-children row-node)))
        coin-strings (collect-strings coin-cell)]
    (is (= 1 (count (filter #(= "xyz" %) coin-strings))))
    (is (contains? (set coin-strings) "NVDA"))
    (is (contains? (set coin-strings) "10x"))))

(deftest sort-positions-by-column-uses-mark-price-over-entry-price-test
  (let [positions [{:position {:coin "AAA"
                               :entryPx "100"
                               :markPx "90"}}
                   {:position {:coin "BBB"
                               :entryPx "10"
                               :markPx "120"}}
                   {:position {:coin "CCC"
                               :entryPx "75"}}]
        asc-result (view/sort-positions-by-column positions "Mark Price" :asc)
        desc-result (view/sort-positions-by-column positions "Mark Price" :desc)]
    (is (= ["CCC" "AAA" "BBB"] (mapv #(get-in % [:position :coin]) asc-result)))
    (is (= ["BBB" "AAA" "CCC"] (mapv #(get-in % [:position :coin]) desc-result)))))

(deftest position-row-uses-safe-placeholders-for-invalid-pnl-and-funding-values-test
  (let [row-node (view/position-row {:position {:coin "HYPE"
                                                :leverage {:value 3}
                                                :szi "1.0"
                                                :positionValue "100"
                                                :entryPx "10"
                                                :markPx "11"
                                                :unrealizedPnl "not-a-number"
                                                :returnOnEquity "invalid"
                                                :liquidationPx nil
                                                :marginUsed "40"
                                                :cumFunding {:allTime "oops"}}})
        row-cells (vec (node-children row-node))
        pnl-cell (nth row-cells 5)
        funding-cell (nth row-cells 8)
        pnl-strings (set (collect-strings pnl-cell))
        funding-strings (set (collect-strings funding-cell))
        funding-value-node (find-first-node funding-cell #(= :span (first %)))]
    (is (contains? pnl-strings "--"))
    (is (contains? funding-strings "--"))
    (is (not-any? #(str/includes? % "NaN") (collect-strings row-node)))
    (is (contains? (node-class-set funding-value-node) "text-trading-text"))))

(deftest position-table-layout-prioritizes-coin-column-over-right-edge-actions-test
  (let [grid-template-class "grid-cols-[minmax(170px,1.9fr)_minmax(130px,1.2fr)_minmax(110px,1fr)_minmax(110px,1fr)_minmax(110px,1fr)_minmax(130px,1.3fr)_minmax(110px,1fr)_minmax(100px,1fr)_minmax(100px,1fr)_minmax(80px,0.8fr)]"
        header-node (view/position-table-header default-sort-state)
        row-node (view/position-row (sample-position-row "xyz:NVDA" 10 "0.500"))
        coin-cell (first (vec (node-children row-node)))
        coin-label-node (find-first-node coin-cell #(contains? (direct-texts %) "NVDA"))]
    (is (contains? (node-class-set header-node) grid-template-class))
    (is (contains? (node-class-set header-node) "min-w-[1140px]"))
    (is (contains? (node-class-set row-node) grid-template-class))
    (is (contains? (node-class-set row-node) "min-w-[1140px]"))
    (is (contains? (node-class-set coin-label-node) "whitespace-nowrap"))
    (is (not (contains? (node-class-set coin-label-node) "truncate")))))

(deftest balance-row-coin-cell-does-not-use-position-gradient-background-test
  (let [row-node (view/balance-row sample-balance-row)
        coin-cell (first (vec (node-children row-node)))]
    (is (nil? (get-in coin-cell [1 :style :background])))))

(deftest balance-row-non-usdc-coin-uses-highlight-color-test
  (let [row-node (view/balance-row (assoc sample-balance-row :coin "HYPE"))
        coin-cell (first (vec (node-children row-node)))]
    (is (= "rgb(151, 252, 228)"
           (get-in coin-cell [1 :style :color])))))

(deftest balance-row-usdc-coin-keeps-default-color-test
  (let [row-node (view/balance-row (assoc sample-balance-row :coin "USDC (Perps)"))
        coin-cell (first (vec (node-children row-node)))]
    (is (nil? (get-in coin-cell [1 :style :color])))))

(deftest open-orders-columns-right-align-numeric-cells-test
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
    (doseq [idx [4 5 6 7]]
      (is (contains? (node-class-set (nth header-cells idx)) "text-right")))
    (doseq [idx [4 5 6 7]]
      (is (contains? (node-class-set (nth row-cells idx)) "text-right")))
    (doseq [idx [8 9 10 11]]
      (is (contains? (node-class-set (nth header-cells idx)) "text-left")))
    (doseq [idx [8 9 10 11]]
      (is (contains? (node-class-set (nth row-cells idx)) "text-left")))))

(deftest normalized-open-orders-prefers-live-source-and-includes-dex-snapshots-test
  (let [live-orders [{:order {:coin "BTC" :oid 1 :side "B" :sz "1.0" :limitPx "100" :timestamp 1000}}]
        snapshot-orders [{:order {:coin "ETH" :oid 2 :side "A" :sz "2.0" :limitPx "200" :timestamp 900}}]
        snapshot-by-dex {:dex-a [{:order {:coin "SOL" :oid 3 :side "B" :sz "3.0" :limitPx "50" :timestamp 800}}]}
        with-live (view/normalized-open-orders live-orders snapshot-orders snapshot-by-dex)
        without-live (view/normalized-open-orders nil snapshot-orders snapshot-by-dex)]
    (is (= #{1 3} (set (map :oid with-live))))
    (is (= #{"BTC" "SOL"} (set (map :coin with-live))))
    (is (= #{2 3} (set (map :oid without-live))))
    (is (= #{"ETH" "SOL"} (set (map :coin without-live))))))

(deftest open-orders-coin-labels-are-bold-and-side-colored-test
  (let [open-orders [{:oid 101
                      :coin "xyz:NVDA"
                      :side "B"
                      :sz "1.0"
                      :orig-sz "1.0"
                      :px "100.0"
                      :type "Limit"
                      :time 1700000001000
                      :reduce-only false
                      :is-trigger false
                      :trigger-condition nil
                      :is-position-tpsl false}
                     {:oid 102
                      :coin "PUMP"
                      :side "A"
                      :sz "2.0"
                      :orig-sz "2.0"
                      :px "99.5"
                      :type "Limit"
                      :time 1700000000000
                      :reduce-only false
                      :is-trigger false
                      :trigger-condition nil
                      :is-position-tpsl false}]
        content (view/open-orders-tab-content open-orders {:column "Time" :direction :desc})
        long-coin-base (find-first-node content #(and (= :span (first %))
                                                      (contains? (node-class-set %) "truncate")
                                                      (contains? (direct-texts %) "NVDA")))
        short-coin-base (find-first-node content #(and (= :span (first %))
                                                       (contains? (node-class-set %) "truncate")
                                                       (contains? (direct-texts %) "PUMP")))]
    (is (some? long-coin-base))
    (is (some? short-coin-base))
    (is (contains? (node-class-set long-coin-base) "font-semibold"))
    (is (contains? (node-class-set short-coin-base) "font-semibold"))
    (is (= "rgb(151, 252, 228)"
           (get-in long-coin-base [1 :style :color])))
    (is (= "rgb(234, 175, 184)"
           (get-in short-coin-base [1 :style :color])))))

(deftest account-info-numeric-cells-use-num-utility-test
  (let [balance-node (view/balance-row sample-balance-row)
        balance-cells (vec (node-children balance-node))
        position-node (view/position-row sample-position-data)
        position-cells (vec (node-children position-node))]
    (doseq [idx [1 2 3 4]]
      (is (contains? (node-class-set (nth balance-cells idx)) "num")))
    (doseq [idx [1 2 3 4 5 6 7 8]]
      (is (contains? (node-class-set (nth position-cells idx)) "num")))))

(deftest history-tables-trade-history-left-aligns-value-columns-test
  (let [fills [{:tid 1 :coin "HYPE" :side "B" :sz "1.2" :px "100.0" :fee "0.1" :time 1700000000000}]
        fundings [{:coin "HYPE" :fundingRate "0.001" :payment "1.23" :positionSize "100.0" :time 1700000000000}]
        order-history [sample-order-history-row]
        trade-node (view/trade-history-tab-content fills)
        trade-header-cells (vec (node-children (tab-header-node trade-node)))
        trade-row-cells (vec (node-children (first-viewport-row trade-node)))
        funding-node (view/funding-history-tab-content fundings)
        funding-header-cells (vec (node-children (tab-header-node funding-node)))
        funding-row-cells (vec (node-children (first-viewport-row funding-node)))
        order-node (view/order-history-tab-content order-history)
        order-header-cells (vec (node-children (tab-header-node order-node)))
        order-row-cells (vec (node-children (first-viewport-row order-node)))]
    (doseq [idx [1 2 3 4 5 6 7]]
      (is (contains? (node-class-set (nth trade-header-cells idx)) "text-left"))
      (is (contains? (node-class-set (nth trade-row-cells idx)) "text-left")))
    (doseq [idx [3 4 5 6 7]]
      (is (not (contains? (node-class-set (nth trade-row-cells idx)) "num-right"))))
    (doseq [idx [1 2 3 4 5]]
      (is (contains? (node-class-set (nth funding-header-cells idx)) "text-left"))
      (is (contains? (node-class-set (nth funding-row-cells idx)) "text-left")))
    (doseq [idx [1 2 3 4 5 6 7 8 9 10 11 12]]
      (is (contains? (node-class-set (nth order-header-cells idx)) "text-left"))
      (is (contains? (node-class-set (nth order-row-cells idx)) "text-left")))
    (doseq [idx [4 5 6 7]]
      (is (not (contains? (node-class-set (nth order-header-cells idx)) "text-right")))
      (is (not (contains? (node-class-set (nth order-row-cells idx)) "text-right")))
      (is (not (contains? (node-class-set (nth order-row-cells idx)) "num-right"))))))

(deftest trade-history-headers-match-hyperliquid-order-and-contrast-test
  (let [fills [{:tid 1
                :coin "xyz:NVDA"
                :dir "Open Long"
                :side "B"
                :sz "0.500"
                :px "187.88"
                :tradeValue "93.94"
                :fee "0.01"
                :closedPnl "-0.01"
                :time 1700000000000}]
        content (view/trade-history-tab-content fills)
        header-node (tab-header-node content)
        header-cells (vec (node-children header-node))
        header-buttons (mapv #(first (vec (node-children %))) header-cells)
        header-labels (mapv #(first (collect-strings %)) header-buttons)]
    (is (= ["Time" "Coin" "Direction" "Price" "Size" "Trade Value" "Fee" "Closed PNL"]
           header-labels))
    (is (every? #(= :button (first %)) header-buttons))
    (is (every? #(contains? (node-class-set %) "text-trading-text-secondary") header-buttons))
    (is (contains? (node-class-set header-node) "text-trading-text-secondary"))))

(deftest trade-history-parity-renders-coin-direction-and-usdc-fields-test
  (let [fills [{:tid 1
                :coin "xyz:NVDA"
                :side "A"
                :dir "Open Long (Price Improved)"
                :sz "0.500"
                :px "187.88"
                :tradeValue "93.94"
                :fee "0.01"
                :closedPnl "-0.01"
                :time 1700000000000}
               {:tid 2
                :coin "HYPE"
                :side "S"
                :sz "2"
                :px "10"
                :fee "0.02"
                :time 1700000001000}]
        content (view/trade-history-tab-content fills)
        viewport (tab-rows-viewport-node content)
        rendered-rows (vec (node-children viewport))
        nvda-row (some #(when (contains? (set (collect-strings %)) "NVDA") %) rendered-rows)
        hype-row (some #(when (contains? (set (collect-strings %)) "HYPE") %) rendered-rows)
        nvda-row-cells (vec (node-children nvda-row))
        hype-row-cells (vec (node-children hype-row))
        nvda-coin-strings (set (collect-strings (nth nvda-row-cells 1)))
        nvda-row-strings (set (collect-strings nvda-row))
        nvda-direction-strings (set (collect-strings (nth nvda-row-cells 2)))
        hype-direction-strings (set (collect-strings (nth hype-row-cells 2)))]
    (is (some? nvda-row))
    (is (some? hype-row))
    (is (contains? nvda-coin-strings "NVDA"))
    (is (contains? nvda-coin-strings "xyz"))
    (is (not (contains? nvda-row-strings "xyz:NVDA")))
    (is (contains? nvda-direction-strings "Open Long (Price Improved)"))
    (is (contains? hype-direction-strings "Open Short"))
    (is (contains? (direct-texts (nth hype-row-cells 5)) "20.00 USDC"))
    (is (contains? (direct-texts (nth nvda-row-cells 6)) "0.01 USDC"))
    (is (contains? (direct-texts (nth nvda-row-cells 7)) "-0.01 USDC"))
    (is (contains? (direct-texts (nth hype-row-cells 7)) "--"))))

(deftest trade-history-direction-and-coin-colors-follow-action-intent-test
  (let [fills [{:tid 1
                :coin "xyz:NVDA"
                :side "B"
                :dir "Open Long"
                :sz "0.500"
                :px "187.88"
                :fee "0.01"
                :time 1700000000000}
               {:tid 2
                :coin "PUMP"
                :side "A"
                :dir "Sell"
                :sz "2"
                :px "10"
                :fee "0.02"
                :time 1700000001000}
               {:tid 3
                :coin "HYPE"
                :side "A"
                :dir "Market Order Liquidation: Close Long"
                :liquidation {:markPx "0.001780"
                              :method "market"}
                :sz "100"
                :px "0.001765"
                :fee "0.01"
                :time 1700000002000}]
        content (view/trade-history-tab-content fills)
        viewport (tab-rows-viewport-node content)
        rendered-rows (vec (node-children viewport))
        row-for (fn [needle]
                  (some #(when (contains? (set (collect-strings %)) needle)
                           %)
                        rendered-rows))
        nvda-row (row-for "NVDA")
        pump-row (row-for "PUMP")
        hype-row (row-for "HYPE")
        nvda-cells (vec (node-children nvda-row))
        pump-cells (vec (node-children pump-row))
        hype-cells (vec (node-children hype-row))
        nvda-coin-cell (nth nvda-cells 1)
        pump-coin-cell (nth pump-cells 1)
        hype-coin-cell (nth hype-cells 1)
        nvda-coin-base (find-first-node nvda-coin-cell #(and (= :span (first %))
                                                              (contains? (direct-texts %) "NVDA")))
        pump-coin-base (find-first-node pump-coin-cell #(and (= :span (first %))
                                                              (contains? (direct-texts %) "PUMP")))
        hype-coin-base (find-first-node hype-coin-cell #(and (= :span (first %))
                                                              (contains? (direct-texts %) "HYPE")))
        xyz-chip (find-first-node nvda-coin-cell #(and (= :span (first %))
                                                       (contains? (direct-texts %) "xyz")))
        nvda-direction-cell (nth nvda-cells 2)
        pump-direction-cell (nth pump-cells 2)
        hype-direction-cell (nth hype-cells 2)]
    (is (some? nvda-row))
    (is (some? pump-row))
    (is (some? hype-row))
    (is (some? nvda-coin-base))
    (is (some? pump-coin-base))
    (is (some? hype-coin-base))
    (is (some? xyz-chip))
    (is (contains? (node-class-set nvda-direction-cell) "text-success"))
    (is (contains? (node-class-set nvda-coin-base) "text-success"))
    (is (contains? (node-class-set pump-direction-cell) "text-error"))
    (is (contains? (node-class-set pump-coin-base) "text-error"))
    (is (contains? (node-class-set hype-direction-cell) "text-error"))
    (is (contains? (node-class-set hype-coin-base) "text-error"))
    (is (contains? (set (collect-strings pump-direction-cell)) "Sell"))
    (is (contains? (set (collect-strings hype-direction-cell))
                   "Market Order Liquidation: Close Long"))))

(deftest trade-history-price-improved-direction-renders-liquidation-tooltip-test
  (let [tooltip-copy "This fill price was more favorable to you than the price chart at that time, because your order provided liquidity to another user's liquidation."
        fills [{:tid 1
                :coin "PUMP"
                :side "B"
                :dir "Open Long (Price Improved)"
                :sz "100"
                :px "0.001765"
                :fee "0.01"
                :time 1700000000000}]
        content (view/trade-history-tab-content fills)
        row-node (first-viewport-row content)
        direction-cell (nth (vec (node-children row-node)) 2)
        direction-strings (set (collect-strings direction-cell))
        group-node (find-first-node direction-cell #(and (= :div (first %))
                                                         (contains? (node-class-set %) "group")))
        focusable-label (find-first-node direction-cell #(and (= :span (first %))
                                                              (= 0 (get-in % [1 :tab-index]))))
        focusable-label-classes (node-class-set focusable-label)
        tooltip-panel-node (find-first-node direction-cell #(and (= :div (first %))
                                                                 (contains? (node-class-set %) "group-hover:opacity-100")
                                                                 (contains? (node-class-set %) "group-focus-within:opacity-100")))
        tooltip-panel-classes (node-class-set tooltip-panel-node)
        tooltip-bubble-node (find-first-node tooltip-panel-node #(and (= :div (first %))
                                                                      (contains? (node-class-set %) "w-[520px]")))
        tooltip-bubble-classes (node-class-set tooltip-bubble-node)]
    (is (contains? direction-strings "Open Long (Price Improved)"))
    (is (some? group-node))
    (is (some? focusable-label))
    (is (some? tooltip-panel-node))
    (is (some? tooltip-bubble-node))
    (is (contains? (set (collect-strings tooltip-panel-node)) tooltip-copy))
    (is (contains? focusable-label-classes "underline"))
    (is (contains? focusable-label-classes "decoration-dotted"))
    (is (contains? focusable-label-classes "underline-offset-2"))
    (is (contains? tooltip-panel-classes "bottom-full"))
    (is (contains? tooltip-panel-classes "mb-2"))
    (is (contains? tooltip-panel-classes "group-hover:opacity-100"))
    (is (contains? tooltip-panel-classes "group-focus-within:opacity-100"))
    (is (contains? tooltip-bubble-classes "w-[520px]"))))

(deftest trade-history-standard-direction-does-not-render-liquidation-tooltip-test
  (let [tooltip-copy "This fill price was more favorable to you than the price chart at that time, because your order provided liquidity to another user's liquidation."
        fills [{:tid 1
                :coin "PUMP"
                :side "B"
                :dir "Open Long"
                :sz "100"
                :px "0.001765"
                :fee "0.01"
                :time 1700000000000}]
        content (view/trade-history-tab-content fills)
        row-node (first-viewport-row content)
        direction-cell (nth (vec (node-children row-node)) 2)
        tooltip-panel-node (find-first-node direction-cell #(and (= :div (first %))
                                                                 (contains? (node-class-set %) "group-hover:opacity-100")
                                                                 (contains? (node-class-set %) "group-focus-within:opacity-100")))
        direction-strings (set (collect-strings direction-cell))]
    (is (contains? direction-strings "Open Long"))
    (is (not (contains? direction-strings tooltip-copy)))
    (is (nil? tooltip-panel-node))))

(deftest trade-history-liquidation-metadata-infers-price-improved-direction-test
  (let [tooltip-copy "This fill price was more favorable to you than the price chart at that time, because your order provided liquidity to another user's liquidation."
        fills [{:tid 1
                :coin "PUMP"
                :side "B"
                :dir "Open Long"
                :liquidation {:markPx "0.001780"
                              :method "market"}
                :sz "100"
                :px "0.001765"
                :fee "0.01"
                :time 1700000000000}]
        content (view/trade-history-tab-content fills)
        row-node (first-viewport-row content)
        direction-cell (nth (vec (node-children row-node)) 2)
        direction-strings (set (collect-strings direction-cell))
        tooltip-panel-node (find-first-node direction-cell #(and (= :div (first %))
                                                                 (contains? (node-class-set %) "group-hover:opacity-100")
                                                                 (contains? (node-class-set %) "group-focus-within:opacity-100")))]
    (is (contains? direction-strings "Open Long (Price Improved)"))
    (is (contains? direction-strings tooltip-copy))
    (is (some? tooltip-panel-node))))

(deftest trade-history-liquidation-direction-remains-unmodified-test
  (let [fills [{:tid 1
                :coin "PUMP"
                :side "A"
                :dir "Market Order Liquidation: Close Long"
                :liquidation {:markPx "0.001780"
                              :method "market"}
                :sz "100"
                :px "0.001765"
                :fee "0.01"
                :time 1700000000000}]
        content (view/trade-history-tab-content fills)
        row-node (first-viewport-row content)
        direction-cell (nth (vec (node-children row-node)) 2)
        direction-strings (set (collect-strings direction-cell))]
    (is (contains? direction-strings "Market Order Liquidation: Close Long"))
    (is (not (contains? direction-strings "Market Order Liquidation: Close Long (Price Improved)")))))

(deftest trade-history-liquidation-close-direction-is-inferred-from-metadata-test
  (let [fills [{:tid 1
                :coin "PUMP"
                :side "A"
                :dir "Close Long"
                :liquidation {:markPx "0.001780"
                              :method "market"}
                :sz "100"
                :px "0.001765"
                :fee "0.01"
                :time 1700000000000}]
        content (view/trade-history-tab-content fills)
        row-node (first-viewport-row content)
        direction-cell (nth (vec (node-children row-node)) 2)
        direction-strings (set (collect-strings direction-cell))]
    (is (contains? direction-strings "Market Order Liquidation: Close Long"))
    (is (not (contains? direction-strings "Close Long (Price Improved)")))
    (is (not (contains? direction-strings "Market Order Liquidation: Close Long (Price Improved)")))))

(deftest trade-history-time-cell-renders-explorer-link-when-valid-hash-present-test
  (let [hash-value "0xcb13be47d7d3e736cc8d04346f1535020494002d72d706086edc699a96d7c121"
        fills [{:tid 1
                :coin "HYPE"
                :side "B"
                :sz "1.2"
                :px "100.0"
                :fee "0.1"
                :time 1700000000000
                :hash hash-value}]
        content (view/trade-history-tab-content fills)
        row-node (first-viewport-row content)
        time-cell (first (vec (node-children row-node)))
        time-cell-classes (node-class-set time-cell)
        link-node (find-first-node time-cell #(= :a (first %)))
        link-classes (node-class-set link-node)
        icon-node (find-first-node link-node #(= :svg (first %)))
        expected-time (view/format-open-orders-time 1700000000000)
        strings (set (collect-strings time-cell))]
    (is (some? link-node))
    (is (contains? time-cell-classes "whitespace-nowrap"))
    (is (contains? link-classes "whitespace-nowrap"))
    (is (= (str "https://app.hyperliquid.xyz/explorer/tx/" hash-value)
           (get-in link-node [1 :href])))
    (is (= "_blank" (get-in link-node [1 :target])))
    (is (= "noopener noreferrer" (get-in link-node [1 :rel])))
    (is (contains? strings expected-time))
    (is (some? icon-node))))

(deftest time-format-wrapper-parity-test
  (let [ms 1700000000000
        expected (fmt/format-local-date-time ms)]
    (is (= expected (view/format-open-orders-time ms)))
    (is (= expected (view/format-funding-history-time ms)))
    (is (nil? (view/format-open-orders-time nil)))
    (is (nil? (view/format-funding-history-time nil)))))

(deftest trade-history-time-cell-falls-back-to-plain-text-when-hash-missing-or-invalid-test
  (let [fills [{:tid 1
                :coin "HYPE"
                :side "B"
                :sz "1.2"
                :px "100.0"
                :fee "0.1"
                :time 1700000000000}
               {:tid 2
                :coin "BTC"
                :side "A"
                :sz "0.8"
                :px "95.0"
                :fee "0.05"
                :time 1700000001000
                :hash "0x1234"}]
        content (view/trade-history-tab-content fills)
        viewport (tab-rows-viewport-node content)
        rendered-rows (vec (node-children viewport))
        expected-times (set (mapv (comp view/format-open-orders-time :time) fills))
        rendered-times (->> rendered-rows
                            (map (fn [row]
                                   (let [time-cell (first (vec (node-children row)))]
                                     (is (nil? (find-first-node time-cell #(= :a (first %)))))
                                     (collect-strings time-cell))))
                            (reduce into #{}))]
    (doseq [expected expected-times]
      (is (contains? rendered-times expected)))))

(deftest trade-history-pagination-renders-only-current-page-rows-test
  (let [rows (mapv trade-history-row (range 55))
        content (@#'view/trade-history-table rows {:page-size 25
                                                   :page 2
                                                   :page-input "2"})
        viewport (tab-rows-viewport-node content)
        rendered-rows (vec (node-children viewport))
        all-strings (set (collect-strings content))]
    (is (= 25 (count rendered-rows)))
    (is (contains? all-strings "Page 2 of 3"))
    (is (contains? all-strings "Total: 55"))))

(deftest trade-history-pagination-controls-disable-prev-next-at-edges-test
  (let [rows (mapv trade-history-row (range 51))
        first-page (@#'view/trade-history-table rows {:page-size 25
                                                      :page 1
                                                      :page-input "1"})
        first-prev (find-first-node first-page #(and (= :button (first %))
                                                     (contains? (direct-texts %) "Prev")))
        first-next (find-first-node first-page #(and (= :button (first %))
                                                     (contains? (direct-texts %) "Next")))
        last-page (@#'view/trade-history-table rows {:page-size 25
                                                     :page 3
                                                     :page-input "3"})
        last-prev (find-first-node last-page #(and (= :button (first %))
                                                   (contains? (direct-texts %) "Prev")))
        last-next (find-first-node last-page #(and (= :button (first %))
                                                   (contains? (direct-texts %) "Next")))]
    (is (= true (get-in first-prev [1 :disabled])))
    (is (not= true (get-in first-next [1 :disabled])))
    (is (not= true (get-in last-prev [1 :disabled])))
    (is (= true (get-in last-next [1 :disabled])))))

(deftest trade-history-pagination-controls-wire-actions-test
  (let [rows (mapv trade-history-row (range 12))
        content (@#'view/trade-history-table rows {:page-size 25
                                                   :page 1
                                                   :page-input "4"})
        page-size-select (find-first-node content #(and (= :select (first %))
                                                        (= "trade-history-page-size" (get-in % [1 :id]))))
        jump-input (find-first-node content #(and (= :input (first %))
                                                  (= "trade-history-page-input" (get-in % [1 :id]))))
        go-button (find-first-node content #(and (= :button (first %))
                                                 (contains? (direct-texts %) "Go")))]
    (is (= [[:actions/set-trade-history-page-size [:event.target/value]]]
           (get-in page-size-select [1 :on :change])))
    (is (= [[:actions/set-trade-history-page-input [:event.target/value]]]
           (get-in jump-input [1 :on :input])))
    (is (= [[:actions/set-trade-history-page-input [:event.target/value]]]
           (get-in jump-input [1 :on :change])))
    (is (= [[:actions/handle-trade-history-page-input-keydown [:event/key] 1]]
           (get-in jump-input [1 :on :keydown])))
    (is (= [[:actions/apply-trade-history-page-input 1]]
           (get-in go-button [1 :on :click])))))

(deftest trade-history-pagination-clamps-page-when-data-shrinks-test
  (let [rows (mapv trade-history-row (range 10))
        content (@#'view/trade-history-table rows {:page-size 25
                                                   :page 4
                                                   :page-input "4"})
        viewport (tab-rows-viewport-node content)
        jump-input (find-first-node content #(and (= :input (first %))
                                                  (= "trade-history-page-input" (get-in % [1 :id]))))
        all-strings (set (collect-strings content))]
    (is (= 10 (count (vec (node-children viewport)))))
    (is (contains? all-strings "Page 1 of 1"))
    (is (= "1" (get-in jump-input [1 :value])))))

(deftest order-history-content-renders-hyperliquid-columns-and-values-test
  (let [rows [{:order {:coin "xyz:NVDA"
                       :oid 307891000622
                       :side "B"
                       :origSz "0.500"
                       :remainingSz "0.000"
                       :limitPx "0"
                       :orderType "Market"
                       :reduceOnly false
                       :isTrigger false
                       :timestamp 1700000000000}
               :status "filled"
               :statusTimestamp 1700000000500}
              {:order {:coin "PUMP"
                       :oid 275043415805
                       :side "B"
                       :origSz "11386"
                       :remainingSz "11386"
                       :limitPx "0.001000"
                       :orderType "Limit"
                       :reduceOnly true
                       :isTrigger false
                       :timestamp 1700000000000}
               :status "canceled"
               :statusTimestamp 1699999999000}]
        content (view/order-history-tab-content rows {:sort {:column "Time" :direction :desc}
                                                      :status-filter :all
                                                      :loading? false})
        strings (set (collect-strings content))]
    (is (some? (find-first-node content #(contains? (direct-texts %) "Filled Size"))))
    (is (some? (find-first-node content #(contains? (direct-texts %) "Trigger Conditions"))))
    (is (some? (find-first-node content #(contains? (direct-texts %) "Order ID"))))
    (is (contains? strings "NVDA"))
    (is (contains? strings "xyz"))
    (is (not (contains? strings "xyz:NVDA")))
    (is (contains? strings "Market"))
    (is (contains? strings "N/A"))
    (is (contains? strings "No"))
    (is (contains? strings "Yes"))
    (is (contains? strings "Filled"))
    (is (contains? strings "Canceled"))))

(deftest order-history-coin-labels-are-bold-and-side-colored-test
  (let [rows [{:order {:coin "xyz:NVDA"
                       :oid 307891000622
                       :side "B"
                       :origSz "0.500"
                       :remainingSz "0.000"
                       :limitPx "0"
                       :orderType "Market"
                       :reduceOnly false
                       :isTrigger false
                       :timestamp 1700000000000}
               :status "filled"
               :statusTimestamp 1700000000500}
              {:order {:coin "PUMP"
                       :oid 275043415805
                       :side "A"
                       :origSz "11386"
                       :remainingSz "11386"
                       :limitPx "0.001000"
                       :orderType "Limit"
                       :reduceOnly true
                       :isTrigger false
                       :timestamp 1700000000000}
               :status "canceled"
               :statusTimestamp 1699999999000}]
        content (view/order-history-tab-content rows {:sort {:column "Time" :direction :desc}
                                                      :status-filter :all
                                                      :loading? false})
        long-coin-base (find-first-node content #(and (= :span (first %))
                                                      (contains? (node-class-set %) "truncate")
                                                      (contains? (direct-texts %) "NVDA")))
        sell-coin-base (find-first-node content #(and (= :span (first %))
                                                      (contains? (node-class-set %) "truncate")
                                                      (contains? (direct-texts %) "PUMP")))]
    (is (some? long-coin-base))
    (is (some? sell-coin-base))
    (is (contains? (node-class-set long-coin-base) "font-semibold"))
    (is (contains? (node-class-set sell-coin-base) "font-semibold"))
    (is (= "rgb(151, 252, 228)"
           (get-in long-coin-base [1 :style :color])))
    (is (= "rgb(234, 175, 184)"
           (get-in sell-coin-base [1 :style :color])))))

(deftest order-history-coin-label-prefers-market-base-for-spot-id-test
  (let [rows [{:order {:coin "@230"
                       :oid 307891000622
                       :side "B"
                       :origSz "0.500"
                       :remainingSz "0.000"
                       :limitPx "0.000"
                       :orderType "Market"
                       :reduceOnly false
                       :isTrigger false
                       :isPositionTpsl false
                       :timestamp 1700000000000}
               :status "filled"
               :statusTimestamp 1700000005000}]
        content (view/order-history-tab-content rows {:sort {:column "Time" :direction :desc}
                                                      :status-filter :all
                                                      :loading? false
                                                      :market-by-key {"spot:@230" {:coin "@230"
                                                                                    :symbol "SOL/USDC"
                                                                                    :base "SOL"
                                                                                    :market-type :spot}}})
        strings (set (collect-strings content))]
    (is (contains? strings "SOL"))
    (is (not (contains? strings "@230")))))

(deftest order-history-formatting-distinguishes-market-price-and-filled-size-placeholder-test
  (let [market-row (view/normalize-order-history-row
                    {:order {:coin "NVDA"
                             :oid 1
                             :side "B"
                             :origSz "2.0"
                             :remainingSz "1.0"
                             :limitPx "0"
                             :orderType "Market"}
                     :status "filled"
                     :statusTimestamp 1700000000000})
        unfilled-limit-row (view/normalize-order-history-row
                            {:order {:coin "PUMP"
                                     :oid 2
                                     :side "A"
                                     :origSz "3.0"
                                     :remainingSz "3.0"
                                     :limitPx "0.0012"
                                     :orderType "Limit"}
                             :status "open"
                             :statusTimestamp 1700000000100})]
    (is (= "Market" (@#'view/format-order-history-price market-row)))
    (is (= "--" (@#'view/format-order-history-filled-size (:filled-size unfilled-limit-row))))
    (is (= "No" (@#'view/format-order-history-reduce-only (assoc market-row :reduce-only false))))
    (is (= "N/A" (@#'view/format-order-history-trigger market-row)))))

(deftest sort-order-history-by-column-is-deterministic-on-ties-test
  (let [rows (view/normalized-order-history
              [{:order {:coin "BTC" :oid "2" :side "B" :origSz "1.0" :remainingSz "0.0" :limitPx "1.0"}
                :status "filled"
                :statusTimestamp 2000}
               {:order {:coin "BTC" :oid "1" :side "B" :origSz "1.0" :remainingSz "0.0" :limitPx "1.0"}
                :status "filled"
                :statusTimestamp 2000}])
        time-asc (view/sort-order-history-by-column rows "Time" :asc)
        oid-desc (view/sort-order-history-by-column rows "Order ID" :desc)]
    (is (= ["1" "2"] (mapv (comp str :oid) time-asc)))
    (is (= ["2" "1"] (mapv (comp str :oid) oid-desc)))))

(deftest order-history-status-filter-controls-and-filtering-test
  (let [rows [{:order {:coin "NVDA"
                       :oid 1
                       :side "B"
                       :origSz "1.0"
                       :remainingSz "0.0"
                       :limitPx "0"
                       :orderType "Market"}
               :status "filled"
               :statusTimestamp 1700000000000}
              {:order {:coin "PUMP"
                       :oid 2
                       :side "A"
                       :origSz "1.0"
                       :remainingSz "0.0"
                       :limitPx "0.001"
                       :orderType "Limit"}
               :status "canceled"
               :statusTimestamp 1699999999000}]
        filtered-content (view/order-history-tab-content rows {:sort {:column "Time" :direction :desc}
                                                               :status-filter :filled
                                                               :loading? false})
        filtered-strings (set (collect-strings filtered-content))
        panel-state (-> sample-account-info-state
                        (assoc-in [:account-info :selected-tab] :order-history)
                        (assoc-in [:account-info :order-history]
                                  {:sort {:column "Time" :direction :desc}
                                   :status-filter :filled
                                   :filter-open? true
                                   :loading? false
                                   :error nil
                                   :request-id 1})
                        (assoc-in [:orders :order-history] rows))
        panel (view/account-info-panel panel-state)
        filter-button (find-first-node panel #(contains? (direct-texts %) "Filter"))
        filled-option (find-first-node panel #(contains? (direct-texts %) "Filled"))]
    (is (contains? filtered-strings "Filled"))
    (is (not (contains? filtered-strings "Canceled")))
    (is (some? filter-button))
    (is (some? filled-option))
    (is (= [[:actions/toggle-order-history-filter-open]]
           (get-in filter-button [1 :on :click])))
    (is (= [[:actions/set-order-history-status-filter :filled]]
           (get-in filled-option [1 :on :click])))))

(deftest order-history-pagination-renders-only-current-page-rows-test
  (let [rows (mapv order-history-row (range 55))
        content (@#'view/order-history-table rows {:sort {:column "Time" :direction :desc}
                                                   :status-filter :all
                                                   :page-size 25
                                                   :page 2
                                                   :page-input "2"
                                                   :loading? false})
        viewport (tab-rows-viewport-node content)
        rendered-rows (vec (node-children viewport))
        all-strings (set (collect-strings content))]
    (is (= 25 (count rendered-rows)))
    (is (contains? all-strings "Page 2 of 3"))
    (is (contains? all-strings "Total: 55"))))

(deftest order-history-pagination-controls-disable-prev-next-at-edges-test
  (let [rows (mapv order-history-row (range 51))
        first-page (@#'view/order-history-table rows {:sort {:column "Time" :direction :desc}
                                                      :status-filter :all
                                                      :page-size 25
                                                      :page 1
                                                      :page-input "1"
                                                      :loading? false})
        first-prev (find-first-node first-page #(and (= :button (first %))
                                                     (contains? (direct-texts %) "Prev")))
        first-next (find-first-node first-page #(and (= :button (first %))
                                                     (contains? (direct-texts %) "Next")))
        last-page (@#'view/order-history-table rows {:sort {:column "Time" :direction :desc}
                                                     :status-filter :all
                                                     :page-size 25
                                                     :page 3
                                                     :page-input "3"
                                                     :loading? false})
        last-prev (find-first-node last-page #(and (= :button (first %))
                                                   (contains? (direct-texts %) "Prev")))
        last-next (find-first-node last-page #(and (= :button (first %))
                                                   (contains? (direct-texts %) "Next")))]
    (is (= true (get-in first-prev [1 :disabled])))
    (is (not= true (get-in first-next [1 :disabled])))
    (is (not= true (get-in last-prev [1 :disabled])))
    (is (= true (get-in last-next [1 :disabled])))))

(deftest order-history-pagination-controls-wire-actions-test
  (let [rows (mapv order-history-row (range 12))
        content (@#'view/order-history-table rows {:sort {:column "Time" :direction :desc}
                                                   :status-filter :all
                                                   :page-size 25
                                                   :page 1
                                                   :page-input "4"
                                                   :loading? false})
        page-size-select (find-first-node content #(and (= :select (first %))
                                                        (= "order-history-page-size" (get-in % [1 :id]))))
        jump-input (find-first-node content #(and (= :input (first %))
                                                  (= "order-history-page-input" (get-in % [1 :id]))))
        go-button (find-first-node content #(and (= :button (first %))
                                                 (contains? (direct-texts %) "Go")))]
    (is (= [[:actions/set-order-history-page-size [:event.target/value]]]
           (get-in page-size-select [1 :on :change])))
    (is (= [[:actions/set-order-history-page-input [:event.target/value]]]
           (get-in jump-input [1 :on :input])))
    (is (= [[:actions/set-order-history-page-input [:event.target/value]]]
           (get-in jump-input [1 :on :change])))
    (is (= [[:actions/handle-order-history-page-input-keydown [:event/key] 1]]
           (get-in jump-input [1 :on :keydown])))
    (is (= [[:actions/apply-order-history-page-input 1]]
           (get-in go-button [1 :on :click])))))

(deftest order-history-pagination-clamps-page-when-data-shrinks-test
  (let [rows (mapv order-history-row (range 10))
        content (@#'view/order-history-table rows {:sort {:column "Time" :direction :desc}
                                                   :status-filter :all
                                                   :page-size 25
                                                   :page 4
                                                   :page-input "4"
                                                   :loading? false})
        viewport (tab-rows-viewport-node content)
        jump-input (find-first-node content #(and (= :input (first %))
                                                  (= "order-history-page-input" (get-in % [1 :id]))))
        all-strings (set (collect-strings content))]
    (is (= 10 (count (vec (node-children viewport)))))
    (is (contains? all-strings "Page 1 of 1"))
    (is (= "1" (get-in jump-input [1 :value])))))

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
    (is (contains? (set (collect-strings coin-value)) "BTC"))
    (is (contains? (set (collect-strings default-coin)) "ETH"))))

(deftest funding-history-row-renders-coin-chip-and-size-without-prefix-test
  (let [rows [{:id "1700000000000|xyz:NVDA|0.5|-0.42|0.0006"
               :time-ms 1700000000000
               :coin "xyz:NVDA"
               :position-size-raw 0.5
               :payment-usdc-raw -0.42
               :funding-rate-raw 0.0006}]
        content (@#'view/funding-history-table rows {:sort {:column "Time" :direction :desc}})
        row (first-viewport-row content)
        row-cells (vec (node-children row))
        coin-cell (nth row-cells 1)
        size-cell (nth row-cells 2)
        row-strings (set (collect-strings row))
        coin-strings (set (collect-strings coin-cell))
        size-strings (set (collect-strings size-cell))
        coin-base (find-first-node coin-cell #(and (= :span (first %))
                                                   (contains? (node-class-set %) "truncate")
                                                   (contains? (direct-texts %) "NVDA")))
        xyz-chip (find-first-node coin-cell #(contains? (direct-texts %) "xyz"))]
    (is (contains? coin-strings "NVDA"))
    (is (contains? coin-strings "xyz"))
    (is (not (contains? row-strings "xyz:NVDA")))
    (is (= #{"0.500 NVDA"} size-strings))
    (is (some? coin-base))
    (is (contains? (node-class-set coin-base) "font-semibold"))
    (is (= view/order-history-long-coin-color
           (get-in coin-base [1 :style :color])))
    (is (some? xyz-chip))
    (is (contains? (node-class-set xyz-chip) "bg-emerald-500/20"))))

(deftest funding-history-pagination-renders-only-current-page-rows-test
  (let [rows (mapv funding-history-row (range 55))
        content (@#'view/funding-history-table rows {:sort {:column "Time" :direction :desc}
                                                     :page-size 25
                                                     :page 2
                                                     :page-input "2"
                                                     :loading? false})
        viewport (tab-rows-viewport-node content)
        rendered-rows (vec (node-children viewport))
        all-strings (set (collect-strings content))]
    (is (= 25 (count rendered-rows)))
    (is (contains? all-strings "Page 2 of 3"))
    (is (contains? all-strings "Total: 55"))))

(deftest funding-history-pagination-controls-disable-prev-next-at-edges-test
  (let [rows (mapv funding-history-row (range 51))
        first-page (@#'view/funding-history-table rows {:sort {:column "Time" :direction :desc}
                                                        :page-size 25
                                                        :page 1
                                                        :page-input "1"
                                                        :loading? false})
        first-prev (find-first-node first-page #(and (= :button (first %))
                                                     (contains? (direct-texts %) "Prev")))
        first-next (find-first-node first-page #(and (= :button (first %))
                                                     (contains? (direct-texts %) "Next")))
        last-page (@#'view/funding-history-table rows {:sort {:column "Time" :direction :desc}
                                                       :page-size 25
                                                       :page 3
                                                       :page-input "3"
                                                       :loading? false})
        last-prev (find-first-node last-page #(and (= :button (first %))
                                                   (contains? (direct-texts %) "Prev")))
        last-next (find-first-node last-page #(and (= :button (first %))
                                                   (contains? (direct-texts %) "Next")))]
    (is (= true (get-in first-prev [1 :disabled])))
    (is (not= true (get-in first-next [1 :disabled])))
    (is (not= true (get-in last-prev [1 :disabled])))
    (is (= true (get-in last-next [1 :disabled])))))

(deftest funding-history-pagination-controls-wire-actions-test
  (let [rows (mapv funding-history-row (range 12))
        content (@#'view/funding-history-table rows {:sort {:column "Time" :direction :desc}
                                                     :page-size 25
                                                     :page 1
                                                     :page-input "4"
                                                     :loading? false})
        page-size-select (find-first-node content #(and (= :select (first %))
                                                        (= "funding-history-page-size" (get-in % [1 :id]))))
        jump-input (find-first-node content #(and (= :input (first %))
                                                  (= "funding-history-page-input" (get-in % [1 :id]))))
        go-button (find-first-node content #(and (= :button (first %))
                                                 (contains? (direct-texts %) "Go")))]
    (is (= [[:actions/set-funding-history-page-size [:event.target/value]]]
           (get-in page-size-select [1 :on :change])))
    (is (= [[:actions/set-funding-history-page-input [:event.target/value]]]
           (get-in jump-input [1 :on :input])))
    (is (= [[:actions/set-funding-history-page-input [:event.target/value]]]
           (get-in jump-input [1 :on :change])))
    (is (= [[:actions/handle-funding-history-page-input-keydown [:event/key] 1]]
           (get-in jump-input [1 :on :keydown])))
    (is (= [[:actions/apply-funding-history-page-input 1]]
           (get-in go-button [1 :on :click])))))

(deftest funding-history-pagination-clamps-page-when-data-shrinks-test
  (let [rows (mapv funding-history-row (range 10))
        content (@#'view/funding-history-table rows {:sort {:column "Time" :direction :desc}
                                                     :page-size 25
                                                     :page 4
                                                     :page-input "4"
                                                     :loading? false})
        viewport (tab-rows-viewport-node content)
        jump-input (find-first-node content #(and (= :input (first %))
                                                  (= "funding-history-page-input" (get-in % [1 :id]))))
        all-strings (set (collect-strings content))]
    (is (= 10 (count (vec (node-children viewport)))))
    (is (contains? all-strings "Page 1 of 1"))
    (is (= "1" (get-in jump-input [1 :value])))))

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
        datetime-input (find-first-node panel #(= "datetime-local" (get-in % [1 :type])))
        apply-button (find-first-node panel #(contains? (direct-texts %) "Apply"))
        cancel-button (find-first-node panel #(contains? (direct-texts %) "Cancel"))
        apply-classes (node-class-set apply-button)
        cancel-classes (node-class-set cancel-button)]
    (is (some? datetime-input))
    (is (some? apply-button))
    (is (some? cancel-button))
    (is (contains? apply-classes "btn-xs"))
    (is (contains? cancel-classes "btn-xs"))
    (is (not (contains? apply-classes "btn-sm")))
    (is (not (contains? cancel-classes "btn-sm")))
    (is (contains? apply-classes "px-3"))
    (is (contains? cancel-classes "px-3"))
    (is (contains? apply-classes "min-w-[4.5rem]"))
    (is (contains? cancel-classes "min-w-[4.5rem]"))))

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

(deftest funding-history-coin-filter-renders-prefixed-coins-as-base-plus-chip-test
  (let [funding-row-pump {:id "1700000000000|PUMP|120.0|-0.42|0.0006"
                          :time-ms 1700000000000
                          :coin "PUMP"
                          :size-raw 120.0
                          :position-size-raw 120.0
                          :position-side :long
                          :payment-usdc-raw -0.42
                          :funding-rate-raw 0.0006}
        funding-row-xyz {:id "1700000100000|xyz:GOOGL|80.0|0.18|0.0002"
                         :time-ms 1700000100000
                         :coin "xyz:GOOGL"
                         :size-raw 80.0
                         :position-size-raw 80.0
                         :position-side :long
                         :payment-usdc-raw 0.18
                         :funding-rate-raw 0.0002}
        funding-history-state {:filters {:coin-set #{}}
                               :draft-filters {:coin-set #{"xyz:GOOGL"}}
                               :filter-open? true
                               :loading? false
                               :error nil}
        controls (@#'view/funding-history-controls funding-history-state
                                                   [funding-row-pump funding-row-xyz])
        coin-checkboxes (find-all-nodes controls
                                        (fn [node]
                                          (and (= :input (first node))
                                               (= "checkbox" (get-in node [1 :type]))
                                               (= :actions/toggle-funding-history-filter-coin
                                                  (first (first (get-in node [1 :on :change])))))))
        xyz-label (find-first-node controls #(contains? (direct-texts %) "xyz"))
        googl-label (find-first-node controls #(contains? (direct-texts %) "GOOGL"))
        controls-strings (set (collect-strings controls))]
    (is (= 2 (count coin-checkboxes)))
    (is (some? xyz-label))
    (is (some? googl-label))
    (is (contains? (node-class-set xyz-label) "bg-emerald-500/20"))
    (is (contains? controls-strings "GOOGL"))
    (is (contains? controls-strings "xyz"))
    (is (not (contains? controls-strings "xyz:GOOGL")))))
